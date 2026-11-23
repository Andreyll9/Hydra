package appcore

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

var globalLogFile *os.File

func (a *AppCore) applyFileLogging(enabled bool) {
	logPath := filepath.Join(a.DataDir, "hydra_network.log")
	if enabled {
		if globalLogFile == nil {
			f, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
			if err == nil {
				globalLogFile = f
				log.SetOutput(io.MultiWriter(os.Stderr, f))
				log.Println("[HydraLog] Локальная диагностика сокета включена")
			}
		}
	} else {
		if globalLogFile != nil {
			log.SetOutput(os.Stderr)
			_ = globalLogFile.Close()
			globalLogFile = nil
		}
	}
}

func (a *AppCore) GetMyDestination() string {
	if a.Messenger == nil {
		if a.Identity != nil && a.Identity.Keys != nil {
			return a.Identity.Keys.PublicKeyBase64
		}
		return ""
	}
	return a.Messenger.GetDestination()
}

func (a *AppCore) GetRouterSettings() *RouterSettings {
	settingsFile := filepath.Join(a.DataDir, "router_settings.json")
	defaultSettings := &RouterSettings{
		TunnelLength: 1,
		LogToFile:    false,
	}
	data, err := os.ReadFile(settingsFile)
	if err != nil {
		return defaultSettings
	}
	var settings RouterSettings
	if err := json.Unmarshal(data, &settings); err != nil {
		return defaultSettings
	}
	a.applyFileLogging(settings.LogToFile)
	return &settings
}

// GetAppAboutInfo также выполняет реальную проверку VPS из Go и синхронизирует реальный статус сети!
func (a *AppCore) GetAppAboutInfo() *AppAboutInfo {
	myPub := a.GetMyDestination()
	start := time.Now()
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Get("http://191.223.137.164:8085/health?pub=" + url.QueryEscape(myPub))
	netInfo := "Hydra Relay v2 (Офлайн — нет связи с VPS)"
	if err == nil {
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		var h map[string]interface{}
		_ = json.Unmarshal(body, &h)
		rtt := time.Since(start).Milliseconds()
		a.SetNetworkStatus(StatusOnline)
		netInfo = fmt.Sprintf("✅ Канал E2EE активен! Отклик: %d мс (онлайн: %v)", rtt, h["online_peers"])
	} else {
		yaOk, gOk := false, false
		probe := &http.Client{Timeout: 3 * time.Second}
		if r1, e1 := probe.Get("https://ya.ru"); e1 == nil && r1 != nil {
			yaOk = true
			r1.Body.Close()
		}
		if r2, e2 := probe.Get("https://www.google.com/generate_204"); e2 == nil && r2 != nil {
			gOk = true
			r2.Body.Close()
		}
		if yaOk || gOk {
			a.SetNetworkStatus(StatusConnecting)
			netInfo = fmt.Sprintf("⚠️ Интернет работает (Яндекс: %v, Google: %v), переподключение к VPS...", yaOk, gOk)
		} else {
			a.SetNetworkStatus(StatusOffline)
			netInfo = "❌ Нет интернета (Яндекс и Google недоступны)"
		}
	}

	return &AppAboutInfo{
		AppVersion: "3.3.0-hydra",
		I2PVersion: netInfo,
		I2PPath:    "Локальная зашифрованная БД (SQLite E2EE)",
		Author:     "@RTbeta (Hydra) | Оригинал ядра: @nullcoredev (TeleGhost)",
		License:    "MIT / Open Source",
	}
}

func (a *AppCore) SaveRouterSettings(settings map[string]interface{}) error {
	settingsFile := filepath.Join(a.DataDir, "router_settings.json")
	current := a.GetRouterSettings()

	if rawVal, exists := settings["tunnelLength"]; exists {
		switch v := rawVal.(type) {
		case float64:
			current.TunnelLength = int(v)
		case int:
			current.TunnelLength = v
		case string:
			if parsed, err := strconv.Atoi(v); err == nil {
				current.TunnelLength = parsed
			}
		}
	}
	if val, ok := settings["logToFile"].(bool); ok {
		current.LogToFile = val
	}
	a.applyFileLogging(current.LogToFile)

	data, err := json.MarshalIndent(current, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(settingsFile, data, 0600)
}

func (a *AppCore) GetNetworkStatus() string {
	return string(a.Status)
}
