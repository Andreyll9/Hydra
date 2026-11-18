package appcore

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"teleghost/internal/core"
	"teleghost/internal/core/identity"

	"github.com/google/uuid"
)

func resolveNickByPubFromVPS(pub string) string {
	pub = strings.TrimSpace(pub)
	if len(pub) < 32 {
		return ""
	}
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("https://rtbeta.duckdns.org/nick/resolve?pub=" + url.QueryEscape(pub))
	if err != nil || resp == nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		var res map[string]string
		if json.Unmarshal(body, &res) == nil && res["nickname"] != "" {
			n := strings.TrimPrefix(res["nickname"], "@")
			return "@" + n
		}
	}
	return ""
}

func (a *AppCore) GetContacts() ([]*ContactInfo, error) {
	if a.Repo == nil {
		return []*ContactInfo{}, nil
	}
	contacts, err := a.Repo.ListContactsWithLastMessage(a.Ctx)
	if err != nil {
		return nil, err
	}
	result := make([]*ContactInfo, len(contacts))
	for i, c := range contacts {
		updated := false
		if c.PublicKey == "" && len(c.I2PAddress) >= 32 {
			c.PublicKey = c.I2PAddress
			updated = true
		}
		if c.ChatID == "" && a.Identity != nil && a.Identity.Keys != nil && c.PublicKey != "" {
			c.ChatID = identity.CalculateChatID(a.Identity.Keys.PublicKeyBase64, c.PublicKey)
			updated = true
		}
		if (len(c.Nickname) > 28 && !strings.Contains(c.Nickname, " ")) || strings.HasPrefix(c.Nickname, "Unknown") || strings.HasPrefix(c.Nickname, "Контакт ") {
			if resolved := resolveNickByPubFromVPS(c.I2PAddress); resolved != "" {
				c.Nickname = resolved
				updated = true
			}
		}
		if updated {
			_ = a.Repo.SaveContact(a.Ctx, c)
		}
		info := &ContactInfo{
			ID:          c.ID,
			Nickname:    c.Nickname,
			Bio:         c.Bio,
			Avatar:      a.formatAvatarURL(c.Avatar),
			I2PAddress:  c.I2PAddress,
			PublicKey:   c.PublicKey,
			ChatID:      c.ChatID,
			IsBlocked:   c.IsBlocked,
			IsVerified:  c.IsVerified,
			UnreadCount: c.UnreadCount,
		}
		if c.LastMessage != "" {
			info.LastMessage = c.LastMessage
			tm := c.LastMessageTime
			info.LastMessageTime = &tm
		}
		result[i] = info
	}
	return result, nil
}

func (a *AppCore) AddContact(name, destination string) (*ContactInfo, error) {
	if a.Repo == nil || a.Identity == nil || a.Identity.Keys == nil {
		return nil, fmt.Errorf("not logged in")
	}
	name = strings.TrimSpace(name)
	destination = strings.TrimSpace(destination)

	if strings.HasPrefix(destination, "@") || (len(destination) >= 3 && len(destination) < 32 && !strings.Contains(destination, "/")) {
		cleanNick := strings.ToLower(strings.TrimPrefix(destination, "@"))
		client := &http.Client{Timeout: 8 * time.Second}
		resp, err := client.Get("https://rtbeta.duckdns.org/nick/resolve?nick=" + url.QueryEscape(cleanNick))
		if err != nil {
			return nil, fmt.Errorf("нет связи с сервером никнеймов: %v", err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("пользователь @%s не найден на сервере", cleanNick)
		}
		body, _ := io.ReadAll(resp.Body)
		var res map[string]string
		if json.Unmarshal(body, &res) == nil && res["pub_key"] != "" {
			destination = strings.TrimSpace(res["pub_key"])
			if name == "" || strings.HasPrefix(name, "Контакт ") || len(name) > 28 {
				name = "@" + cleanNick
			}
		}
	}

	if len(destination) < 32 {
		return nil, fmt.Errorf("некорректный ID ключ Hydra (введите @никнейм или отсканируйте QR-код)")
	}

	if name == "" || (len(name) > 28 && !strings.Contains(name, " ")) || strings.HasPrefix(name, "Контакт ") {
		if resolved := resolveNickByPubFromVPS(destination); resolved != "" {
			name = resolved
		} else if name == "" || len(name) > 28 {
			name = "Контакт " + destination[:6]
		}
	}

	myPub := a.Identity.Keys.PublicKeyBase64
	chatID := identity.CalculateChatID(myPub, destination)

	if existing, _ := a.Repo.GetContactByPublicKey(a.Ctx, destination); existing != nil {
		existing.Nickname = name
		existing.I2PAddress = destination
		existing.PublicKey = destination
		existing.ChatID = chatID
		_ = a.Repo.SaveContact(a.Ctx, existing)
		a.Emitter.Emit("contact_updated")
		return &ContactInfo{
			ID:         existing.ID,
			Nickname:   existing.Nickname,
			I2PAddress: existing.I2PAddress,
			PublicKey:  existing.PublicKey,
			ChatID:     existing.ChatID,
		}, nil
	}

	contact := &core.Contact{
		ID:         uuid.New().String(),
		Nickname:   name,
		I2PAddress: destination,
		PublicKey:  destination,
		ChatID:     chatID,
		AddedAt:    time.Now(),
		UpdatedAt:  time.Now(),
	}
	if err := a.Repo.SaveContact(a.Ctx, contact); err != nil {
		return nil, err
	}
	if a.Messenger != nil {
		go func(dst string) { _ = a.Messenger.SendHandshake(dst) }(destination)
	}
	a.Emitter.Emit("contact_updated")
	return &ContactInfo{
		ID:         contact.ID,
		Nickname:   contact.Nickname,
		I2PAddress: contact.I2PAddress,
		PublicKey:  contact.PublicKey,
		ChatID:     contact.ChatID,
	}, nil
}

func (a *AppCore) AddContactFromClipboard(name string) (*ContactInfo, error) {
	data, err := a.Platform.ClipboardGet()
	if err != nil {
		return nil, fmt.Errorf("clipboard error: %w", err)
	}
	if data == "" {
		return nil, fmt.Errorf("clipboard is empty")
	}
	return a.AddContact(name, data)
}

func (a *AppCore) DeleteContact(id string) error {
	if a.Repo == nil {
		return fmt.Errorf("not logged in")
	}
	err := a.Repo.DeleteContact(a.Ctx, id)
	if err == nil {
		a.Emitter.Emit("contact_updated")
	}
	return err
}
