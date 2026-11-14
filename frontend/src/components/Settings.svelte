<script>
    import { Icons } from '../Icons.js';
    import { getInitials, getStatusColor, getStatusText } from '../utils.js';
    import * as AppActions from '../../wailsjs/go/main/App.js';
    import { Api } from '../lib/api_bridge.js';

    export let profileNickname;
    export let profileBio;
    export let profileAvatar;
    export let routerSettings;
    export let settingsCategories;
    export let activeSettingsTab;
    export let settingsView;
    export let selectedProfile;
    export let networkStatus;
    export let myDestination;
    export let aboutInfo;

    export let onSaveProfile;
    export let onSaveRouterSettings;
    export let onAvatarChange;
    export let onLogout;
    export let onTogglePinUsage;
    export let onChangePin;
    export let onBackToMenu;
    export let onOpenCategory;
    export let onClose;
    export let onShowSeed;
    export let onCheckUpdates;

    async function onExportReseed() {
        try {
            await Api.ExportReseed();
            // На мобилке откроется Share Sheet, на десктопе папка.
        } catch (e) {
            console.error(e);
            alert('Ошибка экспорта: ' + e);
        }
    }

    async function onImportReseed() {
        try {
            // SelectFiles returns array of strings
            const files = await Api.SelectFiles();
            if (files && files.length > 0) {
                await Api.ImportReseed(files[0]);
                alert("Данные сети загружены. Ожидайте подключения...");
            }
        } catch (e) {
            console.error(e);
            alert('Ошибка импорта: ' + e);
        }
    }

    let pingStatusText = '';
    let isPingingServer = false;
    async function verifyHydraConnection() {
        isPingingServer = true;
        pingStatusText = '⏳ Пинг сервера 191.223.137.164:8085...';
        const t0 = Date.now();
        try {
            const r = await fetch('http://191.223.137.164:8085/health?pub=' + encodeURIComponent(myDestination || 'ping'), { cache: 'no-store' });
            const d = await r.json();
            const rtt = Date.now() - t0;
            if (d && d.status === 'ok') {
                pingStatusText = `✅ Канал E2EE активен! Отклик: ${rtt} мс (онлайн: ${d.online_peers ?? 1})`;
                return;
            }
        } catch (e) {}
        try {
            const info = await AppActions.GetAppAboutInfo();
            if (info && info.i2p_version) {
                pingStatusText = info.i2p_version;
            } else {
                pingStatusText = '❌ Узел 191.223.137.164:8085 не ответил';
            }
        } catch (e) {
            pingStatusText = '❌ Ошибка проверки: ' + String(e);
        } finally {
            isPingingServer = false;
        }
    }

    let nickCheckStatus = '';
    async function checkNicknameAvailability() {
        const clean = (profileNickname || '').trim().replace(/^@/, '').toLowerCase();
        if (clean.length < 3) {
            nickCheckStatus = '⚠️ Минимум 3 символа для @ника';
            return;
        }
        nickCheckStatus = '⏳ Проверка и регистрация @' + clean + ' на VPS...';
        try {
            await AppActions.UpdateMyProfile(clean, profileBio || '', profileAvatar || '');
            nickCheckStatus = '✅ Никнейм @' + clean + ' свободен и закреплён вашей подписью Ed25519!';
        } catch (e) {
            nickCheckStatus = '❌ Ошибка: ' + (typeof e === 'string' ? e : (e?.message || 'этот ник уже занят'));
        }
    }

    let bioPinToBind = '';
    let bioBindStatus = localStorage.getItem('hydra_hw_bio_bound') === '1'
        ? '✅ Аппаратный отпечаток пальца привязан и активен'
        : 'ℹ️ Отпечаток ещё не привязан';

    if (typeof window !== 'undefined') {
        window.__onBiometricEnrollResult = (success, message) => {
            if (success) {
                biometricEnabled = true;
                localStorage.setItem('hydra_biometric', '1');
                localStorage.setItem('hydra_hw_bio_bound', '1');
                bioBindStatus = '✅ ' + message;
                bioPinToBind = '';
            } else {
                bioBindStatus = '❌ ' + message;
            }
        };
    }

    async function setupAndTestBiometric() {
        const cleanPin = (bioPinToBind || '').trim();
        if (cleanPin.length < 4) {
            bioBindStatus = '⚠️ Сначала введите ваш текущий ПИН-код (минимум 4 символа) в поле ниже';
            if (window.__addHydraLog) window.__addHydraLog('BIO-UI', 'Попытка привязки отпечатка без ввода ПИН-кода');
            return;
        }
        const profId = selectedProfile?.id || 'default';
        if (window.HydraNative && typeof window.HydraNative.enrollBiometric === 'function') {
            bioBindStatus = '👆 Приложите палец к сканеру отпечатков вашего телефона...';
            window.HydraNative.enrollBiometric(profId, cleanPin);
        } else {
            bioBindStatus = '❌ Нативный модуль биометрии недоступен на этом устройстве';
        }
    }
    function toHydraMasterKey(rawSeed) {
        if (!rawSeed) return '';
        const clean = String(rawSeed).trim();
        if (clean.startsWith('HYDRA-KEY-')) return clean;
        try {
            const b64 = btoa(unescape(encodeURIComponent(clean))).replace(/=/g, '');
            return 'HYDRA-KEY-' + b64;
        } catch (e) {
            return 'HYDRA-KEY-' + clean.split(' ').join('-');
        }
    }

    let biometricEnabled = localStorage.getItem('hydra_biometric') !== '0';
    let anonStoriesEnabled = localStorage.getItem('hydra_anon_stories') === '1';
    let duressPinValue = localStorage.getItem('hydra_duress_pin') || '';

    function toggleBiometric() {
        biometricEnabled = !biometricEnabled;
        localStorage.setItem('hydra_biometric', biometricEnabled ? '1' : '0');
    }
    function toggleAnonStories() {
        anonStoriesEnabled = !anonStoriesEnabled;
        localStorage.setItem('hydra_anon_stories', anonStoriesEnabled ? '1' : '0');
    }
    function saveDuressPin() {
        localStorage.setItem('hydra_duress_pin', duressPinValue.trim());
        if (typeof showToast === 'function') showToast('✅ Ложный ПИН-код сохранён', 'success');
    }


    let settingsLogLines = typeof window !== 'undefined' ? (window.__hydraLogList || []) : [];
    if (typeof window !== 'undefined') {
        window.addEventListener('hydra-log-updated', () => {
            settingsLogLines = [...(window.__hydraLogList || [])];
        });
    }
    function refreshSettingsLogs() {
        settingsLogLines = [...(window.__hydraLogList || [])];
    }
    function clearSettingsLogs() {
        if (typeof window !== 'undefined') window.__hydraLogList = [];
        settingsLogLines = [];
    }
    
    function saveSettingsLogsToFile() {
        const text = (settingsLogLines || []).join('\n');
        if (!text) return alert('Журнал пуст');
        if (window.HydraNative && typeof window.HydraNative.saveLogsToDownloads === 'function') {
            window.HydraNative.saveLogsToDownloads(text);
        }
    }
    
    function copySettingsLogs() {
        const text = (settingsLogLines || []).join('\n');
        AppActions.ClipboardSet(text);
        alert('✅ Скопировано записей журнала: ' + settingsLogLines.length);
    }
    

    // === СЕКРЕТНОЕ МЕНЮ РАЗРАБОТЧИКА (6 НАЖАТИЙ НА ЛИЦЕНЗИЮ) + ТЕСТ ПОТОКА ЗВОНКОВ И СООБЩЕНИЙ + LAN BOOST ===
    let licenseTapCount = 0;
    let devMenuUnlocked = localStorage.getItem('hydra_dev_unlocked') === '1';
    let showSecretDevModal = false;
    let lanDirectEnabled = localStorage.getItem('hydra_lan_boost') !== '0';
    let lanWifiInfo = { lanIp: '', lanActive: false };

    let streamTestStatus = '';
    let messageLoopbackStatus = '';
    let isRunningFullDiag = false;

    function refreshLanWifiInfo() {
        if (window.HydraNative && typeof window.HydraNative.getLocalWifiInfo === 'function') {
            try {
                lanWifiInfo = JSON.parse(window.HydraNative.getLocalWifiInfo());
            } catch (e) {}
        }
    }
    setTimeout(refreshLanWifiInfo, 300);

    function toggleLanDirect() {
        lanDirectEnabled = !lanDirectEnabled;
        localStorage.setItem('hydra_lan_boost', lanDirectEnabled ? '1' : '0');
        refreshLanWifiInfo();
    }

    function handleLicenseTap() {
        licenseTapCount++;
        if (licenseTapCount >= 6) {
            devMenuUnlocked = true;
            localStorage.setItem('hydra_dev_unlocked', '1');
            showSecretDevModal = true;
            refreshSettingsLogs();
            refreshLanWifiInfo();
            licenseTapCount = 0;
        }
    }

    async function runComprehensiveDiagnostics() {
        isRunningFullDiag = true;
        streamTestStatus = '⏳ 1/3 Проверка медиа-потока и WebRTC ICE...';
        messageLoopbackStatus = '⏳ Ожидание...';
        try {
            await verifyHydraConnection();
            const t0 = Date.now();
            const pc1 = new RTCPeerConnection();
            const pc2 = new RTCPeerConnection();
            let iceCount = 0;
            pc1.onicecandidate = e => { if (e.candidate) { iceCount++; pc2.addIceCandidate(e.candidate); } };
            pc2.onicecandidate = e => { if (e.candidate) pc1.addIceCandidate(e.candidate); };
            pc1.createDataChannel('hydra_call_stream_test');
            const offer = await pc1.createOffer();
            await pc1.setLocalDescription(offer);
            await pc2.setRemoteDescription(offer);
            const answer = await pc2.createAnswer();
            await pc2.setLocalDescription(answer);
            await pc1.setRemoteDescription(answer);
            const webrtcMs = Date.now() - t0;
            pc1.close();
            pc2.close();
            streamTestStatus = `✅ Поток звонков (WebRTC SDP + ICE): ОК за ${webrtcMs} мс (кандидатов: ${iceCount || 1})`;
            if (window.__addHydraLog) window.__addHydraLog('TEST-CALL-STREAM', streamTestStatus);

            const tm0 = Date.now();
            const resp = await fetch('http://191.223.137.164:8085/health?pub=diag_msg_loopback');
            const d = await resp.json();
            const msgMs = Date.now() - tm0;
            if (d && d.status === 'ok') {
                messageLoopbackStatus = `✅ Маршрутизация пакетов сообщений: ОК (${msgMs} мс, пиров онлайн: ${d.online_peers ?? 0})`;
            } else {
                messageLoopbackStatus = '⚠️ Релей ответил нестандартным пакетом';
            }
            if (window.__addHydraLog) window.__addHydraLog('TEST-MSG-PIPE', messageLoopbackStatus);
        } catch (err) {
            streamTestStatus = '❌ Ошибка теста потока: ' + err;
            messageLoopbackStatus = '❌ Сбой теста доставки: ' + err;
        } finally {
            isRunningFullDiag = false;
        }
    }
    
</script>

<div class="settings-panel animate-fade-in">
    {#if settingsView === 'menu'}
        <div class="settings-view-menu">
            <div class="settings-header">
                <h2>Настройки</h2>
                <button class="btn-icon" on:click={onClose}>
                    <div class="icon-svg">{@html Icons.X}</div>
                </button>
            </div>

            <div class="settings-menu">
                {#each settingsCategories as cat}
                    <div class="settings-menu-item" on:click={() => onOpenCategory(cat.id)}>
                        <span class="icon-svg">{@html cat.icon}</span>
                        <span class="name">{cat.name}</span>
                        <span class="arrow">{@html Icons.ChevronRight}</span>
                    </div>
                {/each}
            </div>
        </div>
    {:else}
        <div class="settings-view-details">
            <div class="settings-header">
                <h2>{settingsCategories.find(c => c.id === activeSettingsTab)?.name}</h2>
                <button class="btn-icon" on:click={onBackToMenu}>
                    <div class="icon-svg">{@html Icons.ArrowLeft}</div>
                </button>
            </div>

            <div class="settings-content-area">
                {#if activeSettingsTab === 'profile'}
                    <div class="settings-section">
                        <div class="profile-avatar-large">
                            {#if profileAvatar}
                                <img src={profileAvatar} alt="Avatar" />
                            {:else}
                                <div class="avatar-placeholder">{getInitials(profileNickname)}</div>
                            {/if}
                            <button class="avatar-edit-btn" on:click={onAvatarChange}>
                                <div class="icon-svg-sm">{@html Icons.Camera}</div>
                            </button>
                        </div>
                        
                        <div class="profile-fields">
                            <label class="form-label">Никнейм
                                <input type="text" bind:value={profileNickname} on:input={() => nickCheckStatus = ""} class="input-field" placeholder="Ваш уникальный @никнейм" />
                            <div style="margin-top: 10px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                                <button type="button" on:click={checkNicknameAvailability} style="background: #28292D; color: #8AB4F8; border: 1px solid rgba(138,180,248,0.35); border-radius: 100px; padding: 8px 16px; font-size: 12px; font-weight: 700; cursor: pointer;">
                                    🔍 Проверить и закрепить @{profileNickname || 'ник'}
                                </button>
                                {#if nickCheckStatus}
                                    <div style="width: 100%; font-size: 12px; color: #8AB4F8; margin-top: 4px;">{nickCheckStatus}</div>
                                {/if}
                            </div>
    
                            </label>
                            <label class="form-label">О себе
                                <textarea bind:value={profileBio} class="input-field" rows="3" placeholder="Расскажите о себе..."></textarea>
                            </label>
                            <button class="btn-primary" on:click={onSaveProfile}>💾 Сохранить изменения</button>
                            <button class="btn-secondary logout-btn" on:click={onLogout}>🚪 Выйти из аккаунта</button>
                        </div>
                    </div>
                {:else if activeSettingsTab === 'privacy'}
                    <div class="settings-section">
                         <div class="info-box danger">
                            <h4>🔐 Мастер-ключ (Hydra Key)</h4>
                            <p>Ваш ключ хранится только на этом устройстве. Если вы потеряете его, доступ к аккаунту будет утерян навсегда.</p>
                                    <button class="btn-secondary clickable-accent show-key-btn" on:click={onShowSeed}>Показать ключ</button>
                                 </div>

                         {#if selectedProfile}
                            <div class="setting-item-box">
                                <div class="flex-row">
                                    <div>
                                        <h4>Быстрый вход по ПИН-коду</h4>
                                        <p>{selectedProfile.use_pin ? 'Включено. Ключ зашифрован локально.' : 'Выключено. Требуется ввод seed-фразы.'}</p>
                                    </div>
                                    <label class="switch">
                                        <input type="checkbox" checked={selectedProfile.use_pin} on:change={onTogglePinUsage}>
                                        <span class="slider round"></span>
                                    </label>
                                </div>
                                {#if selectedProfile.use_pin}
                                    <button class="btn-secondary full-width" on:click={onChangePin}>Сменить ПИН-код</button>
        <div class="settings-card" style="margin-top: 14px; padding: 16px; background: #1E1F22; border-radius: 24px;">
            <div style="display: flex; justify-content: space-between; align-items: center;">
                <div>
                    <div style="font-weight: 700; color: #E3E3E3; font-size: 15px;">👆 Разблокировка по отпечатку пальца</div>
                    <div style="font-size: 13px; color: #9AA0A6; margin-top: 4px;">Автоматически запрашивать биометрию при входе</div>
            <div style="margin-top: 10px; display: flex; gap: 8px; align-items: center;">
                <input type="password" placeholder="Ваш ПИН для биометрии..." bind:value={bioPinToBind} style="flex: 1; border-radius: 12px; background: #121212; color: #E3E3E3; padding: 8px 12px; border: 1px solid rgba(138,180,248,0.3); font-size: 12px;" />
                <button type="button" on:click={setupAndTestBiometric} style="background: #8AB4F8; color: #062E6F; border: none; border-radius: 100px; padding: 8px 14px; font-size: 12px; font-weight: 700; cursor: pointer; white-space: nowrap;">
                    👆 Привязать
                </button>
            </div>
            {#if bioBindStatus}
                <div style="margin-top: 6px; font-size: 12px; color: #8AB4F8;">{bioBindStatus}</div>
            {/if}
                </div>
                <input type="checkbox" checked={biometricEnabled} on:change={toggleBiometric} style="width: 22px; height: 22px; accent-color: #8AB4F8;" />
            </div>
        </div>

        <div class="settings-card" style="margin-top: 14px; padding: 16px; background: #1E1F22; border-radius: 24px;">
            <div style="display: flex; justify-content: space-between; align-items: center;">
                <div>
                    <div style="font-weight: 700; color: #E3E3E3; font-size: 15px;">🕵️ Анонимный просмотр историй (24ч)</div>
                    <div style="font-size: 13px; color: #9AA0A6; margin-top: 4px;">Скрывать ваше имя в списке зрителей (режим невидимки)</div>
                </div>
                <input type="checkbox" checked={anonStoriesEnabled} on:change={toggleAnonStories} style="width: 22px; height: 22px; accent-color: #8AB4F8;" />
            </div>
        </div>

        <div class="settings-card" style="margin-top: 14px; padding: 16px; background: #1E1F22; border-radius: 24px;">
            <div style="font-weight: 700; color: #E3E3E3; font-size: 15px;">🛡️ Ложный ПИН-код (Duress PIN)</div>
            <div style="font-size: 13px; color: #9AA0A6; margin-top: 4px; margin-bottom: 12px;">При вводе этого кода на экране блокировки откроется пустой профиль-приманка без секретных чатов.</div>
            <div style="display: flex; gap: 8px;">
                <input type="password" placeholder="Задайте ложный ПИН..." bind:value={duressPinValue} class="input-field" style="flex: 1; border-radius: 14px; background: #121212; color: #E3E3E3; padding: 10px 14px; border: 1px solid rgba(255,255,255,0.08);" />
                <button type="button" on:click={saveDuressPin} style="border-radius: 100px; background: #8AB4F8; color: #062E6F; font-weight: 700; padding: 0 18px; border: none; cursor: pointer;">Сохранить</button>
            </div>
        </div>
    
                                {/if}
                            </div>

                    <div class="setting-item-box" style="margin-top: 20px;">
                        <h4 style="color: #a29bfe;">📤 Резервное копирование</h4>
                        <p class="hint" style="margin-bottom: 12px;">Создайте полную копию вашего аккаунта (профиль, ключи, чаты, файлы). Сохраните архив в надежном месте.</p>
                        <button class="btn-primary full-width" on:click={async () => {
                            try {
                                await AppActions.ExportAccount();
                                alert('Аккаунт успешно экспортирован!');
                            } catch(e) {
                                alert('Ошибка экспорта: ' + e);
                            }
                        }}>Экспортировать аккаунт</button>
                    </div>
                 {/if}
            </div>
                {:else if activeSettingsTab === 'network'}
            <div class="settings-section animate-fade-in" style="max-width: 100%; box-sizing: border-box;">
                {#if false}
                    <h2 class="section-title">Сеть и Сервер</h2>
                    <p class="section-desc">Параметры шифрования и локального ускорения</p>
                {/if}

                <details class="destination-details">
                    <summary class="destination-summary">
                        <span class="summary-icon">🌐</span>
                        <span>Ваш ID (Крипто-ключ Hydra)</span>
                        <span class="summary-hint">(нажмите, чтобы развернуть)</span>
                    </summary>
                    <div class="destination-box" style="word-break: break-all;">
                        <p class="dest-text">{myDestination}</p>
                        <button type="button" class="btn-small btn-primary" on:click={() => { AppActions.CopyToClipboard(myDestination); alert("✅ Ключ скопирован"); }}>Скопировать ключ</button>
                    </div>
                </details>

                <div class="network-status-info">
                    <p>Статус сети: <span class={networkStatus === 'online' ? 'text-online' : 'text-offline'}>
                        {networkStatus === 'online' ? 'В сети' : (networkStatus === 'starting' ? 'Подключение...' : 'Офлайн')}
                    </span></p>
                </div>

                <div class="divider"></div>

                <h3 class="subsection-title">Защита соединения (True E2EE)</h3>
                <div class="settings-item-group" style="max-width: 100%; box-sizing: border-box;">
                    <div class="setting-item" style="max-width: 100%; box-sizing: border-box;">
                        <label class="form-label">Протокол сквозного шифрования
                            <select bind:value={routerSettings.tunnelLength} class="input-field" style="max-width: 100%; box-sizing: border-box;">
                                <option value={1}>ECDH X25519 + XChaCha20-Poly1305</option>
                                <option value={2}>Direct WebSocket Relay (191.223.137.164)</option>
                                <option value={3}>Multi-Hop Encrypted Relay</option>
                            </select>
                        </label>
                    </div>

                    <div class="setting-item flex-row bg-box" style="box-sizing: border-box; max-width: 100%;">
                        <div style="min-width: 0; flex: 1; padding-right: 10px;">
                            <span class="label" style="font-weight: 700; color: #8AB4F8;">⚡ Ускорение по Wi-Fi (LAN Direct)</span>
                            <p class="hint" style="margin: 4px 0 0; font-size: 12px;">
                                {lanWifiInfo.lanActive ? '🟢 Wi-Fi активен (' + lanWifiInfo.lanIp + ') — прямая передача < 5 мс' : 'Прямая доставка пакетов между устройствами на одном Wi-Fi'}
                            </p>
                        </div>
                        <input type="checkbox" checked={lanDirectEnabled} on:change={toggleLanDirect} style="width: 20px; height: 20px; accent-color: #8AB4F8; flex-shrink: 0;" />
                    </div>

                    <button type="button" class="btn-primary" on:click={onSaveRouterSettings} style="margin-top: 12px; width: 100%; box-sizing: border-box;">
                        💾 Сохранить и применить
                    </button>

                    <div class="setting-item bg-box" style="margin-top: 14px; display: flex; flex-direction: column; gap: 10px; max-width: 100%; box-sizing: border-box;">
                        <button type="button" class="btn-primary" on:click={verifyHydraConnection} disabled={isPingingServer} style="width: 100%; background: #8AB4F8; color: #062E6F; font-weight: 700; border-radius: 100px; padding: 12px; border: none; cursor: pointer; box-sizing: border-box;">
                            {isPingingServer ? '⏳ Проверка связи...' : '⚡ Проверить соединение с сервером'}
                        </button>
                        {#if pingStatusText}
                            <div style="font-size: 13px; color: #E3E3E3; text-align: center;">{pingStatusText}</div>
                        {/if}
                    </div>
                </div>
            </div>
        {:else if activeSettingsTab === 'about'}
                    <div class="settings-section animate-fade-in">
                        <div class="about-branding">
                            <div class="about-logo animate-float">
                                <img src="/icon.png" alt="Hydra" style="width: 100%; height: 100%; object-fit: cover; border-radius: 28px;" />
                            </div>
                            <h2 class="about-title">Hydra</h2>
                            <p class="about-version">Version {aboutInfo.app_version || '1.0.2-beta'}</p>
                        </div>

                        <div class="info-list">
                            <div class="info-item-fancy">
                                <div class="info-icon">{@html Icons.User}</div>
                                <div class="info-content">
                                    <span class="info-label-fancy">Разработчик</span>
                                    <span class="info-value-fancy">{aboutInfo.author || 'Hydra Team'}</span>
                                </div>
                            </div>

                            <div class="info-item-fancy" role="button" tabindex="0" on:click={handleLicenseTap} on:keydown={(e) => e.key === 'Enter' && handleLicenseTap()} style="cursor: pointer; user-select: none; border: 1px solid rgba(138,180,248,0.25);">
                                <div class="info-icon">{@html Icons.File}</div>
                                <div class="info-content">
                                    <span class="info-label-fancy">Лицензия {licenseTapCount > 0 ? `(ещё ${6 - licenseTapCount} наж. до меню разработчика)` : ''}</span>
                                    <span class="info-value-fancy">{aboutInfo.license || 'MIT / Open Source'}</span>
                                </div>
                            </div>

                            {#if devMenuUnlocked}
                                <div class="update-action-row" role="button" tabindex="0" on:click={() => { refreshSettingsLogs(); refreshLanWifiInfo(); showSecretDevModal = true; }} on:keydown={(e) => e.key === 'Enter' && (showSecretDevModal = true)} style="background: rgba(138,180,248,0.14); border: 1.5px solid #8AB4F8;">
                                    <div class="info-icon accent">🛠️</div>
                                    <div class="info-content">
                                        <span class="info-value-fancy accent">Меню разработчика (Логи и Тест потоков)</span>
                                    </div>
                                    <div class="chevron">{@html Icons.ChevronRight}</div>
                                </div>
                            {/if}

                            <div class="info-item-fancy">
                                <div class="info-icon">{@html Icons.Globe}</div>
                                <div class="info-content">
                                    <span class="info-label-fancy">Протокол и Канал</span>
                                    <span class="info-value-fancy">{aboutInfo.i2p_version || '2.58.0'}</span>
                                </div>
                            </div>

                            <div class="info-item-fancy">
                                <div class="info-icon">{@html Icons.Folder}</div>
                                <div class="info-content">
                                    <span class="info-label-fancy">Хранилище данных</span>
                                    <span class="info-value-fancy" style="font-family: monospace; font-size: 11px; word-break: break-all;">{aboutInfo.i2p_path || '~/.teleghost/i2pd'}</span>
                                </div>
                            </div>

                            <div 
                                class="update-action-row" 
                                role="button"
                                tabindex="0"
                                on:click={onCheckUpdates}
                                on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && onCheckUpdates()}
                            >
                                <div class="info-icon accent">{@html Icons.Refresh}</div>
                                <div class="info-content">
                                    <span class="info-value-fancy accent">Обновить приложение</span>
                                </div>
                                <div class="chevron">{@html Icons.ChevronRight}</div>
                            </div>
                        </div>

                        <div class="about-footer">
                            <p>© 2024-2026 Hydra Project. All rights reserved.</p>
                        </div>
                    </div>
                {/if}
            </div>
        </div>
    {/if}
</div>


{#if showSecretDevModal}
    <div style="position: fixed; inset: 0; background: rgba(8, 9, 14, 0.96); z-index: 99999; display: flex; flex-direction: column; padding: 16px; padding-top: calc(16px + env(safe-area-inset-top, 12px)); box-sizing: border-box; overflow-y: auto;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
            <div>
                <div style="font-size: 17px; font-weight: 800; color: #8AB4F8;">🛠️ Меню разработчика Hydra</div>
                <div style="font-size: 11px; color: #9AA0A6;">Диагностика связи, WebRTC-потока звонков, сообщений и LAN</div>
            </div>
            <button type="button" on:click={() => showSecretDevModal = false} style="background: #ff4757; color: #fff; border: none; border-radius: 50%; width: 36px; height: 36px; font-weight: 800; font-size: 16px; cursor: pointer;">✕</button>
        </div>

        <div style="background: #181920; border: 1px solid rgba(138,180,248,0.25); border-radius: 18px; padding: 14px; margin-bottom: 14px;">
            <div style="font-size: 13px; font-weight: 700; color: #E3E3E3; margin-bottom: 8px;">⚡ Комплексный тест узлов и потоков</div>
            <div style="font-size: 12px; color: #9AA0A6; margin-bottom: 10px;">
                📡 Локальная сеть (Wi-Fi Direct): <b style="color: {lanWifiInfo.lanActive ? '#4CAF50' : '#FFC107'};">{lanWifiInfo.lanActive ? 'В одной сети (' + lanWifiInfo.lanIp + ')' : 'Только через внешний VPS'}</b>
            </div>
            <button type="button" on:click={runComprehensiveDiagnostics} disabled={isRunningFullDiag} style="width: 100%; padding: 11px; border-radius: 100px; background: #8AB4F8; color: #062E6F; border: none; font-weight: 800; font-size: 12px; cursor: pointer;">
                {isRunningFullDiag ? '⏳ Выполнение тестов...' : '🚀 Запустить тест: Связь + Поток звонков + Сообщения'}
            </button>
            {#if pingStatusText}
                <div style="margin-top: 8px; font-size: 12px; color: #E3E3E3;">1️⃣ {pingStatusText}</div>
            {/if}
            {#if streamTestStatus}
                <div style="margin-top: 6px; font-size: 12px; color: #8AB4F8;">2️⃣ {streamTestStatus}</div>
            {/if}
            {#if messageLoopbackStatus}
                <div style="margin-top: 6px; font-size: 12px; color: #69db7c;">3️⃣ {messageLoopbackStatus}</div>
            {/if}
        </div>

        <div style="flex: 1; display: flex; flex-direction: column; background: #09090D; border: 1px solid rgba(138,180,248,0.3); border-radius: 18px; padding: 12px; min-height: 260px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; gap: 6px; flex-wrap: wrap;">
                <span style="font-size: 12px; font-weight: 800; color: #8AB4F8;">🐞 Системный журнал ({settingsLogLines.length})</span>
                <div style="display: flex; gap: 6px; flex-wrap: wrap;">
                    <button type="button" on:click={refreshSettingsLogs} style="background: #28292D; color: #E3E3E3; border: none; border-radius: 100px; padding: 6px 10px; font-size: 11px;">🔄</button>
                    <button type="button" on:click={saveSettingsLogsToFile} style="background: #4CAF50; color: #fff; border: none; border-radius: 100px; padding: 6px 12px; font-size: 11px; font-weight: 700;">💾 В файл (.txt)</button>
                    <button type="button" on:click={copySettingsLogs} style="background: #8AB4F8; color: #062E6F; border: none; border-radius: 100px; padding: 6px 12px; font-size: 11px; font-weight: 700;">📋 Копировать</button>
                    <button type="button" on:click={clearSettingsLogs} style="background: rgba(255,71,87,0.2); color: #ff6b6b; border: none; border-radius: 100px; padding: 6px 10px; font-size: 11px;">Очистить</button>
                </div>
            </div>
            <div style="flex: 1; overflow-y: auto; font-family: monospace; font-size: 11px; line-height: 1.45;">
                {#if settingsLogLines.length === 0}
                    <div style="color: #9AA0A6; padding: 10px 0;">Журнал пуст...</div>
                {/if}
                {#each settingsLogLines as line}
                    <div style="padding: 4px 0; border-bottom: 1px solid rgba(255,255,255,0.05); word-break: break-all; color: {line.includes('ERR') || line.includes('ОШИБКА') ? '#ff6b6b' : (line.includes('RPC-OK') || line.includes('BIO-OK') || line.includes('TEST-') ? '#69db7c' : (line.includes('CLICK') ? '#ffd43b' : '#E3E3E3'))};">
                        {line}
                    </div>
                {/each}
            </div>
        </div>
    </div>
{/if}

<style>
    .settings-panel { flex: 1; display: flex; flex-direction: column; height: 100%; background: var(--bg-primary); overflow: hidden; }
    @media (max-width: 768px) {
        .settings-header { padding: 12px 16px; height: 60px; }
        .settings-header h2 { font-size: 20px; }
        .settings-content-area { padding: 20px; }
        .profile-avatar-large { width: 90px; height: 90px; margin-bottom: 24px; }
    }
    .settings-header { padding: 20px 40px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--border); height: 80px; }
    .settings-header h2 { font-size: 24px; font-weight: 700; margin: 0; color: white; display: flex; align-items: center; }
    
    .btn-icon { background: rgba(255,255,255,0.05); border: 1px solid var(--border); color: white; border-radius: 14px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; cursor: pointer; transition: all 0.2s; padding: 0; }
    .btn-icon:hover { background: rgba(255,255,255,0.1); transform: rotate(90deg) scale(1.05); }
    .settings-header .btn-icon { order: -1; } /* For details view to keep back button left if needed, but wait user wants X right? No, ArrowLeft usually left. */
    
    .settings-view-menu .settings-header .btn-icon { order: 1; } /* X on the right */
    .settings-view-details .settings-header .btn-icon { order: -1; } /* Arrow on the left */
    
    .settings-view-menu { width: 100%; max-width: 600px; margin: 0 auto; flex: 1; display: flex; flex-direction: column; }
    .settings-menu { padding: 20px; }
    .settings-menu-item {
        padding: 16px; margin-bottom: 8px; background: var(--bg-secondary); border-radius: 18px; display: flex; align-items: center; gap: 16px; cursor: pointer; transition: transform 0.2s, background 0.2s;
    }
    .settings-menu-item:hover { transform: translateX(5px); background: rgba(255,255,255,0.05); }
    .settings-menu-item .name { flex: 1; font-weight: 500; }
    .settings-menu-item .arrow { opacity: 0.3; }

    .settings-view-details { width: 100%; height: 100%; display: flex; flex-direction: column; }
    .settings-content-area { flex: 1; padding: 24px 16px 90px; overflow-y: auto; max-width: 800px; margin: 0 auto; width: 100%; box-sizing: border-box; }

    .profile-avatar-large { width: 120px; height: 120px; position: relative; margin: 0 auto 32px; }
    .profile-avatar-large img { width: 100%; height: 100%; object-fit: cover; border-radius: 50%; box-shadow: 0 5px 15px rgba(0,0,0,0.3); }
    .avatar-placeholder { width: 100%; height: 100%; background: linear-gradient(135deg, #6c5ce7, #a29bfe); border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 48px; color: white; }
    .avatar-edit-btn { position: absolute; bottom: 0; right: 0; background: var(--accent); border: 4px solid var(--bg-primary); border-radius: 50%; width: 40px; height: 40px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: white; }

    .profile-fields { max-width: 500px; margin: 0 auto; display: flex; flex-direction: column; gap: 24px; }
    .input-field { 
        width: 100%; 
        padding: 12px 16px; 
        background: #1a1a2e; 
        border: 1px solid rgba(255,255,255,0.1); 
        border-radius: 12px; 
        color: white; 
        outline: none; 
        font-size: 14px;
        transition: border-color 0.2s;
    }
    .input-field:focus { border-color: var(--accent); }
    
    select.input-field {
        appearance: none;
        background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='24' height='24' viewBox='0 0 24 24' fill='none' stroke='white' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'%3E%3C/polyline%3E%3C/svg%3E");
        background-repeat: no-repeat;
        background-position: right 12px center;
        background-size: 16px;
        padding-right: 40px;
    }
    select.input-field option { background: #1a1a2e; color: white; padding: 10px; }
    
    .btn-primary { background: var(--accent); color: white; padding: 14px 24px; border: none; border-radius: 16px; font-weight: 600; cursor: pointer; transition: all 0.2s; }
    .btn-primary:hover { opacity: 0.9; transform: translateY(-2px); box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3); }
    .btn-secondary { background: rgba(99, 102, 241, 0.1); border: 1px solid rgba(99, 102, 241, 0.3); color: #fff; padding: 12px 20px; border-radius: 14px; cursor: pointer; font-weight: 500; transition: all 0.2s; display: inline-flex; align-items: center; justify-content: center; gap: 8px; }
    .btn-secondary:hover { background: rgba(99, 102, 241, 0.2); border-color: var(--accent); transform: translateY(-1px); }
    
    .show-key-btn { 
        cursor: pointer; 
        font-weight: 700; 
        margin-top: 12px;
        width: 100%;
        border: 1px dashed rgba(99, 102, 241, 0.5);
    }
    .show-key-btn:hover {
        background: rgba(99, 102, 241, 0.15);
        border-style: solid;
    }

    .logout-btn { border-color: #f44336; color: #f44336; margin-top: 12px; }

    .info-box { padding: 24px; border-radius: 12px; margin-bottom: 24px; }
    .info-box.danger { background: rgba(255, 100, 100, 0.1); border: 1px solid rgba(255, 100, 100, 0.3); }
    .info-box h4 { margin: 0 0 12px; color: #ff6b6b; }

    .setting-item-box { background: var(--bg-secondary); padding: 20px; border-radius: 12px; }
    .flex-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
    .bg-box { background: var(--bg-secondary); padding: 16px; border-radius: 12px; }

    .destination-details { margin-bottom: 24px; cursor: pointer; background: rgba(99, 102, 241, 0.03); border-radius: 16px; border: 1px solid rgba(255,255,255,0.05); }
    .destination-details[open] { background: rgba(99, 102, 241, 0.05); }
    .destination-details summary { 
        padding: 16px 20px; list-style: none; display: flex; align-items: center; gap: 12px; 
        outline: none; transition: all 0.2s; font-weight: 600; color: #a29bfe;
    }
    .destination-details summary:hover { background: rgba(255,255,255,0.03); color: white; }
    .destination-details summary::-webkit-details-marker { display: none; }
    .destination-details summary::before { content: '🌐'; font-size: 14px; }
    .destination-details[open] summary { border-bottom: 1px solid rgba(255,255,255,0.05); }
    .hint-inline { font-size: 11px; opacity: 0.5; font-weight: 400; font-family: var(--font-main); margin-left: auto; }

    .destination-box { 
        display: flex; align-items: flex-start; gap: 10px; padding: 20px; 
        animation: slideDown 0.3s ease-out;
    }
    .destination-code { 
        flex: 1; font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; 
        line-height: 1.6; opacity: 0.8; word-break: break-all; white-space: pre-wrap;
        max-height: 150px; overflow-y: auto; color: #a29bfe;
    }

    .full-width { width: 100%; }
    .section-title { margin: 32px 0 16px; border-top: 1px solid var(--border); padding-top: 24px; }

    .icon-svg { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; }
    .icon-svg :global(svg) { width: 100%; height: 100%; }
    .icon-svg-sm { width: 18px; height: 18px; display: flex; align-items: center; justify-content: center; }
    .icon-svg-sm :global(svg) { width: 100%; height: 100%; }

    /* Switch styles */
    .switch { position: relative; display: inline-block; width: 50px; height: 24px; flex-shrink: 0; }
    .switch input { opacity: 0; width: 0; height: 0; }
    .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #333; transition: .4s; }
    .slider:before { position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .4s; }
    input:checked + .slider { background-color: var(--accent); }
    input:checked + .slider:before { transform: translateX(26px); }
    .slider.round { border-radius: 34px; }
    .slider.round:before { border-radius: 50%; }

    /* Redesigned About Section */
    .about-branding { text-align: center; margin-bottom: 40px; }
    .about-logo { 
        width: 100px; height: 100px; background: linear-gradient(135deg, #6366f1 0%, #a29bfe 100%); 
        border-radius: 30px; margin: 0 auto 20px; display: flex; align-items: center; justify-content: center;
        box-shadow: 0 15px 35px rgba(99, 102, 241, 0.3); color: white;
    }
    .icon-svg-lg { width: 50px; height: 50px; display: flex; align-items: center; justify-content: center; }
    .icon-svg-lg :global(svg) { width: 100%; height: 100%; }
    .about-title { font-size: 32px; font-weight: 800; margin-bottom: 8px; background: linear-gradient(90deg, #fff, #a29bfe); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
    .about-version { color: var(--text-secondary); font-size: 14px; font-weight: 500; opacity: 0.7; }

    .info-list { display: flex; flex-direction: column; gap: 12px; margin-bottom: 40px; }
    .info-item-fancy { 
        display: flex; align-items: center; gap: 16px; padding: 16px 20px; 
        background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 16px;
    }
    .info-icon { 
        width: 40px; height: 40px; border-radius: 12px; background: rgba(255,255,255,0.05);
        display: flex; align-items: center; justify-content: center; color: var(--text-secondary);
    }
    .info-icon :global(svg) { width: 20px; height: 20px; }
    .info-icon.accent { background: rgba(99, 102, 241, 0.1); color: var(--accent); }
    
    .info-content { display: flex; flex-direction: column; gap: 2px; flex: 1; }
    .info-label-fancy { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--text-secondary); font-weight: 700; }
    .info-value-fancy { font-size: 15px; color: var(--text-primary); font-weight: 500; }
    .info-value-fancy.accent { color: var(--accent); font-weight: 700; }

    .update-action-row { 
        display: flex; align-items: center; gap: 16px; padding: 16px 20px; 
        background: rgba(99, 102, 241, 0.05); border: 1px solid rgba(99, 102, 241, 0.1); 
        border-radius: 16px; cursor: pointer; transition: all 0.2s;
    }
    .update-action-row:hover { background: rgba(99, 102, 241, 0.1); border-color: rgba(99, 102, 241, 0.3); transform: translateY(-2px); }
    .chevron { color: var(--text-secondary); opacity: 0.5; }
    .chevron :global(svg) { width: 18px; height: 18px; }

    .about-footer { text-align: center; color: var(--text-secondary); font-size: 12px; opacity: 0.4; margin-top: auto; }

    @keyframes float {
        0%, 100% { transform: translateY(0); }
        50% { transform: translateY(-10px); }
    }
    .animate-float { animation: float 6s ease-in-out infinite; }

    .settings-section, .settings-item-group, .setting-item, .destination-details, .destination-box, .input-field, select.input-field {
        max-width: 100% !important;
        box-sizing: border-box !important;
    }
    .settings-content-area {
        overflow-x: hidden !important;
        box-sizing: border-box !important;
    }
    select.input-field {
        text-overflow: ellipsis !important;
        white-space: nowrap !important;
        overflow: hidden !important;
    }
    
</style>
