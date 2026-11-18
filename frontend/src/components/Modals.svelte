<script>
    import jsQR from 'jsqr';
    import { Icons } from '../Icons.js';
    import { getInitials, getAvatarGradient, getUnifiedPresence } from '../utils.js';
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

    let profileActiveTab = 'media'; // 'media' | 'files' | 'voice'
    let showProfileDotsMenu = false;
    let contactMuted = false;

    let showBlockDurationModal = false;
    let blockAmount = 1;
    let blockUnit = 'days';
    let isContactBlockedNow = false;
    let groupMembersInProfile = [];
    let groupHideMembers = false;

    $: if (contact) {
        contactMuted = localStorage.getItem('hydra_mute_' + contact.ID) === '1';
        const bUntil = Number(localStorage.getItem('hydra_block_until_' + contact.ID) || 0);
        isContactBlockedNow = bUntil > Date.now();
        try {
            groupMembersInProfile = JSON.parse(localStorage.getItem('hydra_group_members_' + contact.ID) || '["Вы (Создатель)"]');
            groupHideMembers = localStorage.getItem('hydra_group_hide_members_' + contact.ID) === '1';
        } catch (e) { groupMembersInProfile = ['Вы (Создатель)']; }
    }

    function toggleContactMute() {
        if (!contact) return;
        contactMuted = !contactMuted;
        localStorage.setItem('hydra_mute_' + contact.ID, contactMuted ? '1' : '0');
    }

    function toggleGroupMembersVisibility() {
        if (!contact) return;
        groupHideMembers = !groupHideMembers;
        localStorage.setItem('hydra_group_hide_members_' + contact.ID, groupHideMembers ? '1' : '0');
    }

    function applyTimedUserBlock() {
        if (!contact) return;
        const multMap = {
            minutes: 60 * 1000,
            hours: 3600 * 1000,
            days: 86400 * 1000,
            months: 30 * 86400 * 1000,
            years: 365 * 86400 * 1000
        };
        const durationMs = Math.max(1, Number(blockAmount || 1)) * (multMap[blockUnit] || 86400000);
        const untilTs = Date.now() + durationMs;
        localStorage.setItem('hydra_block_until_' + contact.ID, String(untilTs));
        isContactBlockedNow = true;
        showBlockDurationModal = false;
    }

    function unblockCurrentUser() {
        if (!contact) return;
        localStorage.removeItem('hydra_block_until_' + contact.ID);
        isContactBlockedNow = false;
        showBlockDurationModal = false;
    }
    
    let noCopyProtected = localStorage.getItem('hydra_flag_secure') === '1';

    function toggleNoCopyProtection() {
        noCopyProtected = !noCopyProtected;
        localStorage.setItem('hydra_flag_secure', noCopyProtected ? '1' : '0');
        if (window.HydraNative && typeof window.HydraNative.setScreenshotProtection === 'function') {
            window.HydraNative.setScreenshotProtection(noCopyProtected);
        }
    }

    async function renameCurrentContact() {
        showProfileDotsMenu = false;
        if (!contact) return;
        const newName = prompt('Введите новое имя для контакта:', contact.Nickname || '');
        if (newName && newName.trim()) {
            await AppActions.AddContact(newName.trim(), contact.I2PAddress);
            contact.Nickname = newName.trim();
        }
    }
    

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
                    qrStatusMsg = '⏳ QR считан! Поиск @никнейма на сервере...';
                    stopLiveQRScanner();
                    fetch('http://191.223.137.164:8085/nick/resolve?pub=' + encodeURIComponent(addContactAddress))
                        .then(r => r.ok ? r.json() : null)
                        .then(d => {
                            if (d && d.nickname) {
                                addContactName = '@' + d.nickname.replace(/^@/, '');
                                qrStatusMsg = '✅ Найдено имя пользователя: ' + addContactName;
                            } else {
                                if (!addContactName.trim() || addContactName.startsWith('Контакт ')) addContactName = 'Пользователь Hydra';
                                qrStatusMsg = '✅ QR-код считан! Введите имя друга выше.';
                            }
                        })
                        .catch(() => {
                            if (!addContactName.trim()) addContactName = 'Пользователь Hydra';
                            qrStatusMsg = '✅ QR-код успешно считан!';
                        });
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
                qrStatusMsg = '⏳ Поиск @никнейма владельца QR...';
                fetch('http://191.223.137.164:8085/nick/resolve?pub=' + encodeURIComponent(addContactAddress))
                    .then(r => r.ok ? r.json() : null)
                    .then(d => {
                        if (d && d.nickname) {
                            addContactName = '@' + d.nickname.replace(/^@/, '');
                            qrStatusMsg = '✅ Найдено имя: ' + addContactName;
                        } else {
                            if (!addContactName.trim() || addContactName.startsWith('Контакт ')) addContactName = 'Пользователь Hydra';
                            qrStatusMsg = '✅ QR-код распознан из картинки!';
                        }
                    }).catch(() => {});
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

<!-- Contact Profile Modal (Redesigned like Screenshot 1 & 2) -->
{#if showContactProfile && contact}
<div style="position: fixed; inset: 0; background: #0B0C10; z-index: 99990; display: flex; flex-direction: column; overflow-y: auto; -webkit-overflow-scrolling: touch; color: #E5E7EB;" on:click={() => showProfileDotsMenu = false}>
    <!-- Верхняя панель со стрелкой назад и меню 3 точки -->
    <div style="display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; padding-top: calc(14px + env(safe-area-inset-top, 12px)); position: sticky; top: 0; z-index: 20; background: rgba(11,12,16,0.85); backdrop-filter: blur(12px);">
        <button type="button" on:click={onCloseContactProfile} style="width: 42px; height: 42px; border-radius: 50%; background: #1A1C20; border: none; color: #fff; font-size: 20px; display: flex; align-items: center; justify-content: center; cursor: pointer;">←</button>
        <div style="position: relative;">
            <button type="button" on:click|stopPropagation={() => showProfileDotsMenu = !showProfileDotsMenu} style="width: 42px; height: 42px; border-radius: 50%; background: #1A1C20; border: none; color: #fff; font-size: 20px; font-weight: 800; display: flex; align-items: center; justify-content: center; cursor: pointer;">⋮</button>
            {#if showProfileDotsMenu}
                <div on:click|stopPropagation style="position: absolute; top: 48px; right: 0; width: 255px; background: #1A1C20; border: 1px solid rgba(255,255,255,0.08); border-radius: 18px; padding: 8px; box-shadow: 0 16px 40px rgba(0,0,0,0.8); z-index: 100;">
                    <button type="button" on:click={() => { showProfileDotsMenu = false; if (window.__cycleChatTtlFromProfile) window.__cycleChatTtlFromProfile(); }} style="width: 100%; display: flex; align-items: center; gap: 12px; padding: 11px 12px; background: transparent; border: none; color: #E5E7EB; font-size: 14px; border-radius: 12px; cursor: pointer; text-align: left;">
                        <span>⏲️</span> <span style="flex:1;">Автоудаление</span> <span>›</span>
                    </button>
                    <button type="button" on:click={() => { showProfileDotsMenu = false; AppActions.ClipboardSet(contact.I2PAddress); alert('✅ Ключ контакта скопирован'); }} style="width: 100%; display: flex; align-items: center; gap: 12px; padding: 11px 12px; background: transparent; border: none; color: #E5E7EB; font-size: 14px; border-radius: 12px; cursor: pointer; text-align: left;">
                        <span>↪️</span> <span>Поделиться контактом</span>
                    </button>
                    <button type="button" on:click={renameCurrentContact} style="width: 100%; display: flex; align-items: center; gap: 12px; padding: 11px 12px; background: transparent; border: none; color: #E5E7EB; font-size: 14px; border-radius: 12px; cursor: pointer; text-align: left;">
                        <span>✏️</span> <span>Изменить контакт</span>
                    </button>
                    <button type="button" on:click={toggleNoCopyProtection} style="width: 100%; display: flex; align-items: center; gap: 12px; padding: 11px 12px; background: transparent; border: none; color: #E5E7EB; font-size: 14px; border-radius: 12px; cursor: pointer; text-align: left;">
                        <span>🔒</span> <span>{noCopyProtected ? 'Разрешить скриншоты' : 'Запретить копирование'}</span>
                    </button>
                    {#if onUpdateProfile}
                        <button type="button" on:click={() => { showProfileDotsMenu = false; onUpdateProfile(contact.I2PAddress); }} style="width: 100%; display: flex; align-items: center; gap: 12px; padding: 11px 12px; background: transparent; border: none; color: #60A5FA; font-size: 14px; border-radius: 12px; cursor: pointer; text-align: left;">
                            <span>🔄</span> <span>Синхронизировать профиль</span>
                        </button>
                    {/if}
                    <button type="button" on:click={async () => { showProfileDotsMenu = false; await AppActions.DeleteContact(contact.ID); onCloseContactProfile(); location.reload(); }} style="width: 100%; display: flex; align-items: center; gap: 12px; padding: 11px 12px; background: transparent; border: none; color: #EF4444; font-size: 14px; font-weight: 600; border-radius: 12px; cursor: pointer; text-align: left;">
                        <span>🗑️</span> <span>Удалить контакт</span>
                    </button>
                </div>
            {/if}
        </div>
    </div>

    <!-- Центральный аватар, имя и статус -->
    <div style="display: flex; flex-direction: column; align-items: center; padding: 4px 20px 18px;">
        <div style="width: 108px; height: 108px; border-radius: 50%; background: {getAvatarGradient(contact.Nickname)}; overflow: hidden; display: flex; align-items: center; justify-content: center; font-size: 40px; font-weight: 800; color: #fff; box-shadow: 0 10px 30px rgba(0,0,0,0.5); margin-bottom: 14px;">
            {#if contact.Avatar}<img src={contact.Avatar} alt="av" style="width:100%;height:100%;object-fit:cover;"/>{:else}{getInitials(contact.Nickname)}{/if}
        </div>
        <div style="font-size: 24px; font-weight: 800; color: #fff; text-align: center;">{contact.Nickname}</div>
        <div style="font-size: 13px; color: #9CA3AF; margin-top: 4px;">
            {getUnifiedPresence(contact).text} <span style="background: rgba(255,255,255,0.08); padding: 2px 8px; border-radius: 100px; font-size: 11px; margin-left: 4px;">защищено E2EE</span>
        </div>

        <!-- Плитки действий профиля: Чат | Звук | Звонок (только 1-на-1) | Блокировка по таймеру -->
        <div style="display: grid; grid-template-columns: repeat({String(contact.Nickname || '').startsWith('👥') || String(contact.Nickname || '').startsWith('📢') ? 3 : 4}, 1fr); gap: 8px; width: 100%; max-width: 400px; margin-top: 20px;">
            <button type="button" on:click={onCloseContactProfile} style="background: #1A1C20; border: none; border-radius: 16px; padding: 12px 6px; display: flex; flex-direction: column; align-items: center; gap: 6px; color: #fff; cursor: pointer;">
                <span style="font-size: 18px;">💬</span>
                <span style="font-size: 12px; font-weight: 600;">Чат</span>
            </button>
            <button type="button" on:click={toggleContactMute} style="background: {contactMuted ? 'rgba(239,68,68,0.18)' : '#1A1C20'}; border: 1px solid {contactMuted ? 'rgba(239,68,68,0.4)' : 'transparent'}; border-radius: 16px; padding: 12px 6px; display: flex; flex-direction: column; align-items: center; gap: 6px; color: #fff; cursor: pointer;">
                <span style="font-size: 18px;">{contactMuted ? '🔕' : '🔔'}</span>
                <span style="font-size: 12px; font-weight: 600;">{contactMuted ? 'Выключен' : 'Звук'}</span>
            </button>
            {#if !String(contact.Nickname || '').startsWith('👥') && !String(contact.Nickname || '').startsWith('📢')}
                <button type="button" on:click={() => { onCloseContactProfile(); if (window.__triggerHydraCallFromProfile) window.__triggerHydraCallFromProfile(); }} style="background: #1A1C20; border: none; border-radius: 16px; padding: 12px 6px; display: flex; flex-direction: column; align-items: center; gap: 6px; color: #fff; cursor: pointer;">
                    <span style="font-size: 18px;">📞</span>
                    <span style="font-size: 12px; font-weight: 600;">Звонок</span>
                </button>
            {/if}
            <button type="button" on:click={() => showBlockDurationModal = true} style="background: {isContactBlockedNow ? 'rgba(239,68,68,0.22)' : '#1A1C20'}; border: 1px solid {isContactBlockedNow ? '#EF4444' : 'transparent'}; border-radius: 16px; padding: 12px 6px; display: flex; flex-direction: column; align-items: center; gap: 6px; color: {isContactBlockedNow ? '#FCA5A5' : '#fff'}; cursor: pointer;">
                <span style="font-size: 18px;">{isContactBlockedNow ? '🔓' : '🚫'}</span>
                <span style="font-size: 12px; font-weight: 600;">{isContactBlockedNow ? 'Разблок.' : 'Блок'}</span>
            </button>
        </div>

        {#if showBlockDurationModal}
            <div on:click|stopPropagation style="margin-top: 14px; width: 100%; max-width: 400px; background: #16181F; border: 1px solid rgba(239,68,68,0.4); border-radius: 20px; padding: 16px; box-sizing: border-box;">
                <div style="font-size: 14px; font-weight: 800; color: #FCA5A5; margin-bottom: 10px;">🚫 Блокировка: {contact.Nickname}</div>
                <div style="font-size: 12px; color: #9CA3AF; margin-bottom: 10px;">Выберите срок, на который нужно заблокировать пользователя:</div>
                <div style="display: flex; gap: 8px; margin-bottom: 12px;">
                    <input type="number" min="1" max="999" bind:value={blockAmount} style="width: 80px; padding: 10px; border-radius: 12px; background: #0E1015; color: #fff; border: 1px solid rgba(255,255,255,0.15); font-size: 14px; font-weight: 700; text-align: center;" />
                    <select bind:value={blockUnit} style="flex: 1; padding: 10px; border-radius: 12px; background: #0E1015; color: #fff; border: 1px solid rgba(255,255,255,0.15); font-size: 13px; font-weight: 600;">
                        <option value="minutes">Минут</option>
                        <option value="hours">Часов</option>
                        <option value="days">Дней</option>
                        <option value="months">Месяцев</option>
                        <option value="years">Лет</option>
                    </select>
                </div>
                <div style="display: flex; gap: 8px;">
                    {#if isContactBlockedNow}
                        <button type="button" on:click={unblockCurrentUser} style="flex: 1; padding: 10px; border-radius: 100px; background: #10B981; color: #fff; border: none; font-weight: 800; font-size: 12px; cursor: pointer;">🔓 Снять блок</button>
                    {/if}
                    <button type="button" on:click={() => showBlockDurationModal = false} style="flex: 1; padding: 10px; border-radius: 100px; background: #22252A; color: #E5E7EB; border: none; font-size: 12px; cursor: pointer;">Отмена</button>
                    <button type="button" on:click={applyTimedUserBlock} style="flex: 1; padding: 10px; border-radius: 100px; background: #EF4444; color: #fff; border: none; font-weight: 800; font-size: 12px; cursor: pointer;">Заблокировать</button>
                </div>
            </div>
        {/if}
    </div>

    <!-- Карточка с данными пользователя -->
    <div style="margin: 0 16px 16px; background: #1A1C20; border-radius: 20px; padding: 16px; display: flex; flex-direction: column; gap: 14px;">
        <div style="display: flex; justify-content: space-between; align-items: center;">
            <div>
                <div style="font-size: 15px; font-weight: 600; color: #fff;">{contact.Nickname?.startsWith('@') ? contact.Nickname : ('@' + (contact.Nickname || 'user').toLowerCase().replace(/\s+/g, ''))}</div>
                <div style="font-size: 12px; color: #9CA3AF; margin-top: 2px;">Имя пользователя Hydra</div>
            </div>
            <button type="button" on:click={() => { AppActions.ClipboardSet(contact.I2PAddress); alert('✅ Крипто-ID скопирован'); }} style="background: rgba(59,130,246,0.15); color: #60A5FA; border: none; border-radius: 12px; width: 38px; height: 38px; font-size: 18px; cursor: pointer;" title="Скопировать ключ">⚃</button>
        </div>
        {#if contact.Bio}
            <div>
                <div style="font-size: 14px; color: #E5E7EB;">{contact.Bio}</div>
                <div style="font-size: 12px; color: #9CA3AF; margin-top: 2px;">О себе</div>
            </div>
        {/if}
        <div on:click={() => showFullAddress = !showFullAddress} style="cursor: pointer;">
            <div style="font-size: 12px; font-family: monospace; color: #60A5FA; word-break: break-all;">
                {showFullAddress ? contact.I2PAddress : ((contact.I2PAddress || '').slice(0, 24) + '...')}
            </div>
            <div style="font-size: 12px; color: #9CA3AF; margin-top: 2px;">Крипто-ID ключ (нажмите, чтобы {showFullAddress ? 'скрыть' : 'показать полностью'})</div>
        </div>
    </div>

    <!-- Вкладки Медиа / Файлы / Голосовые как на Скриншотах 1 и 2 -->
    <div style="display: flex; gap: 8px; padding: 6px 16px; margin-bottom: 10px; overflow-x: auto;">
        <button type="button" on:click={() => profileActiveTab = 'media'} style="padding: 8px 18px; border-radius: 100px; border: none; font-size: 13px; font-weight: 700; background: {profileActiveTab === 'media' ? '#1E3A8A' : '#1A1C20'}; color: {profileActiveTab === 'media' ? '#fff' : '#9CA3AF'}; cursor: pointer;">Медиа</button>
        <button type="button" on:click={() => profileActiveTab = 'files'} style="padding: 8px 18px; border-radius: 100px; border: none; font-size: 13px; font-weight: 700; background: {profileActiveTab === 'files' ? '#1E3A8A' : '#1A1C20'}; color: {profileActiveTab === 'files' ? '#fff' : '#9CA3AF'}; cursor: pointer;">Файлы</button>
        <button type="button" on:click={() => profileActiveTab = 'voice'} style="padding: 8px 18px; border-radius: 100px; border: none; font-size: 13px; font-weight: 700; background: {profileActiveTab === 'voice' ? '#1E3A8A' : '#1A1C20'}; color: {profileActiveTab === 'voice' ? '#fff' : '#9CA3AF'}; cursor: pointer;">Голосовые</button>
    </div>
    <div style="padding: 16px; text-align: center; color: #6B7280; font-size: 13px;">
        🔒 Все {profileActiveTab === 'media' ? 'фото и видео-кружки' : (profileActiveTab === 'files' ? 'переданные файлы' : 'голосовые сообщения')} этого чата хранятся в зашифрованном контейнере E2EE.
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
    <div class="modal-content animate-slide-down" style="max-width: 450px; background: #16181D; border: 1px solid rgba(255, 100, 100, 0.2);">
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
    .input-field:focus { border-color: var(--accent); box-shadow: 0 0 10px rgba(29, 78, 216, 0.2); }
    
    .modal-footer { display: flex; gap: 8px; margin-top: 20px; flex-wrap: wrap; justify-content: flex-end; }
    .modal-footer .btn-small { padding: 10px 14px; font-size: 13px; flex: 1; min-width: 85px; text-align: center; }
    .btn-small { padding: 12px 24px; border-radius: 16px; font-weight: 600; cursor: pointer; border: none; transition: all 0.2s; font-size: 14px; }
    .btn-small:hover { opacity: 0.9; transform: translateY(-2px); }
    
    .btn-primary { background: var(--accent); color: white; flex: 1; border: none; border-radius: 16px; font-weight: 600; transition: all 0.2s; }
    .accent-btn { background: #1D4ED8 !important; }
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
    .emoji-btn.selected { border-color: var(--accent); background: rgba(29, 78, 216, 0.15); }
</style>
