package appcore

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"teleghost/internal/core"
	"teleghost/internal/core/identity"

	"github.com/google/uuid"
)

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
		resp, err := client.Get("http://191.223.137.164:8085/nick/resolve?nick=" + url.QueryEscape(cleanNick))
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
			if name == "" || strings.HasPrefix(name, "Контакт ") {
				name = "@" + cleanNick
			}
		}
	}

	if len(destination) < 32 {
		return nil, fmt.Errorf("некорректный ID ключ Hydra (введите @никнейм или отсканируйте QR-код)")
	}
	if name == "" || (len(name) > 28 && !strings.Contains(name, " ")) {
		name = "Контакт " + destination[:6]
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
