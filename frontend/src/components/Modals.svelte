<script>
    import jsQR from 'jsqr';
    import { Icons } from '../Icons.js';
    import { getInitials, getAvatarGradient } from '../utils.js';
    import * as AppActions from '../../wailsjs/go/main/App.js';

    // Confirm Modal
    export let showConfirmModal = false;
    export let confirmModalTitle = '';
    export let confirmModalText = '';
    export let onConfirm;
    export let onCancelConfirm;

    // Folder Modal
    export let showFolderModal = false;
    export let isEditingFolder = false;
    export let folderName = '';
    export let folderIcon = '📁';
    export let onSaveFolder;
    export let onCancelFolder;
    export let onDeleteFolder;

    // Contact Profile Modal
    export let showContactProfile = false;
    export let contact = null;
    export let onCloseContactProfile;
    export let onUpdateProfile = null;
    
    // Add/Search Contact Modal
    export let showAddContact = false;
    export let onAddContact;
    export let onCancelAddContact;
    export let addContactName = '';
    export let addContactAddress = '';

    const handleAddContact = async () => {
        const rawAddr = (addContactAddress || '').trim();
        if (rawAddr.startsWith('@') || (rawAddr.length >= 3 && rawAddr.length <= 24 && !rawAddr.includes('/'))) {
            const cleanNick = rawAddr.replace(/^@/, '').toLowerCase();
            if (!addContactName.trim()) addContactName = '@' + cleanNick;
            try {
                const r = await fetch('http://191.223.137.164:8085/nick/resolve?nick=' + encodeURIComponent(cleanNick));
                if (r.ok) {
                    const d = await r.json();
                    if (d && d.pub_key) addContactAddress = d.pub_key;
                }
            } catch (e) {}
        } else if (!addContactName.trim() || addContactName.trim().length > 22) {
            addContactName = 'Контакт ' + rawAddr.slice(0, 6);
        }
        onAddContact();
    };

    // Show Seed Modal
    export let showSeedModal = false;
    export let mnemonic = '';
    export let onCloseSeed;

    // Change PIN Modal
    export let showChangePinModal = false;
    export let onSavePin;
    export let onCancelChangePin;
    let newPin = '';
    let confirmPin = '';
    let pinError = '';

    // I2P address toggle
    let showFullAddress = false;

    // Emoji picker
    const emojiList = [
        '📁', '💼', '👥', '👨‍👩‍👧‍👦', '🏠', '💬', '🎮', '🎵',
        '📚', '💻', '🔒', '⭐', '❤️', '🔥', '🎯', '🌐',
        '🛒', '📸', '🎬', '🏢', '✈️', '🎓', '🤝', '💡',
        '🔔', '📌', '🏆', '🌟', '🎨', '🔧', '📈', '🛡️'
    ];
    let showEmojiPicker = false;
    let createEntityType = 'folder';

    function selectEmoji(emoji) {
        folderIcon = emoji;
        showEmojiPicker = false;
    }

    // Close I2P when contact profile closes
    $: if (!showContactProfile) showFullAddress = false;

    let isLiveScanningQR = false;
    let qrVideoStream = null;
    let qrScanTimer = null;
    let qrVideoEl = null;
    let qrStatusMsg = '';

    async function startLiveQRScanner() {
        if (isLiveScanningQR) {
            stopLiveQRScanner();
            return;
        }
        qrStatusMsg = '⏳ Запуск камеры для сканирования QR...';
        if (window.__addHydraLog) window.__addHydraLog('QR-CAM', 'Открытие прямой камеры для сканирования QR-кода...');
        try {
            if (window.HydraNative?.requestMissingPermissions) {
                window.HydraNative.requestMissingPermissions();
            }
            qrVideoStream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: { ideal: 'environment' }, width: { ideal: 640 }, height: { ideal: 640 } },
                audio: false
            });
            isLiveScanningQR = true;
            qrStatusMsg = '🎯 Наведите камеру на QR-код друга...';
            setTimeout(() => {
                if (qrVideoEl && qrVideoStream) {
                    qrVideoEl.srcObject = qrVideoStream;
                    qrVideoEl.play().catch(() => {});
                }
            }, 100);

            const canvas = document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            qrScanTimer = setInterval(() => {
                if (!qrVideoEl || qrVideoEl.readyState !== qrVideoEl.HAVE_ENOUGH_DATA) return;
                canvas.width = qrVideoEl.videoWidth || 480;
                canvas.height = qrVideoEl.videoHeight || 480;
                ctx.drawImage(qrVideoEl, 0, 0, canvas.width, canvas.height);
                const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                const code = jsQR(imageData.data, imageData.width, imageData.height);
                if (code && code.data) {
                    addContactAddress = code.data.trim();
                    if (!addContactName.trim()) addContactName = 'Контакт ' + addContactAddress.slice(0, 6);
                    qrStatusMsg = '✅ QR-код успешно считан камерой!';
                    if (window.__addHydraLog) window.__addHydraLog('QR-CAM-OK', 'Считан QR с живой камеры: ' + addContactAddress);
                    stopLiveQRScanner();
                }
            }, 220);
        } catch (err) {
            qrStatusMsg = '❌ Не удалось открыть камеру: ' + err;
            if (window.__addHydraLog) window.__addHydraLog('QR-CAM-ERR', String(err));
        }
    }

    function stopLiveQRScanner() {
        if (qrScanTimer) {
            clearInterval(qrScanTimer);
            qrScanTimer = null;
        }
        if (qrVideoStream) {
            qrVideoStream.getTracks().forEach(t => t.stop());
            qrVideoStream = null;
        }
        isLiveScanningQR = false;
    }

    $: if (!showAddContact && isLiveScanningQR) {
        stopLiveQRScanner();
    }

    async function handleScanQRFile(e) {
        const file = e.target.files?.[0];
        if (!file) return;
        if (window.__addHydraLog) window.__addHydraLog('QR-FILE', 'Выбран файл QR из галереи: ' + file.name);
        try {
            const img = new Image();
            const url = URL.createObjectURL(file);
            await new Promise((resolve, reject) => {
                img.onload = resolve;
                img.onerror = reject;
                img.src = url;
            });
            const canvas = document.createElement('canvas');
            const maxDim = 1024;
            let w = img.width, h = img.height;
            if (w > maxDim || h > maxDim) {
                if (w > h) { h = Math.round((h * maxDim) / w); w = maxDim; }
                else { w = Math.round((w * maxDim) / h); h = maxDim; }
            }
            canvas.width = w;
            canvas.height = h;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(img, 0, 0, w, h);
            const imageData = ctx.getImageData(0, 0, w, h);
            URL.revokeObjectURL(url);
            const code = jsQR(imageData.data, imageData.width, imageData.height);
            if (code && code.data) {
                addContactAddress = code.data.trim();
                if (!addContactName.trim()) addContactName = 'Контакт ' + addContactAddress.slice(0, 6);
                qrStatusMsg = '✅ QR-код распознан из картинки!';
                if (window.__addHydraLog) window.__addHydraLog('QR-FILE-OK', 'Распознан адрес: ' + addContactAddress);
                return;
            }
            qrStatusMsg = '⚠️ QR-код не найден на картинке';
        } catch (err) {
            qrStatusMsg = '❌ Ошибка чтения файла: ' + err;
        }
    }

    function formatHydraKey(raw) {
        if (!raw) return '';
        const clean = String(raw).trim();
        if (clean.startsWith('HYDRA-KEY-')) return clean;
        try {
            return 'HYDRA-KEY-' + btoa(unescape(encodeURIComponent(clean))).replace(/=/g, '');
        } catch (e) {
            return 'HYDRA-KEY-' + clean;
        }
    }
    
</script>

<!-- Custom Confirm Modal -->
{#if showConfirmModal}
<div 
    class="modal-backdrop animate-fade-in" 
    role="button"
    tabindex="0"
    on:click|self={onCancelConfirm}
    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget && onCancelConfirm()}
>
    <div class="modal-content animate-slide-down" style="max-width: 400px;">
        <div class="modal-header">
            <h3>{confirmModalTitle}</h3>
            <button class="btn-icon" on:click={onCancelConfirm}><div class="icon-svg">{@html Icons.X}</div></button>
        </div>
        <div class="modal-body">
            <p style="color: var(--text-secondary); margin-bottom: 20px;">{confirmModalText}</p>
        </div>
        <div class="modal-footer">
            <button class="btn-small btn-glass" on:click={onCancelConfirm}>Отмена</button>
            <button class="btn-small btn-primary" on:click={onConfirm}>OK</button>
        </div>
    </div>
</div>
{/if}

<!-- Add/Edit Folder Modal -->
{#if showFolderModal}
<div 
    class="modal-backdrop animate-fade-in" 
    role="button"
    tabindex="0"
    on:click|self={() => { showEmojiPicker = false; if (onCancelFolder) onCancelFolder(); }}
    on:keydown={(e) => e.key === 'Escape' && onCancelFolder && onCancelFolder()}
>
    <div class="modal-content animate-slide-down" style="max-width: 400px; width: 92%; box-sizing: border-box;">
        <div class="modal-header">
            <h3>{isEditingFolder ? 'Редактировать' : (createEntityType === 'group' ? '👥 Новая группа E2EE' : (createEntityType === 'channel' ? '📢 Новый канал' : '📁 Новая папка'))}</h3>
            <button type="button" class="btn-icon" on:click={() => { showEmojiPicker = false; if (onCancelFolder) onCancelFolder(); }}><div class="icon-svg">{@html Icons.X}</div></button>
        </div>
        <div class="modal-body">
            {#if !isEditingFolder}
                <div style="display: flex; gap: 6px; margin-bottom: 14px; background: #121212; padding: 4px; border-radius: 100px; border: 1px solid rgba(138,180,248,0.2);">
                    <button type="button" on:click={() => { createEntityType = 'folder'; folderIcon = '📁'; }} style="flex: 1; padding: 8px 4px; border-radius: 100px; border: none; font-size: 12px; font-weight: 700; cursor: pointer; background: {createEntityType === 'folder' ? '#8AB4F8' : 'transparent'}; color: {createEntityType === 'folder' ? '#062E6F' : '#9AA0A6'};">📁 Папка</button>
                    <button type="button" on:click={() => { createEntityType = 'group'; folderIcon = '👥'; }} style="flex: 1; padding: 8px 4px; border-radius: 100px; border: none; font-size: 12px; font-weight: 700; cursor: pointer; background: {createEntityType === 'group' ? '#8AB4F8' : 'transparent'}; color: {createEntityType === 'group' ? '#062E6F' : '#9AA0A6'};">👥 Группа</button>
                    <button type="button" on:click={() => { createEntityType = 'channel'; folderIcon = '📢'; }} style="flex: 1; padding: 8px 4px; border-radius: 100px; border: none; font-size: 12px; font-weight: 700; cursor: pointer; background: {createEntityType === 'channel' ? '#8AB4F8' : 'transparent'}; color: {createEntityType === 'channel' ? '#062E6F' : '#9AA0A6'};">📢 Канал</button>
                </div>
            {/if}
            <label class="form-label">
                {createEntityType === 'group' ? 'Название группы' : (createEntityType === 'channel' ? 'Название канала' : 'Название папки')}
                <input type="text" bind:value={folderName} class="input-field" style="box-sizing: border-box;" placeholder={createEntityType === 'group' ? 'Напр: Секретный чат' : (createEntityType === 'channel' ? 'Напр: Новости Hydra' : 'Напр: Работа, Семья')} />
            </label>
            <div class="emoji-section" style="margin-top: 14px;">
                <div class="form-label">Иконка</div>
                <div class="emoji-current" on:click={() => showEmojiPicker = !showEmojiPicker} role="button" tabindex="0" on:keydown={(e) => e.key === 'Enter' && (showEmojiPicker = !showEmojiPicker)}>
                    <span class="emoji-display">{folderIcon}</span>
                    <span class="emoji-hint">{showEmojiPicker ? 'Скрыть иконки' : 'Выбрать иконку'}</span>
                </div>
                {#if showEmojiPicker}
                    <div class="emoji-grid">
                        {#each emojiList as emoji}
                            <button type="button" class="emoji-btn" class:selected={folderIcon === emoji} on:click={() => selectEmoji(emoji)}>{emoji}</button>
                        {/each}
                    </div>
                {/if}
            </div>
        </div>
        <div class="modal-footer">
            {#if isEditingFolder}
                <button type="button" class="btn-small btn-danger" on:click={onDeleteFolder} style="margin-right: auto;">Удалить</button>
            {/if}
            <button type="button" class="btn-small btn-glass" on:click={() => { showEmojiPicker = false; if (onCancelFolder) onCancelFolder(); }}>Отмена</button>
            <button type="button" class="btn-small btn-primary" on:click={() => {
                if (!isEditingFolder && createEntityType === 'group' && folderName && !folderName.startsWith('👥 ')) {
                    folderName = '👥 ' + folderName.trim();
                } else if (!isEditingFolder && createEntityType === 'channel' && folderName && !folderName.startsWith('📢 ')) {
                    folderName = '📢 ' + folderName.trim();
                }
                showEmojiPicker = false;
                if (onSaveFolder) onSaveFolder();
            }}>Создать</button>
        </div>
    </div>
</div>
{/if}

<!-- Contact Profile Modal -->
{#if showContactProfile && contact}
<div 
    class="modal-backdrop animate-fade-in" 
    role="button"
    tabindex="0"
    on:click|self={onCloseContactProfile}
    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget && onCloseContactProfile()}
>
    <div class="modal-content animate-slide-down">
        <div class="modal-header">
            <h3>Профиль контакта</h3>
            <button class="btn-icon" on:click={onCloseContactProfile}><div class="icon-svg">{@html Icons.X}</div></button>
        </div>
        <div class="modal-body" style="text-align: center; padding: 20px 0;">
            <div class="profile-avatar-large" style="width: 100px; height: 100px; margin: 0 auto 20px; background: {getAvatarGradient(contact.Nickname)}; border-radius: 50%; overflow: hidden; display: flex; align-items: center; justify-content: center; font-size: 40px; color: white;">
                {#if contact.Avatar}<img src={contact.Avatar} alt="av" style="width:100%;height:100%;object-fit:cover;"/>{:else}{getInitials(contact.Nickname)}{/if}
            </div>
            <h2 style="margin-bottom: 4px;">{contact.Nickname}</h2>
            <p style="color: var(--text-secondary); font-size: 14px; margin-bottom: 24px;">{contact.IsOnline ? 'В сети' : 'Оффлайн'}</p>
            
            <!-- I2P Address - collapsed by default -->
            <div class="i2p-address-section">
                <div class="i2p-toggle" on:click={() => showFullAddress = !showFullAddress} role="button" tabindex="0" on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && (showFullAddress = !showFullAddress)}>
                    <span style="font-size: 12px; color: var(--text-secondary);">ID ключ Hydra</span>
                    <span style="font-size: 12px; color: var(--text-secondary);">
                        {#if !showFullAddress}
                            {contact.I2PAddress ? contact.I2PAddress.slice(0, 20) + '...' : 'Нет адреса'}
                        {/if}
                        <span class="toggle-arrow" class:open={showFullAddress}>{@html Icons.ChevronRight || '▸'}</span>
                    </span>
                </div>
                {#if showFullAddress}
                    <div class="i2p-address-full" style="margin-top: 8px; text-align: left; background: var(--bg-input); padding: 12px; border-radius: 12px;">
                        <code style="font-size: 11px; word-break: break-all; opacity: 0.8; line-height: 1.4;">{contact.I2PAddress}</code>
                    </div>
                {/if}
            </div>
        </div>
        <div class="modal-footer" style="flex-direction: column; gap: 8px;">
            <button class="btn-primary full-width clickable-btn" on:click={() => { AppActions.ClipboardSet(contact.I2PAddress); }}>Скопировать адрес</button>
            {#if onUpdateProfile}
                <button class="btn-glass full-width clickable-btn" on:click={() => onUpdateProfile(contact.I2PAddress)}>🔄 Обновить профиль</button>
            {/if}
        </div>
    </div>
</div>
{/if}

<!-- Add Contact Modal -->
{#if showAddContact}
<div class="modal-backdrop animate-fade-in" role="button" tabindex="0" on:click|self={() => { stopLiveQRScanner(); onCancelAddContact(); }} on:keydown={(e) => e.key === 'Escape' && onCancelAddContact()}>
    <div class="modal-content animate-slide-down" style="max-width: 440px;">
        <div class="modal-header">
            <h3>Добавить контакт</h3>
            <button class="btn-icon" on:click={() => { stopLiveQRScanner(); onCancelAddContact(); }}><div class="icon-svg">{@html Icons.X}</div></button>
        </div>
        <div class="modal-body">
            <div class="form-group">
                <label class="form-label">Имя или @никнейм друга
                    <input type="text" bind:value={addContactName} class="input-field" placeholder="Напр: @fity или Андрей" />
                </label>
            </div>
            <div class="form-group" style="margin-top: 14px;">
                <label class="form-label">ID ключ Hydra или @никнейм
                    <textarea bind:value={addContactAddress} class="input-field" rows="2" placeholder="Вставьте @никнейм или отсканируйте QR-код..."></textarea>
                </label>
            </div>
            <div style="margin-top: 12px; display: flex; flex-direction: column; gap: 8px;">
                <div style="display: flex; gap: 8px;">
                    <button type="button" on:click={startLiveQRScanner} style="flex: 1; padding: 12px; border-radius: 100px; background: {isLiveScanningQR ? '#ff4757' : '#8AB4F8'}; color: {isLiveScanningQR ? '#fff' : '#062E6F'}; border: none; font-weight: 800; font-size: 12px; cursor: pointer;">
                        {isLiveScanningQR ? '⏹ Выключить камеру' : '📷 Сканировать QR камерой'}
                    </button>
                    <label style="padding: 12px 14px; border-radius: 100px; background: #28292D; color: #8AB4F8; border: 1px solid rgba(138,180,248,0.35); font-weight: 700; font-size: 12px; cursor: pointer; display: flex; align-items: center;">
                        🖼 Галерея
                        <input type="file" accept="image/*" style="display: none;" on:change={handleScanQRFile} />
                    </label>
                </div>
                {#if qrStatusMsg}
                    <div style="font-size: 12px; color: #8AB4F8; text-align: center; font-weight: 600;">{qrStatusMsg}</div>
                {/if}
                {#if isLiveScanningQR}
                    <div style="position: relative; width: 230px; height: 230px; margin: 6px auto; border-radius: 22px; overflow: hidden; border: 3px solid #8AB4F8; background: #000;">
                        <video bind:this={qrVideoEl} autoplay playsinline muted style="width: 100%; height: 100%; object-fit: cover;"></video>
                        <div style="position: absolute; inset: 30px; border: 2px dashed #8AB4F8; border-radius: 16px; pointer-events: none;"></div>
                    </div>
                {/if}
            </div>
        </div>
        <div class="modal-footer">
            <button class="btn-small btn-glass" on:click={() => { stopLiveQRScanner(); onCancelAddContact(); }}>Отмена</button>
            <button class="btn-small btn-primary clickable-btn" on:click={() => { stopLiveQRScanner(); handleAddContact(); }}>Добавить</button>
        </div>
    </div>
</div>
{/if}
<!-- Show Seed Modal -->
{#if showSeedModal}
<div 
    class="modal-backdrop animate-fade-in" 
    role="button"
    tabindex="0"
    on:click|self={onCloseSeed}
    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget && onCloseSeed()}
>
    <div class="modal-content animate-slide-down" style="max-width: 450px; background: #1a1a2e; border: 1px solid rgba(255, 100, 100, 0.2);">
        <div class="modal-header">
            <h3 style="color: #ff6b6b;">🔐 Ваш Мастер-ключ Hydra</h3>
            <button class="btn-icon" on:click={onCloseSeed}><div class="icon-svg">{@html Icons.X}</div></button>
        </div>
        <div class="modal-body">
            <p class="warning-text" style="color: #ff6b6b; font-size: 13px; margin-bottom: 20px; background: rgba(255,100,100,0.1); padding: 12px; border-radius: 12px;">
                ⚠️ Никогда не передавайте этот Мастер-ключ третьим лицам! Он даёт ПОЛНЫЙ доступ к вашему аккаунту.
            </p>
            
            <div style="background: #121212; border: 1.5px solid #8AB4F8; border-radius: 18px; padding: 16px; margin-bottom: 20px; word-break: break-all; font-family: monospace; font-size: 13px; color: #8AB4F8; font-weight: 700; text-align: center; user-select: all;">
            {formatHydraKey(mnemonic)}
        </div>
        <button class="btn-glass full-width clickable-btn" on:click={() => { AppActions.ClipboardSet(formatHydraKey(mnemonic)); }} style="padding: 12px; border-radius: 16px;">
                📋 Скопировать всё
            </button>
        </div>
        <div class="modal-footer">
            <button class="btn-primary full-width accent-btn clickable-btn" on:click|preventDefault|stopPropagation={onCloseSeed}>Я всё сохранил(а)</button>
        </div>
    </div>
</div>
{/if}

<!-- Change PIN Modal -->
{#if showChangePinModal}
<div 
    class="modal-backdrop animate-fade-in" 
    role="button"
    tabindex="0"
    on:click|self={onCancelChangePin}
    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget && onCancelChangePin()}
>
    <div class="modal-content animate-slide-down" style="max-width: 450px;">
        <div class="modal-header">
            <h3>🔐 Сменить ПИН-код</h3>
            <button class="btn-icon" on:click={onCancelChangePin}><div class="icon-svg">{@html Icons.X}</div></button>
        </div>
        <div class="modal-body">
            <p style="color: var(--text-secondary); font-size: 13px; margin-bottom: 20px;">
                Введите новый ПИН-код для быстрого входа в аккаунт. Минимум 6 символов.
            </p>
            
            <div class="form-group">
                <label class="form-label">Новый ПИН-код
                    <input 
                        type="password" 
                        bind:value={newPin} 
                        class="input-field" 
                        placeholder="Минимум 6 символов"
                        on:input={() => pinError = ''}
                    />
                </label>
            </div>
            
            <div class="form-group" style="margin-top: 16px;">
                <label class="form-label">Подтвердите ПИН-код
                    <input 
                        type="password" 
                        bind:value={confirmPin} 
                        class="input-field" 
                        placeholder="Повторите ПИН-код"
                        on:input={() => pinError = ''}
                        on:keydown={(e) => {
                            if (e.key === 'Enter') {
                                if (newPin.length >= 6 && newPin === confirmPin) {
                                    onSavePin(newPin);
                                    newPin = '';
                                    confirmPin = '';
                                    pinError = '';
                                } else if (newPin.length < 6) {
                                    pinError = 'ПИН-код должен быть минимум 6 символов';
                                } else {
                                    pinError = 'ПИН-коды не совпадают';
                                }
                            }
                        }}
                    />
                </label>
            </div>
            
            {#if pinError}
                <p style="color: #ff6b6b; font-size: 12px; margin-top: 8px; background: rgba(255,100,100,0.1); padding: 8px; border-radius: 8px;">
                    ⚠️ {pinError}
                </p>
            {/if}
        </div>
        <div class="modal-footer">
            <button class="btn-small btn-glass" on:click={onCancelChangePin}>Отмена</button>
            <button 
                class="btn-small btn-primary clickable-btn" 
                on:click={() => {
                    if (newPin.length < 6) {
                        pinError = 'ПИН-код должен быть минимум 6 символов';
                    } else if (newPin !== confirmPin) {
                        pinError = 'ПИН-коды не совпадают';
                    } else {
                        onSavePin(newPin);
                        newPin = '';
                        confirmPin = '';
                        pinError = '';
                    }
                }}
            >
                Сохранить
            </button>
        </div>
    </div>
</div>
{/if}

<style>
    .modal-backdrop { position: fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(0,0,0,0.8); backdrop-filter:blur(10px); display:flex; align-items:center; justify-content:center; z-index: 1000; }
    .modal-content { background: var(--bg-secondary); border-radius: 24px; padding: 24px; width: 90%; max-width: 500px; box-shadow: 0 30px 60px rgba(0,0,0,0.6); border: 1px solid var(--border); overflow: hidden; }
    .modal-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
    .modal-header h3 { margin: 0; font-size: 20px; color: white; font-weight: 700; }
    
    .form-label { display: block; font-size: 14px; color: var(--text-secondary); margin-bottom: 8px; font-weight: 500; }
    .input-field { width: 100%; padding: 14px; background: var(--bg-input); border: 1px solid var(--border); border-radius: 14px; color: white; outline: none; transition: all 0.2s; }
    .input-field:focus { border-color: var(--accent); box-shadow: 0 0 10px rgba(99, 102, 241, 0.2); }
    
    .modal-footer { display: flex; gap: 12px; margin-top: 24px; }
    .btn-small { padding: 12px 24px; border-radius: 16px; font-weight: 600; cursor: pointer; border: none; transition: all 0.2s; font-size: 14px; }
    .btn-small:hover { opacity: 0.9; transform: translateY(-2px); }
    
    .btn-primary { background: var(--accent); color: white; flex: 1; border: none; border-radius: 16px; font-weight: 600; transition: all 0.2s; }
    .accent-btn { background: #6366f1 !important; }
    .btn-glass { background: rgba(255,255,255,0.05); color: #a0a0ba; border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; }
    .btn-glass:hover { background: rgba(255,255,255,0.1); color: white; }
    
    .full-width { width: 100%; padding: 14px; }
    .icon-svg { width: 24px; height: 24px; }
    .btn-icon { border: none; color: white; cursor: pointer; background: rgba(255,255,255,0.05); width: 36px; height: 36px; border-radius: 12px; display: flex; align-items: center; justify-content: center; transition: all 0.2s; padding: 0;}
    .btn-icon:hover { background: rgba(255,255,255,0.1); transform: rotate(90deg); }
    
    .clickable-btn { cursor: pointer !important; position: relative; z-index: 10002; transition: all 0.2s; }
    .clickable-btn:hover { filter: brightness(1.2); transform: translateY(-2px); box-shadow: 0 8px 20px rgba(0,0,0,0.3); }
    .clickable-btn:active { transform: translateY(0); }

    /* I2P Address Section */
    .i2p-address-section { text-align: left; margin-top: 8px; }
    .i2p-toggle { 
        display: flex; align-items: center; justify-content: space-between; 
        padding: 12px 16px; background: var(--bg-input); border-radius: 14px; 
        cursor: pointer; transition: background 0.2s;
    }
    .i2p-toggle:hover { background: rgba(255,255,255,0.08); }
    .toggle-arrow { 
        display: inline-flex; width: 16px; height: 16px; margin-left: 8px;
        transition: transform 0.2s; vertical-align: middle;
    }
    .toggle-arrow :global(svg) { width: 100%; height: 100%; }
    .toggle-arrow.open { transform: rotate(90deg); }

    /* Emoji Picker */
    .emoji-current {
        display: flex; align-items: center; gap: 12px;
        padding: 12px 16px; background: var(--bg-input); border: 1px solid var(--border);
        border-radius: 14px; cursor: pointer; transition: all 0.2s;
    }
    .emoji-current:hover { border-color: var(--accent); }
    .emoji-display { font-size: 28px; line-height: 1; }
    .emoji-hint { font-size: 12px; color: var(--text-secondary); }
    .emoji-grid {
        display: grid; grid-template-columns: repeat(8, 1fr); gap: 4px;
        padding: 12px; margin-top: 8px; background: var(--bg-input);
        border: 1px solid var(--border); border-radius: 14px;
        max-height: 200px; overflow-y: auto;
    }
    .emoji-btn {
        width: 100%; aspect-ratio: 1; font-size: 22px; background: transparent;
        border: 2px solid transparent; border-radius: 10px; cursor: pointer;
        display: flex; align-items: center; justify-content: center;
        transition: all 0.15s;
    }
    .emoji-btn:hover { background: rgba(255,255,255,0.1); transform: scale(1.15); }
    .emoji-btn.selected { border-color: var(--accent); background: rgba(99, 102, 241, 0.15); }
</style>
