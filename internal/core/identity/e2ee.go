package identity

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base64"
	"errors"
	"io"
	"math/big"
	"strings"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
)

const E2EEPrefix = "[HYDRA_E2EE]:"

func ed25519PrivToX25519(edPriv []byte) []byte {
	h := sha512.Sum512(edPriv[:32])
	h[0] &= 248
	h[31] &= 127
	h[31] |= 64
	return h[:32]
}

func ed25519PubToX25519(edPub []byte) ([]byte, error) {
	if len(edPub) != 32 {
		return nil, errors.New("invalid ed25519 pubkey length")
	}
	// p = 2^255 - 19
	p := new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 255), big.NewInt(19))
	yBE := make([]byte, 32)
	for i := 0; i < 32; i++ {
		yBE[31-i] = edPub[i]
	}
	yBE[0] &= 0x7f
	y := new(big.Int).SetBytes(yBE)

	one := big.NewInt(1)
	num := new(big.Int).Add(one, y)
	den := new(big.Int).Sub(one, y)
	den.Mod(den, p)
	denInv := new(big.Int).ModInverse(den, p)
	if denInv == nil {
		return nil, errors.New("invalid curve point")
	}
	u := new(big.Int).Mul(num, denInv)
	u.Mod(u, p)

	uBE := make([]byte, 32)
	u.FillBytes(uBE)
	uLE := make([]byte, 32)
	for i := 0; i < 32; i++ {
		uLE[i] = uBE[31-i]
	}
	return uLE, nil
}

func (k *Keys) deriveSharedKey(peerPubBase64 string) ([]byte, error) {
	peerPubBase64 = strings.TrimSpace(peerPubBase64)
	if strings.HasPrefix(peerPubBase64, "SFlEUkFfUk9PTV") {
		sum := sha256.Sum256([]byte("HYDRA-ROOM-E2EE-V1:" + peerPubBase64))
		return sum[:], nil
	}
	peerBytes, err := base64.StdEncoding.DecodeString(peerPubBase64)
	if err != nil {
		return nil, err
	}
	xPub, err := ed25519PubToX25519(peerBytes)
	if err != nil {
		return nil, err
	}
	xPriv := ed25519PrivToX25519(k.SigningPrivateKey)
	shared, err := curve25519.X25519(xPriv, xPub)
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(shared)
	return sum[:], nil
}

// EncryptForPeer шифрует строку ключом X25519 + XChaCha20-Poly1305
func (k *Keys) EncryptForPeer(peerPubBase64, plaintext string) string {
	if plaintext == "" || strings.HasPrefix(plaintext, E2EEPrefix) {
		return plaintext
	}
	symKey, err := k.deriveSharedKey(peerPubBase64)
	if err != nil {
		return plaintext
	}
	aead, err := chacha20poly1305.NewX(symKey)
	if err != nil {
		return plaintext
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return plaintext
	}
	sealed := aead.Seal(nonce, nonce, []byte(plaintext), nil)
	return E2EEPrefix + base64.StdEncoding.EncodeToString(sealed)
}

// DecryptFromPeer расшифровывает строку, если она имеет префикс [HYDRA_E2EE]:
func (k *Keys) DecryptFromPeer(peerPubBase64, payload string) string {
	if !strings.HasPrefix(payload, E2EEPrefix) {
		return payload
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(payload, E2EEPrefix))
	if err != nil {
		return payload
	}
	symKey, err := k.deriveSharedKey(peerPubBase64)
	if err != nil {
		return payload
	}
	aead, err := chacha20poly1305.NewX(symKey)
	if err != nil || len(raw) < aead.NonceSize() {
		return payload
	}
	nonce, ct := raw[:aead.NonceSize()], raw[aead.NonceSize():]
	plain, err := aead.Open(nil, nonce, ct, nil)
	if err != nil {
		return payload
	}
	return string(plain)
}
