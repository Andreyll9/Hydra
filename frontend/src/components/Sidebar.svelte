<script>
    import * as AppActions from '../../wailsjs/go/main/App.js';
    import { EventsOn } from '../../wailsjs/runtime/runtime.js';
    import { Icons } from '../Icons.js';
    import { getInitials, formatTime, getStatusColor, getStatusText, getAvatarGradient, formatPreviewText } from '../utils.js';
    import { writable } from 'svelte/store';

    export let isMobile;
    export let contacts = [];
    export let folders = [];
    export let activeFolderId;
    export let searchQuery;
    export let networkStatus;
    export let showSettings;
    export let sidebarWidth;
    export let isResizing;
    export let selectedContact;
    export let identity;
    export let unreadCount = 0;
    export let pinnedChats = [];

    export let onSelectContact;
    export let onContextMenu;
    export let onToggleSettings;
    export let onStartResize;
    export let onOpenAddContact;
    export let onAddContactFromClipboard;
    export let onCopyDestination;
    export let onOpenMyQR;
    export let onSelectFolder;
    export let onEditFolder;
    export let onCreateFolder;
    export let onFolderContextMenu;

    let longPressTimer;

    $: filteredContacts = (contacts || []).filter(c => {
        if (!c || !c.Nickname) return false;
        
        // Фильтрация по папкам
        if (activeFolderId && activeFolderId !== 'all') {
            const folder = (folders || []).find(f => (f.ID || f.id) === activeFolderId);
            if (folder) {
                const chatIds = folder.ChatIDs || folder.chat_ids || [];
                if (!chatIds.includes(c.ID)) {
                    return false;
                }
            }
        }

        const query = (searchQuery || "").toLowerCase();
        const nickname = (c.Nickname || "").toLowerCase();
        const lastMsg = (c.LastMessage || "").toLowerCase();
        const addr = (c.I2PAddress || c.PublicKey || '').toLowerCase();
        return nickname.includes(query) || lastMsg.includes(query) || addr.includes(query);
    });

    $: sortedContacts = [...filteredContacts].sort((a, b) => {
        const aIndex = pinnedChats.indexOf(a.ID);
        const bIndex = pinnedChats.indexOf(b.ID);
        
        if (aIndex !== -1 && bIndex !== -1) {
            return aIndex - bIndex; // Both pinned, sort by index
        }
        if (aIndex !== -1) return -1; // a pinned
        if (bIndex !== -1) return 1;  // b pinned
        
        // Keep original order otherwise (usually by last message time if backend provides it sorted, or ID)
        return 0;
    });

    $: totalUnread = (contacts || []).reduce((sum, c) => sum + (c.UnreadCount || 0), 0);

    $: uiFolders = [
        { ID: 'all', Name: 'Все', Icon: Icons.Chat, UnreadCount: totalUnread },
        ...([...(folders || [])].sort((a, b) => ((a.Position ?? a.position) || 0) - ((b.Position ?? b.position) || 0))),
        { ID: 'add', Name: 'Создать', Icon: Icons.Plus, UnreadCount: 0 }
    ];

    function handleTouchStart(item, type, e) {
        longPressTimer = setTimeout(() => {
            if (type === 'contact') {
                onContextMenu(e, item);
            } else if (type === 'folder') {
                onEditFolder(item);
            }
        }, 700);
    }

    function handleTouchEnd() {
        clearTimeout(longPressTimer);
    }

    function handleFolderClick(folder) {
        const id = folder.ID || folder.id;
        if (id === 'add') {
            onCreateFolder();
        } else {
            onSelectFolder(id);
        }
    }

    // === ЭТАП 4: СТОРИС НА 24 ЧАСА (ФОТО + ЖИВОЕ ВИДЕО С КАМЕРЫ) ===
    let hydraStories = [];
    let activeStoryModal = null;
    let showCreateStoryModal = false;
    let newStoryText = '';
    let newStoryImage = '';
    let newStoryVideo = '';

    let isStoryCameraOpen = false;
    let storyCameraStream = null;
    let storyVideoEl = null;
    let isRecordingStoryVideo = false;
    let storyMediaRecorder = null;
    let storyRecordedChunks = [];
    let storyFacingMode = 'user';

    function loadHydraStories() {
        try {
            const raw = JSON.parse(localStorage.getItem('hydra_stories_v1') || '[]');
            const now = Date.now();
            hydraStories = raw.filter(s => (now - s.createdAt) < 86400000);
            localStorage.setItem('hydra_stories_v1', JSON.stringify(hydraStories));
        } catch (e) {
            hydraStories = [];
        }
    }
    loadHydraStories();

    EventsOn('remote_story_received', (payload) => {
        if (!payload || !payload.data) return;
        try {
            const stObj = JSON.parse(payload.data);
            stObj.author = payload.author || stObj.author || 'Контакт';
            loadHydraStories();
            if (!hydraStories.some(s => s.id === stObj.id)) {
                hydraStories = [stObj, ...hydraStories];
                localStorage.setItem('hydra_stories_v1', JSON.stringify(hydraStories));
            }
        } catch (e) {}
    });
    

    async function joinGroupByInviteKey(rawKey) {
        const cleanKey = String(rawKey || '').trim();
        let roomTitle = '👥 Группа Hydra';
        try {
            const decoded = decodeURIComponent(escape(atob(cleanKey)));
            if (decoded.startsWith('HYDRA_ROOM_')) {
                const body = decoded.slice('HYDRA_ROOM_'.length);
                const namePart = body.split('_')[0];
                if (namePart) roomTitle = namePart;
            }
        } catch (e) {}
        try {
            const created = await AppActions.AddContact(roomTitle, cleanKey);
            searchQuery = '';
            if (created && onSelectContact) onSelectContact(created);
            else location.reload();
        } catch (e) {
            alert('Ошибка перехода в группу: ' + e);
        }
    }
    if (typeof window !== 'undefined') {
        window.__joinHydraGroupByKey = joinGroupByInviteKey;
    }
    

    async function toggleStoryCamera(switchCam = false) {
        if (isStoryCameraOpen && !switchCam) {
            stopStoryCamera();
            return;
        }
        if (switchCam) {
            storyFacingMode = storyFacingMode === 'user' ? 'environment' : 'user';
            stopStoryCamera();
        }
        if (window.__addHydraLog) window.__addHydraLog('STORY-CAM', 'Запуск живой камеры для Сторис (' + storyFacingMode + ')');
        try {
            storyCameraStream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: storyFacingMode, width: { ideal: 480 }, height: { ideal: 640 } },
                audio: true
            });
            isStoryCameraOpen = true;
            setTimeout(() => {
                if (storyVideoEl && storyCameraStream) {
                    storyVideoEl.srcObject = storyCameraStream;
                    storyVideoEl.play().catch(() => {});
                }
            }, 100);
        } catch (err) {
            alert('Не удалось открыть камеру для Сторис: ' + err);
            if (window.__addHydraLog) window.__addHydraLog('STORY-CAM-ERR', String(err));
        }
    }

    function stopStoryCamera() {
        if (storyMediaRecorder && isRecordingStoryVideo) {
            storyMediaRecorder.stop();
            isRecordingStoryVideo = false;
        }
        if (storyCameraStream) {
            storyCameraStream.getTracks().forEach(t => t.stop());
            storyCameraStream = null;
        }
        isStoryCameraOpen = false;
    }

    function captureStoryPhotoFromCam() {
        if (!storyVideoEl) return;
        const canvas = document.createElement('canvas');
        canvas.width = storyVideoEl.videoWidth || 480;
        canvas.height = storyVideoEl.videoHeight || 640;
        canvas.getContext('2d').drawImage(storyVideoEl, 0, 0, canvas.width, canvas.height);
        newStoryImage = canvas.toDataURL('image/jpeg', 0.85);
        newStoryVideo = '';
        if (window.__addHydraLog) window.__addHydraLog('STORY-SNAP', 'Снято живое фото с камеры для Сторис');
        stopStoryCamera();
    }

    function toggleRecordStoryVideo() {
        if (!storyCameraStream) return;
        if (isRecordingStoryVideo && storyMediaRecorder) {
            storyMediaRecorder.stop();
            isRecordingStoryVideo = false;
            return;
        }
        storyRecordedChunks = [];
        try {
            storyMediaRecorder = new MediaRecorder(storyCameraStream, { videoBitsPerSecond: 250000, audioBitsPerSecond: 32000 });
        } catch (e) {
            storyMediaRecorder = new MediaRecorder(storyCameraStream);
        }
        storyMediaRecorder.ondataavailable = (ev) => {
            if (ev.data && ev.data.size > 0) storyRecordedChunks.push(ev.data);
        };
        storyMediaRecorder.onstop = () => {
            const blob = new Blob(storyRecordedChunks, { type: 'video/webm' });
            const reader = new FileReader();
            reader.onloadend = () => {
                newStoryVideo = reader.result;
                newStoryImage = '';
                if (window.__addHydraLog) window.__addHydraLog('STORY-VID-OK', 'Записано видео-сторис (' + blob.size + ' байт)');
                stopStoryCamera();
            };
            reader.readAsDataURL(blob);
        };
        storyMediaRecorder.start(250);
        isRecordingStoryVideo = true;
        if (window.__addHydraLog) window.__addHydraLog('STORY-VID-REC', 'Начата запись видео-сторис с камеры');
    }

    function handleStoryFile(e) {
        const file = e.target.files?.[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = () => {
            if (file.type && file.type.startsWith('video/')) {
                newStoryVideo = reader.result;
                newStoryImage = '';
            } else {
                const img = new Image();
                img.onload = () => {
                    const canvas = document.createElement('canvas');
                    const maxDim = 720;
                    let w = img.width || 600, h = img.height || 800;
                    if (w > maxDim || h > maxDim) {
                        if (w > h) { h = Math.round(h * maxDim / w); w = maxDim; }
                        else { w = Math.round(w * maxDim / h); h = maxDim; }
                    }
                    canvas.width = w;
                    canvas.height = h;
                    canvas.getContext('2d').drawImage(img, 0, 0, w, h);
                    newStoryImage = canvas.toDataURL('image/jpeg', 0.8);
                    newStoryVideo = '';
                };
                img.src = reader.result;
            }
        };
        reader.readAsDataURL(file);
    }

    function publishHydraStory() {
        if (!newStoryText.trim() && !newStoryImage && !newStoryVideo) return;
        stopStoryCamera();
        loadHydraStories();
        hydraStories = [{
            id: 'st_' + Date.now(),
            author: 'Моя история',
            text: newStoryText.trim(),
            image: newStoryImage,
            video: newStoryVideo,
            createdAt: Date.now(),
            openViews: [],
            anonViews: 0
        }, ...hydraStories];
        localStorage.setItem('hydra_stories_v1', JSON.stringify(hydraStories));
        const publishedStory = hydraStories[0];
        (contacts || []).forEach(c => {
            if (c && c.ID && !c.IsFavorites && !String(c.Nickname || '').startsWith('👥') && !String(c.Nickname || '').startsWith('📢')) {
                AppActions.SendText(c.ID, '[HYDRA_STORY_SYNC]:' + JSON.stringify(publishedStory), '').catch(() => {});
            }
        });
        if (window.__addHydraLog) window.__addHydraLog('STORY-PUB', 'Опубликована и разослана контактам новая История на 24ч');
        newStoryText = '';
        newStoryImage = '';
        newStoryVideo = '';
        showCreateStoryModal = false;
    }

    
    let storyPaused = false;
    let storyIsVertical = false;

    function deleteCurrentStory(stId) {
        hydraStories = hydraStories.filter(s => s.id !== stId);
        localStorage.setItem('hydra_stories_v1', JSON.stringify(hydraStories));
        activeStoryModal = null;
    }
    
    function openHydraStory(st) {
        storyPaused = false;
        const isAnon = localStorage.getItem('hydra_anon_stories') === '1';
        if (isAnon) {
            st.anonViews = (st.anonViews || 0) + 1;
        } else {
            if (!st.openViews) st.openViews = [];
            if (!st.openViews.includes('Вы (Открыто)')) st.openViews.push('Вы (Открыто)');
        }
        localStorage.setItem('hydra_stories_v1', JSON.stringify(hydraStories));
        activeStoryModal = st;
    }

    function formatContactDisplayName(name) {
        if (!name) return 'Неизвестный';
        const s = String(name).trim();
        if (s.length > 22 && !s.includes(' ')) return '👤 Контакт (' + s.slice(0, 6) + '…' + s.slice(-4) + ')';
        return s;
    }
    

    let showNearbyWifiModal = false;
    let nearbyWifiUsers = [];
    let isScanningNearby = false;
    let myWifiSubnetIp = '';

    async function openNearbyWifiRadar() {
        showNearbyWifiModal = true;
        await scanNearbyWifiPeers();
    }

    async function scanNearbyWifiPeers() {
        isScanningNearby = true;
        nearbyWifiUsers = [];
        try {
            let lanInfo = { lanIp: '', lanActive: false };
            if (window.HydraNative && typeof window.HydraNative.getLocalWifiInfo === 'function') {
                lanInfo = JSON.parse(window.HydraNative.getLocalWifiInfo());
            }
            myWifiSubnetIp = lanInfo.lanIp || '';
            if (!lanInfo.lanActive || !myWifiSubnetIp) {
                isScanningNearby = false;
                return;
            }
            const subnet = myWifiSubnetIp.split('.').slice(0, 3).join('.');
            const hideMe = localStorage.getItem('hydra_hide_nearby_wifi') === '1';
            const myInfo = await AppActions.GetMyInfo().catch(() => null);
            const myNick = (myInfo?.Nickname || '').trim();
            const myPub = (myInfo?.PublicKey || myInfo?.Destination || identity || '').trim();

            if (!hideMe && myNick && myPub) {
                await fetch('https://rtbeta.duckdns.org/relay/send', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        id: 'wifi_beacon_' + myPub.slice(0, 10),
                        from: myPub,
                        from_nick: myNick.startsWith('@') ? myNick : ('@' + myNick),
                        to: 'WIFI_SUBNET_' + subnet,
                        chat_id: 'WIFI_NEARBY',
                        content: 'WIFI_BEACON',
                        timestamp: Date.now()
                    })
                }).catch(() => {});
            }

            const r = await fetch('https://rtbeta.duckdns.org/relay/poll?pub=' + encodeURIComponent('WIFI_SUBNET_' + subnet));
            if (r.ok) {
                const items = await r.json();
                const mapByPub = {};
                for (const it of (items || [])) {
                    if (it.from && it.from !== myPub && it.from_nick) {
                        mapByPub[it.from] = { nick: it.from_nick, pub: it.from, ts: it.timestamp || Date.now() };
                    }
                }
                nearbyWifiUsers = Object.values(mapByPub);
            }
        } catch (e) {} finally {
            isScanningNearby = false;
        }
    }

    async function connectToNearbyUser(u) {
        try {
            await AppActions.AddContact(u.nick, u.pub);
            showNearbyWifiModal = false;
            location.reload();
        } catch (e) { alert('Ошибка: ' + e); }
    }
    
</script>

{#if !isMobile}
    <!-- Folders Rail -->
    <div class="folders-rail">
        <div 
            class="rail-button" 
            class:active={showSettings} 
            role="button"
            tabindex="0"
            on:click={onToggleSettings}
            on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && onToggleSettings()}
        >
            <div class="icon-svg">{@html Icons.Menu}</div>
        </div>

        <div class="folders-list">
            {#each uiFolders as folder}
                <div 
                    class="folder-item" 
                    class:active={!showSettings && activeFolderId === (folder.ID || folder.id) && (folder.ID || folder.id) !== 'add'} 
                    role="button"
                    tabindex="0"
                    on:click={() => handleFolderClick(folder)}
                    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && handleFolderClick(folder)}
                    on:contextmenu|preventDefault={(e) => (folder.ID || folder.id) !== 'all' && (folder.ID || folder.id) !== 'add' && onFolderContextMenu(e, folder)}
                    on:touchstart={(e) => (folder.ID || folder.id) !== 'all' && (folder.ID || folder.id) !== 'add' && handleTouchStart(folder, 'folder', e)}
                    on:touchend={handleTouchEnd}
                    on:touchmove={handleTouchEnd}
                    title={folder.Name || folder.name}
                    style={(folder.ID || folder.id) === 'add' ? 'margin-top: 10px; opacity: 0.7;' : 'position: relative;'}
                >
                    <div class="folder-icon">{@html folder.Icon || folder.icon}</div>
                    <div class="folder-name">{folder.Name || folder.name}</div>
                    {#if (folder.UnreadCount || folder.unread_count) > 0 && (folder.ID || folder.id) !== 'add'}
                        <div class="folder-unread-badge">{(folder.UnreadCount || folder.unread_count) > 99 ? '99+' : (folder.UnreadCount || folder.unread_count)}</div>
                    {/if}
                </div>
            {/each}
        </div>
    </div>

    <!-- Sidebar (Resizable) -->
    <div class="sidebar" style="width: {sidebarWidth}px; min-width: 240px; flex: none; display: flex; flex-direction: column;">
        <div class="sidebar-header" style="padding: 10px; background: var(--bg-secondary);">
            <div class="search-input-wrapper">
                <span class="search-icon"><div class="icon-svg-sm">{@html Icons.Search}</div></span>
                <input type="text" placeholder="Поиск" bind:value={searchQuery} />
            </div>
        </div>
        
        <div class="sidebar-actions">
           <button class="btn-primary" on:click={onOpenAddContact}>
              <div class="icon-svg-sm">{@html Icons.MessageSquarePlus}</div>
              <span>Новый чат</span>
           </button>
        </div>
        
        <!-- Network Status -->
        <div class="network-status" style="background: {getStatusColor(networkStatus)}15">
            <div class="status-dot" class:animate-pulse={networkStatus === 'connecting'} style="background: {getStatusColor(networkStatus)}"></div>
            <span>{getStatusText(networkStatus)}</span>
        </div>
        
        <!-- Contacts List -->
        
    <!-- Лента Сторис (24 часа) -->
    <div style="display: flex; gap: 12px; padding: 10px 16px; overflow-x: auto; border-bottom: 1px solid rgba(255,255,255,0.05); align-items: center;">
        <div on:click={() => showCreateStoryModal = true} style="display: flex; flex-direction: column; align-items: center; cursor: pointer; min-width: 62px;">
            <div style="width: 50px; height: 50px; border-radius: 50%; background: #28292D; border: 2px dashed #8AB4F8; display: flex; align-items: center; justify-content: center; font-size: 22px; color: #8AB4F8; font-weight: 700;">+</div>
            <span style="font-size: 11px; color: #E3E3E3; margin-top: 4px; white-space: nowrap;">История</span>
        </div>
        {#each hydraStories as st}
            <div on:click={() => openHydraStory(st)} style="display: flex; flex-direction: column; align-items: center; cursor: pointer; min-width: 62px;">
                <div style="width: 50px; height: 50px; border-radius: 50%; padding: 2px; background: linear-gradient(135deg, #8AB4F8, #4285F4);">
                    <div style="width: 100%; height: 100%; border-radius: 50%; background: #1E1F22; display: flex; align-items: center; justify-content: center; overflow: hidden;">
                        {#if st.image}
                            <img src={st.image} alt="story" style="width: 100%; height: 100%; object-fit: cover;" />
                        {:else}
                            <span style="font-size: 18px;">🔥</span>
                        {/if}
                    </div>
                </div>
                <span style="font-size: 11px; color: #9AA0A6; margin-top: 4px; max-width: 60px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{st.author}</span>
            </div>
        {/each}
    </div>

        <div class="contacts-list">
            <!-- Favorites Chat -->
            {#if !searchQuery || 'избранное'.includes(searchQuery.toLowerCase()) || 'saved messages'.includes(searchQuery.toLowerCase()) || 'favorites'.includes(searchQuery.toLowerCase())}
                <div 
                    class="contact-item favorites-item animate-card" 
                    class:selected={selectedContact && (selectedContact.IsFavorites || selectedContact.ID === identity)} 
                    on:click={() => onSelectContact({ID: identity, Nickname: 'Избранное', IsFavorites: true, ChatID: identity})}
                    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelectContact({ID: identity, Nickname: 'Избранное', IsFavorites: true, ChatID: identity})}
                    tabindex="0"
                    role="button"
                >
                    <div class="contact-avatar favorites-avatar" style="background: linear-gradient(135deg, #1D4ED8 0%, #3B82F6 100%);">
                        <div class="icon-svg-sm" style="color: white; width: 20px; height: 20px;">{@html Icons.Bookmark}</div>
                    </div>
                    <div class="contact-info">
                        <div class="contact-name">Избранное</div>
                        <div class="contact-last">Сохранённые сообщения</div>
                    </div>
                </div>
            {/if}

            {#each sortedContacts as contact}
                <div 
                    class="contact-item animate-card" 
                    class:selected={selectedContact && selectedContact.ID === contact.ID} 
                    class:pinned={pinnedChats.includes(contact.ID)}
                    on:click={() => onSelectContact(contact)}
                    on:contextmenu|preventDefault={(e) => onContextMenu(e, contact)}
                    on:touchstart={(e) => handleTouchStart(contact, 'contact', e)}
                    on:touchend={handleTouchEnd}
                    on:touchmove={handleTouchEnd}
                    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelectContact(contact)}
                    tabindex="0"
                    role="button"
                >
                    <div class="contact-avatar" style="background: {getAvatarGradient(contact.Nickname)};">
                        {#if contact.Avatar}
                            <img src={contact.Avatar} alt="av"/>
                        {:else}
                            {getInitials(contact.Nickname)}
                        {/if}
                    </div>
                    <div class="contact-info">
                        <div class="contact-header">
                            <div class="contact-name">
                                {formatContactDisplayName(contact.Nickname)}
                                {#if pinnedChats.includes(contact.ID)}
                                    <span class="pin-icon">{@html Icons.Pin}</span>
                                {/if}
                            </div>
                            <span class="contact-time">{contact.LastMessageTime ? formatTime(contact.LastMessageTime) : ''}</span>
                        </div>
                        <div class="contact-last">{formatPreviewText(contact.LastMessage)}</div>
                    </div>
                    {#if contact.UnreadCount > 0}
                        <div class="contact-unread-badge">{contact.UnreadCount > 99 ? '99+' : contact.UnreadCount}</div>
                    {/if}
                </div>
            {/each}
            
            {#if contacts.length === 0}
                <div class="no-contacts">
                    <div class="no-contacts-icon"><div class="icon-svg" style="width:48px;height:48px;">{@html Icons.Ghost}</div></div>
                    <p>Нет контактов</p>
                    <p class="hint">Нажмите + чтобы добавить</p>
                </div>
            {/if}
        </div>
        
        <!-- My Destination -->
        <div class="my-destination">
            <button class="btn-copy" on:click={onOpenMyQR}>
                <div class="icon-svg-sm">{@html Icons.QrCode}</div>
                <span>Мой ID ключ</span>
            </button>
        </div>
    </div>
    
    <div class="resizer" on:mousedown={onStartResize} class:resizing={isResizing}></div>
{:else}
    <!-- Mobile Sidebar -->
    <div class="mobile-sidebar">
        <!-- Mobile Header -->
        <div class="mobile-header">
            <button class="mobile-menu-btn" on:click={onToggleSettings} style="position: relative;">
                <div class="icon-svg">{@html Icons.Menu}</div>
            </button>
            <h1 class="mobile-title">Hydra</h1>
            <div class="mobile-network-dot" style="background: {getStatusColor(networkStatus)}" title={getStatusText(networkStatus)}></div>
        </div>
        
        <!-- Mobile Search -->
        <div class="mobile-search">
            <div class="search-input-wrapper">
                <span class="search-icon"><div class="icon-svg-sm">{@html Icons.Search}</div></span>
                <input type="text" placeholder="Поиск" bind:value={searchQuery} />
            </div>
        </div>

        <!-- Mobile Folders Bar -->
        
        {#if searchQuery && searchQuery.trim().startsWith('SFlEUkFfUk9PTV')}
            <div style="margin: 6px 16px; padding: 12px 14px; background: linear-gradient(135deg, #172554, #1E3A8A); border: 1px solid #3B82F6; border-radius: 16px; display: flex; justify-content: space-between; align-items: center;">
                <div>
                    <div style="font-size: 13px; font-weight: 800; color: #fff;">🎟️ Найдена ссылка-ключ группы</div>
                    <div style="font-size: 11px; color: #93C5FD;">Нажмите, чтобы открыть или вступить</div>
                </div>
                <button type="button" on:click={() => joinGroupByInviteKey(searchQuery)} style="background: #fff; color: #1E3A8A; border: none; border-radius: 100px; padding: 7px 14px; font-size: 12px; font-weight: 800; cursor: pointer;">
                    ➡️ Перейти
                </button>
            </div>
        {/if}
    
        <div class="mobile-folders-bar">
            {#each uiFolders as folder}
                <button 
                    class="mobile-folder-chip"
                    class:active={activeFolderId === (folder.ID || folder.id) && (folder.ID || folder.id) !== 'add'}
                    on:click={() => handleFolderClick(folder)}
                    on:touchstart={(e) => (folder.ID || folder.id) !== 'all' && (folder.ID || folder.id) !== 'add' && handleTouchStart(folder, 'folder', e)}
                    on:touchend={handleTouchEnd}
                    style="position: relative;"
                >
                    <span class="mobile-folder-icon">{@html folder.Icon || folder.icon}</span>
                    <span>{folder.Name || folder.name}</span>
                    {#if (folder.UnreadCount || folder.unread_count) > 0 && (folder.ID || folder.id) !== 'add'}
                        <span class="mobile-folder-badge">{(folder.UnreadCount || folder.unread_count) > 99 ? '99+' : (folder.UnreadCount || folder.unread_count)}</span>
                    {/if}
                </button>
            {/each}
        </div>

        <!-- Mobile Contacts List -->
        
        <!-- Мобильная лента Сторис (24 часа) -->
        <div style="display: flex; gap: 12px; padding: 8px 16px 12px; overflow-x: auto; border-bottom: 1px solid rgba(255,255,255,0.06); align-items: center; flex-shrink: 0;">
            <div on:click={() => showCreateStoryModal = true} style="display: flex; flex-direction: column; align-items: center; cursor: pointer; min-width: 64px;">
                <div style="width: 52px; height: 52px; border-radius: 50%; background: #1E1F22; border: 2px dashed #8AB4F8; display: flex; align-items: center; justify-content: center; font-size: 24px; color: #8AB4F8; font-weight: 700;">+</div>
                <span style="font-size: 11px; color: #8AB4F8; font-weight: 600; margin-top: 4px; white-space: nowrap;">История</span>
            </div>

        
            {#each hydraStories as st}
                <div on:click={() => openHydraStory(st)} style="display: flex; flex-direction: column; align-items: center; cursor: pointer; min-width: 64px;">
                    <div style="width: 52px; height: 52px; border-radius: 50%; padding: 2px; background: linear-gradient(135deg, #8AB4F8, #4285F4);">
                        <div style="width: 100%; height: 100%; border-radius: 50%; background: #1E1F22; display: flex; align-items: center; justify-content: center; overflow: hidden;">
                            {#if st.image}
                                <img src={st.image} alt="story" style="width: 100%; height: 100%; object-fit: cover;" />
                            {:else}
                                <span style="font-size: 20px;">🔥</span>
                            {/if}
                        </div>
                    </div>
                    <span style="font-size: 11px; color: #E3E3E3; margin-top: 4px; max-width: 62px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{st.author}</span>
                </div>
            {/each}
        </div>
    
        <div class="contacts-list mobile-contacts">
            <!-- Mobile Favorites Chat -->
            {#if !searchQuery || 'избранное'.includes(searchQuery.toLowerCase()) || 'saved messages'.includes(searchQuery.toLowerCase()) || 'favorites'.includes(searchQuery.toLowerCase())}
                <div 
                    class="contact-item favorites-item animate-card"
                    class:selected={selectedContact && (selectedContact.IsFavorites || selectedContact.ID === identity)}
                    on:click={() => onSelectContact({ID: identity, Nickname: 'Избранное', IsFavorites: true, ChatID: identity})}
                    tabindex="0"
                    role="button"
                >
                    <div class="contact-avatar favorites-avatar" style="background: linear-gradient(135deg, #1D4ED8 0%, #3B82F6 100%);">
                        <div class="icon-svg-sm" style="color: white; width: 20px; height: 20px;">{@html Icons.Bookmark}</div>
                    </div>
                    <div class="contact-info">
                        <div class="contact-name">Избранное</div>
                        <div class="contact-last">Сохранённые сообщения</div>
                    </div>
                </div>
            {/if}

            {#each sortedContacts as contact}
                <div 
                    class="contact-item animate-card"
                    class:selected={selectedContact && selectedContact.ID === contact.ID}
                    on:click={() => onSelectContact(contact)}
                    on:contextmenu|preventDefault={(e) => onContextMenu(e, contact)}
                    on:touchstart={(e) => handleTouchStart(contact, 'contact', e)}
                    on:touchend={handleTouchEnd}
                    on:touchmove={handleTouchEnd}
                    tabindex="0"
                    role="button"
                >
                    <div class="contact-avatar" style="background: {getAvatarGradient(contact.Nickname)};">
                        {#if contact.Avatar}
                            <img src={contact.Avatar} alt="av"/>
                        {:else}
                            {getInitials(contact.Nickname)}
                        {/if}
                    </div>
                    <div class="contact-info">
                        <div class="contact-name">{formatContactDisplayName(contact.Nickname)}</div>
                        <div class="contact-last">{formatPreviewText(contact.LastMessage)}</div>
                    </div>
                    {#if contact.UnreadCount > 0}
                        <div class="contact-unread-badge">{contact.UnreadCount > 99 ? '99+' : contact.UnreadCount}</div>
                    {/if}
                </div>
            {/each}
            
            {#if contacts.length === 0}
                <div class="no-contacts">
                    <div class="no-contacts-icon"><div class="icon-svg" style="width:48px;height:48px;">{@html Icons.Ghost}</div></div>
                    <p>Нет контактов</p>
                    <p class="hint">Нажмите + чтобы добавить</p>
                </div>
            {/if}
        </div>
        
        <!-- Mobile Bottom Actions -->
        <div class="mobile-bottom-bar">
            <button class="mobile-action-btn" on:click={onOpenAddContact}>
                <div class="icon-svg">{@html Icons.MessageSquarePlus}</div>
                <span>Новый чат</span>
            </button>
            <button class="mobile-action-btn" on:click={() => {
                // If onOpenMyQR is just opening modal, maybe we want direct copy?
                // The user asked "cannot copy own qr code/address".
                // Let's open QR modal (onOpenMyQR) BUT ensure QR modal has copy button.
                // Or successfuly adding a copy action here too.
                // Let's do both: Open QR, but also long press to copy? 
                // Simple: just open QR, but verify QR modal has copy.
                // Wait, user said "cannot copy". 
                // Let's add a copy button TO the QR modal. 
                // For now, let's make this button open QR, and separately ensure QR modal has copy.
                onOpenMyQR();
            }}>
                <div class="icon-svg">{@html Icons.QrCode}</div>
                <span>Мой адрес</span>
            </button>
        </div>
    </div>
{/if}


{#if showCreateStoryModal}
    <div style="position: fixed; inset: 0; background: rgba(0,0,0,0.88); z-index: 99999; display: flex; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: #1E1F22; border: 1px solid rgba(138,180,248,0.25); border-radius: 24px; padding: 18px; width: 100%; max-width: 380px; max-height: 90vh; overflow-y: auto;">
            <h3 style="margin: 0 0 4px; color: #E3E3E3;">📸 Новая история (на 24 часа)</h3>
            <p style="margin: 0 0 12px; font-size: 11px; color: #9AA0A6;">Снимите фото или видео напрямую с камеры или выберите из галереи.</p>

            {#if isStoryCameraOpen}
                <div style="position: relative; width: 100%; height: 280px; border-radius: 18px; overflow: hidden; background: #000; border: 2px solid #8AB4F8; margin-bottom: 10px;">
                    <video bind:this={storyVideoEl} autoplay playsinline muted style="width: 100%; height: 100%; object-fit: cover;"></video>
                    <div style="position: absolute; bottom: 10px; left: 0; right: 0; display: flex; justify-content: center; gap: 10px; padding: 0 10px;">
                        <button type="button" on:click={captureStoryPhotoFromCam} style="background: #8AB4F8; color: #062E6F; border: none; border-radius: 100px; padding: 8px 14px; font-weight: 700; font-size: 12px;">📸 Фото</button>
                        <button type="button" on:click={toggleRecordStoryVideo} style="background: {isRecordingStoryVideo ? '#ff4757' : '#E3E3E3'}; color: {isRecordingStoryVideo ? '#fff' : '#121212'}; border: none; border-radius: 100px; padding: 8px 14px; font-weight: 700; font-size: 12px;">
                            {isRecordingStoryVideo ? '⏹ Стоп видео' : '🔴 Записать видео'}
                        </button>
                        <button type="button" on:click={() => toggleStoryCamera(true)} style="background: rgba(0,0,0,0.6); color: #fff; border: 1px solid rgba(255,255,255,0.3); border-radius: 100px; padding: 8px 12px; font-size: 12px;">🔄</button>
                    </div>
                </div>
            {/if}

            {#if newStoryImage}
                <img src={newStoryImage} alt="preview" style="width: 100%; max-height: 220px; object-fit: cover; border-radius: 16px; margin-bottom: 10px;" />
            {/if}
            {#if newStoryVideo}
                <video controls playsinline src={newStoryVideo} style="width: 100%; max-height: 220px; border-radius: 16px; margin-bottom: 10px; background: #000;"></video>
            {/if}

            <div style="display: flex; gap: 8px; margin-bottom: 10px;">
                <button type="button" on:click={() => toggleStoryCamera(false)} style="flex: 1; padding: 11px; border-radius: 100px; background: {isStoryCameraOpen ? '#ff4757' : '#8AB4F8'}; color: {isStoryCameraOpen ? '#fff' : '#062E6F'}; border: none; font-weight: 700; font-size: 12px; cursor: pointer;">
                    {isStoryCameraOpen ? '✕ Закрыть камеру' : '📹 Открыть камеру (Фото/Видео)'}
                </button>
                <label style="padding: 11px 14px; border-radius: 100px; background: #28292D; color: #8AB4F8; border: 1px solid rgba(138,180,248,0.35); font-weight: 700; font-size: 12px; cursor: pointer; display: flex; align-items: center;">
                    🖼 Галерея
                    <input type="file" accept="image/*,video/*" style="display: none;" on:change={handleStoryFile} />
                </label>
            </div>

            <input type="text" placeholder="Текст или подпись к истории..." bind:value={newStoryText} style="width: 100%; padding: 12px; border-radius: 14px; background: #121212; color: #E3E3E3; border: 1px solid rgba(255,255,255,0.1); margin-bottom: 14px;" />
            <div style="display: flex; gap: 8px;">
                <button type="button" on:click={() => { stopStoryCamera(); showCreateStoryModal = false; }} style="flex: 1; padding: 12px; border-radius: 100px; background: #28292D; color: #E3E3E3; border: none; font-weight: 600;">Отмена</button>
                <button type="button" on:click={publishHydraStory} style="flex: 1; padding: 12px; border-radius: 100px; background: #8AB4F8; color: #062E6F; font-weight: 700; border: none;">Опубликовать</button>
            </div>
        </div>
    </div>
{/if}

{#if activeStoryModal}
    <div style="position: fixed; inset: 0; background: #0B0D12; z-index: 99999; display: flex; flex-direction: column; justify-content: space-between; overflow: hidden;">
        <!-- Динамический размытый атмосферный фон под цвет видео/фото -->
        {#if activeStoryModal.video}
            <video autoplay loop muted playsinline src={activeStoryModal.video} style="position: absolute; inset: -30px; width: calc(100% + 60px); height: calc(100% + 60px); object-fit: cover; filter: blur(38px) brightness(0.45) saturate(1.6); pointer-events: none; z-index: 0;"></video>
        {:else if activeStoryModal.image}
            <img src={activeStoryModal.image} alt="" style="position: absolute; inset: -30px; width: calc(100% + 60px); height: calc(100% + 60px); object-fit: cover; filter: blur(38px) brightness(0.45) saturate(1.6); pointer-events: none; z-index: 0;" />
        {/if}

        <!-- Шапка Сторис с кнопками Удалить и Закрыть -->
        <div style="position: relative; z-index: 2; display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; padding-top: calc(16px + env(safe-area-inset-top, 12px)); background: linear-gradient(to bottom, rgba(0,0,0,0.65), transparent);">
            <div>
                <div style="font-weight: 700; color: #fff; font-size: 16px;">{activeStoryModal.author}</div>
                <div style="font-size: 12px; color: #60A5FA;">⏱ 24ч • {storyPaused ? '⏸ Пауза (нажмите для пуска)' : '▶️ Идёт показ'}</div>
            </div>
            <div style="display: flex; gap: 10px; align-items: center;">
                <button type="button" on:click|stopPropagation={() => deleteCurrentStory(activeStoryModal.id)} style="background: rgba(239,68,68,0.25); color: #FCA5A5; border: 1px solid rgba(239,68,68,0.4); border-radius: 100px; padding: 8px 14px; font-size: 12px; font-weight: 700; cursor: pointer;">
                    🗑️ Удалить
                </button>
                <button type="button" on:click={() => activeStoryModal = null} style="background: rgba(255,255,255,0.15); color: #fff; border: none; border-radius: 50%; width: 38px; height: 38px; font-size: 16px; cursor: pointer;">✕</button>
            </div>
        </div>

        <!-- Контент Сторис: 1 нажатие = Пауза / Продолжить (без панели плеера!) -->
        <div style="position: relative; z-index: 1; flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: {storyIsVertical ? '0' : '16px'}; overflow: hidden;" on:click|stopPropagation={(e) => {
            const vid = e.currentTarget.querySelector('video');
            if (vid) {
                if (vid.paused) { vid.play(); storyPaused = false; }
                else { vid.pause(); storyPaused = true; }
            }
        }}>
            {#if activeStoryModal.video}
                <video
                    autoplay
                    loop
                    playsinline
                    src={activeStoryModal.video}
                    on:loadedmetadata={(e) => {
                        const v = e.currentTarget;
                        storyIsVertical = (v.videoHeight > v.videoWidth * 1.15);
                    }}
                    style="width: {storyIsVertical ? '100%' : 'auto'}; height: {storyIsVertical ? '100%' : 'auto'}; max-width: 100%; max-height: {storyIsVertical ? '100%' : '70vh'}; object-fit: {storyIsVertical ? 'cover' : 'contain'}; border-radius: {storyIsVertical ? '0' : '22px'}; box-shadow: 0 12px 40px rgba(0,0,0,0.6); cursor: pointer;"
                ></video>
            {:else if activeStoryModal.image}
                <img src={activeStoryModal.image} alt="story" style="max-width: 100%; max-height: 72vh; object-fit: contain; border-radius: 22px; box-shadow: 0 12px 40px rgba(0,0,0,0.6);" />
            {/if}
            {#if activeStoryModal.text}
                <div style="position: absolute; bottom: 20px; left: 20px; right: 20px; text-align: center; background: rgba(10,12,18,0.75); backdrop-filter: blur(10px); padding: 12px 18px; border-radius: 16px; font-size: 17px; color: #fff; font-weight: 600;">{activeStoryModal.text}</div>
            {/if}
        </div>

        <div style="position: relative; z-index: 2; margin: 12px 16px calc(12px + env(safe-area-inset-bottom, 8px)); background: rgba(26,28,32,0.85); backdrop-filter: blur(14px); border-radius: 20px; padding: 12px 16px; font-size: 12px; color: #E5E7EB;">
            <div>👁 Открытых просмотров: {activeStoryModal.openViews?.length || 0} {activeStoryModal.openViews?.length ? '(' + activeStoryModal.openViews.join(', ') + ')' : ''}</div>
            <div style="color: #60A5FA; margin-top: 4px;">🕵️ Анонимных просмотров (Невидимка): {activeStoryModal.anonViews || 0}</div>
        </div>
    </div>
{/if}


{#if showNearbyWifiModal}
    <div style="position: fixed; inset: 0; background: rgba(10,11,16,0.92); z-index: 99999; display: flex; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: #1A1C20; border: 1px solid rgba(59,130,246,0.3); border-radius: 24px; padding: 20px; width: 100%; max-width: 380px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px;">
                <h3 style="margin: 0; color: #E5E7EB; font-size: 17px;">📡 Люди рядом (только по Wi-Fi)</h3>
                <button type="button" on:click={() => showNearbyWifiModal = false} style="background: #28292D; color: #fff; border: none; border-radius: 50%; width: 32px; height: 32px;">✕</button>
            </div>
            <p style="font-size: 12px; color: #9CA3AF; margin: 0 0 14px;">Показывает только @никнеймы пользователей Hydra в вашей сети Wi-Fi без раскрытия крипто-ключей.</p>
            {#if !myWifiSubnetIp}
                <div style="padding: 16px; border-radius: 16px; background: rgba(239,68,68,0.12); color: #FCA5A5; font-size: 13px; text-align: center;">⚠️ Подключитесь к Wi-Fi для обнаружения людей рядом.</div>
            {:else}
                <div style="padding: 10px 12px; border-radius: 12px; background: #121316; font-size: 12px; color: #60A5FA; margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center;">
                    <span>🟢 Ваш Wi-Fi: <b>{myWifiSubnetIp}</b></span>
                    <button type="button" on:click={scanNearbyWifiPeers} style="background: #1E3A8A; color: #fff; border: none; border-radius: 100px; padding: 4px 10px; font-size: 11px;">🔄 Поиск</button>
                </div>
                {#if isScanningNearby}
                    <div style="text-align: center; padding: 20px; color: #9CA3AF; font-size: 13px;">⏳ Сканирование локальной сети...</div>
                {:else if nearbyWifiUsers.length === 0}
                    <div style="text-align: center; padding: 20px; color: #9CA3AF; font-size: 13px;">Никого не найдено в этой сети Wi-Fi.</div>
                {:else}
                    <div style="display: flex; flex-direction: column; gap: 8px; max-height: 220px; overflow-y: auto;">
                        {#each nearbyWifiUsers as u}
                            <div style="display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; background: #121316; border-radius: 14px;">
                                <div>
                                    <div style="font-weight: 700; color: #E5E7EB; font-size: 14px;">{u.nick}</div>
                                    <div style="font-size: 11px; color: #4CAF50;">📡 В вашей сети Wi-Fi</div>
                                </div>
                                <button type="button" on:click={() => connectToNearbyUser(u)} style="background: #1D4ED8; color: #fff; border: none; border-radius: 100px; padding: 7px 14px; font-size: 12px; font-weight: 700;">Написать</button>
                            </div>
                        {/each}
                    </div>
                {/if}
            {/if}
        </div>
    </div>
{/if}

<style>
    /* === Desktop Styles === */
    .folders-rail {
        width: 72px;
        background: var(--bg-tertiary, #141619);
        display: flex;
        flex-direction: column;
        align-items: center;
        padding-top: 10px;
        flex-shrink: 0;
        z-index: 100;
        border-right: 1px solid var(--border, rgba(255,255,255,0.05));
    }

    .rail-button {
        width: 48px;
        height: 48px;
        border-radius: 12px;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        margin-bottom: 20px;
        color: var(--text-secondary);
        transition: all 0.2s;
        position: relative;
    }
    .rail-button:hover, .rail-button.active {
        background: rgba(255,255,255,0.1);
        color: white;
    }

    .unread-badge {
        position: absolute;
        top: -4px;
        right: -4px;
        background: #ff4757;
        color: white;
        font-size: 10px;
        font-weight: 700;
        min-width: 18px;
        height: 18px;
        border-radius: 9px;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0 4px;
        box-shadow: 0 2px 8px rgba(255, 71, 87, 0.4);
        animation: badge-pulse 2s ease-in-out infinite;
    }

    .mobile-badge {
        top: 2px;
        right: 2px;
        font-size: 9px;
        min-width: 16px;
        height: 16px;
        border-radius: 8px;
    }

    @keyframes badge-pulse {
        0%, 100% { transform: scale(1); }
        50% { transform: scale(1.1); }
    }

    .folder-item {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        width: 64px;
        height: 64px;
        margin-bottom: 8px;
        cursor: pointer;
        border-radius: 12px;
        transition: background 0.2s;
        gap: 2px;
    }
    .folder-item:hover, .folder-item.active {
        background: rgba(255, 255, 255, 0.1);
    }
    .folder-icon { font-size: 24px; }
    .folder-name {
        font-size: 10px; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: center; color: var(--text-secondary);
    }

    .folder-unread-badge {
        position: absolute;
        top: 4px;
        right: 4px;
        background: #ff4757;
        color: white;
        font-size: 9px;
        font-weight: 700;
        min-width: 16px;
        height: 16px;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0 4px;
        box-shadow: 0 2px 6px rgba(255, 71, 87, 0.4);
    }

    .sidebar { background: var(--bg-secondary, #1A1C20); border-right: 1px solid var(--border); overflow: hidden; }
    .search-input-wrapper { background: var(--bg-input, #121316); border-radius: 18px; padding: 8px 12px; display: flex; align-items: center; gap: 8px; }
    .search-input-wrapper input { background: transparent; border: none; color: white; width: 100%; font-size: 14px; outline: none; }
    
    .sidebar-actions { padding: 0 10px 10px; }
    .btn-primary { 
        width: 100%; display: flex; align-items: center; justify-content: center; gap: 8px; padding: 10px; background: var(--accent, #1D4ED8); color: white; border: none; border-radius: 14px; cursor: pointer; font-weight: 600; transition: all 0.2s;
    }
    .btn-primary:hover { transform: translateY(-1px); filter: brightness(1.1); box-shadow: 0 4px 12px rgba(29, 78, 216, 0.3); }

    .network-status {
        padding: 10px 16px; display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--text-primary); margin: 0 10px 10px; border-radius: 8px;
    }
    .status-dot { width: 8px; height: 8px; border-radius: 50%; }
    .animate-pulse { animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite; }
    @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .5; } }

    .contacts-list { flex: 1; overflow-y: auto; }
    .contact-item {
        padding: 12px 16px; cursor: pointer; display: flex; gap: 12px; align-items: center; transition: background 0.2s; border-radius: 12px; margin: 2px 8px;
    }
    .contact-item:hover { background: rgba(255, 255, 255, 0.05); }
    .contact-item.selected { background: rgba(30, 64, 175, 0.15); }

    .contact-avatar { width: 48px; height: 48px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: white; font-weight: bold; flex-shrink: 0; overflow: hidden; }
    .contact-avatar img { width: 100%; height: 100%; object-fit: cover; }

    .contact-info { flex: 1; min-width: 0; }
    .contact-name { font-weight: 600; font-size: 15px; color: white; margin-bottom: 2px; }
    .contact-last { font-size: 13px; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

    .contact-unread-badge {
        background: #ff4757;
        color: white;
        font-size: 11px;
        font-weight: 700;
        min-width: 20px;
        height: 20px;
        border-radius: 10px;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0 6px;
        flex-shrink: 0;
        box-shadow: 0 2px 6px rgba(255, 71, 87, 0.3);
    }

    .no-contacts { padding: 40px 20px; text-align: center; color: var(--text-secondary); }
    .no-contacts-icon { margin-bottom: 16px; opacity: 0.5; display: flex; justify-content: center; }
    .hint { font-size: 12px; margin-top: 4px; opacity: 0.7; }

    .my-destination { padding: 10px; border-top: 1px solid var(--border); }
    .btn-copy {
        width: 100%; display: flex; align-items: center; justify-content: center; gap: 8px; padding: 10px; background: rgba(255, 255, 255, 0.05); color: var(--text-primary); border: 1px solid rgba(255, 255, 255, 0.05); border-radius: 12px; cursor: pointer; font-size: 13px; transition: background 0.2s;
    }
    .btn-copy:hover { background: rgba(255, 255, 255, 0.1); }

    .resizer { width: 4px; cursor: col-resize; background: transparent; transition: background 0.2s; flex-shrink: 0; }
    .resizer:hover, .resizer.resizing { background: var(--accent); }
    
    .icon-svg { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; }
    .icon-svg :global(svg) { width: 100%; height: 100%; }
    .icon-svg-sm { width: 18px; height: 18px; display: flex; align-items: center; justify-content: center; }
    .icon-svg-sm :global(svg) { width: 100%; height: 100%; }

    /* === Mobile Styles === */
    .mobile-sidebar {
        width: 100%;
        height: 100%;
        display: flex;
        flex-direction: column;
        background: var(--bg-primary, #121316);
        overflow: hidden;
    }

    .mobile-header {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 12px 16px;
        padding-top: calc(12px + env(safe-area-inset-top));
        background: var(--bg-secondary, #1A1C20);
        border-bottom: 1px solid var(--border);
    }

    .mobile-menu-btn {
        background: transparent;
        border: none;
        color: var(--text-secondary);
        cursor: pointer;
        padding: 8px;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        transition: background 0.2s;
    }
    .mobile-menu-btn:hover { background: rgba(255,255,255,0.1); }

    .mobile-title {
        font-size: 20px;
        font-weight: 700;
        color: white;
        margin: 0;
        flex: 1;
        background: linear-gradient(135deg, #1D4ED8, #3B82F6);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
    }

    .mobile-network-dot {
        width: 10px;
        height: 10px;
        border-radius: 50%;
        flex-shrink: 0;
    }

    .mobile-search {
        padding: 8px 16px;
    }

    .mobile-folders-bar {
        display: flex;
        gap: 8px;
        padding: 4px 16px 12px;
        overflow-x: auto;
        scrollbar-width: none;
        -webkit-overflow-scrolling: touch;
    }
    .mobile-folders-bar::-webkit-scrollbar { display: none; }

    .mobile-folder-chip {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 6px 14px;
        border-radius: 20px;
        border: 1px solid var(--border);
        background: var(--bg-secondary, #1A1C20);
        color: var(--text-secondary);
        font-size: 13px;
        white-space: nowrap;
        cursor: pointer;
        transition: all 0.2s;
        flex-shrink: 0;
    }
    .mobile-folder-chip.active {
        background: var(--accent, #1D4ED8);
        color: white;
        border-color: var(--accent, #1D4ED8);
        box-shadow: 0 2px 8px rgba(29, 78, 216, 0.3);
    }
    .mobile-folder-chip:hover { background: rgba(255,255,255,0.1); }

    .mobile-folder-icon {
        font-size: 14px;
        display: flex;
        align-items: center;
    }
    .mobile-folder-icon :global(svg) { width: 14px; height: 14px; }

    .mobile-folder-badge {
        position: absolute;
        top: -6px;
        right: -6px;
        background: #ff4757;
        color: white;
        font-size: 9px;
        font-weight: 700;
        min-width: 16px;
        height: 16px;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0 4px;
        box-shadow: 0 2px 6px rgba(255, 71, 87, 0.4);
    }

    .mobile-contacts {
        flex: 1;
        overflow-y: auto;
        -webkit-overflow-scrolling: touch;
    }

    .mobile-bottom-bar {
        display: flex;
        gap: 8px;
        padding: 10px 16px;
        padding-bottom: calc(10px + env(safe-area-inset-bottom, 0px));
        background: var(--bg-secondary, #1A1C20);
        border-top: 1px solid var(--border);
    }

    .mobile-action-btn {
        flex: 1;
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 8px;
        padding: 12px;
        border-radius: 14px;
        border: none;
        cursor: pointer;
        font-size: 13px;
        font-weight: 600;
        transition: all 0.2s;
        color: white;
    }
    .mobile-action-btn:first-child {
        background: var(--accent, #1D4ED8);
    }
    .mobile-action-btn:first-child:hover {
        filter: brightness(1.1);
    }
    .mobile-action-btn:last-child {
        background: rgba(255,255,255,0.08);
        color: var(--text-primary);
    }
    .mobile-action-btn:last-child:hover {
        background: rgba(255,255,255,0.12);
    }

    .contact-header { display: flex; justify-content: space-between; align-items: center; }
    .pin-icon { width: 12px; height: 12px; margin-left: 4px; color: var(--accent); transform: rotate(45deg); display: inline-block; vertical-align: middle; }
    .pin-icon :global(svg) { width: 100%; height: 100%; }
    .contact-time { font-size: 11px; color: var(--text-secondary); opacity: 0.7; }

    .mobile-sidebar, .contacts-list, .mobile-contacts { max-width: 100vw !important; overflow-x: hidden !important; box-sizing: border-box !important; }
    .contact-item { max-width: calc(100% - 16px) !important; overflow: hidden !important; box-sizing: border-box !important; }
    .contact-name { white-space: nowrap !important; overflow: hidden !important; text-overflow: ellipsis !important; max-width: 210px !important; }
    

    video::-webkit-media-controls,
    video::-webkit-media-controls-enclosure,
    video::-webkit-media-controls-panel,
    video::-webkit-media-controls-overlay-play-button {
        display: none !important;
        opacity: 0 !important;
    }
    
</style>
