<script>
    import * as AppActions from '../../wailsjs/go/main/App.js';
    import { EventsOn } from '../../wailsjs/runtime/runtime.js';
    import { Icons } from '../Icons.js';
    import { getInitials, formatTime, parseMarkdown, getAvatarGradient, getUnifiedPresence } from '../utils.js';
    import { fade, fly } from 'svelte/transition';
    import { onMount, tick, createEventDispatcher } from 'svelte';

    const dispatch = createEventDispatcher();

    export let selectedContact;
    export let messages = [];
    export let newMessage;
    export let selectedFiles = [];
    export let filePreviews = {};
    export let editingMessageId = null;
    export let editMessageContent = '';
    export let isCompressed = false;
    export let replyingTo = null;
    export let isMobile = false;
    export let onBack = null;
    export let onCancelReply;

    export let onSendMessage;
    export let canLoadMore = false;
    export let onLoadMore = null;
    export let onKeyPress;
    export let onPaste;
    export let onSelectFiles;
    export let onRemoveFile;
    export let onShowMessageMenu;
    export let onAcceptTransfer;
    export let onDeclineTransfer;
    export let onOpenContactProfile;
    export let onSaveEditMessage;
    export let onCancelEdit;
    export let onOpenFile;
    export let onSaveFile;
    export let onPreviewImage;
    export let startLoadingImage; // Fix: Add missing prop
    export let isLoading = false;
    export let previewImage; // Fix: Add missing prop
    export let onJumpToMessage = null; // Fix: Add missing prop

    let textarea;
    let touchStartX = 0;
    let touchMoveX = 0;
    let swipedMsgId = null;
    let swipeThreshold = 60;
    
    // Image loading state
    let imagesLoading = false;
    let pendingImages = 0;
    let loadedImages = 0;
    let chatReady = true;
    let initialScrollDone = false;
    let currentContactId = null;


    function handleTouchStart(e, msgId) {
        if (msgId === swipedMsgId) return;
        touchStartX = e.touches[0].clientX;
        swipedMsgId = msgId;
    }

    function handleTouchMove(e) {
        if (!swipedMsgId) return;
        const currentX = e.touches[0].clientX;
        const diff = touchStartX - currentX;
        if (diff > 0) { // Swiping left
            touchMoveX = Math.min(diff, 100); // Limit visual swipe
        }
    }

    function handleTouchEnd(msg) {
        if (swipedMsgId === msg.ID && touchMoveX >= swipeThreshold) {
            handleReply(msg);
            if (navigator.vibrate) navigator.vibrate(50);
        }
        touchMoveX = 0;
        swipedMsgId = null;
    }

    function handleReply(msg) {
        replyingTo = msg;
        if (textarea) textarea.focus();
    }

    function handleDoubleClick(msg) {
        if (!isMobile) {
            handleReply(msg);
        }
    }

    let showScrollButton = false;
    let resizeObserver;
    let containerRef;

    let isLoadingMore = false;


    function scrollToBottom(force = false) {
        // Guard against unnecessary scrolls
        if (!processScroll(force)) return;

        tick().then(() => {
            requestAnimationFrame(() => {
                if (containerRef) {
                     // If force is true, we scroll to bottom no matter what
                    if (force) {
                        containerRef.scrollTop = containerRef.scrollHeight;
                        return;
                    }
                    
                    // If content grew and we were ALREADY at bottom (or close), stay at bottom
                    const distanceToBottom = containerRef.scrollHeight - containerRef.scrollTop - containerRef.clientHeight;
                    // Relaxed threshold
                    if (distanceToBottom < 100) {
                         containerRef.scrollTop = containerRef.scrollHeight;
                    }
                }
            });
        });
    }

    function processScroll(force) {
        if (!containerRef) return false;
        if (!force && (!messages || messages.length === 0)) return false;
        return true;
    }

    let pollInterval;

    onMount(() => {
        if (containerRef) {
            containerRef.addEventListener('scroll', () => {
                 const distanceToBottom = containerRef.scrollHeight - containerRef.scrollTop - containerRef.clientHeight;
                 showScrollButton = distanceToBottom > 80;
            });
            scrollToBottom(true);
        }
    });

    // Handle Contact Change & Initialize Loading
    let lastMessageId = null;

    $: if (selectedContact && selectedContact.ID !== currentContactId) {
        currentContactId = selectedContact.ID;
        // Reset state ONLY for new chat
        chatReady = true; 
        initialScrollDone = false;
        imagesLoading = true;
        pendingImages = 0;
        loadedImages = 0;
        lastMessageId = null;
    }

    // Handle Messages Update & Auto-scroll
    $: if (messages && currentContactId && containerRef) {
        if (!chatReady) {
            // Initial Load Logic
            const images = (messages || []).flatMap(m => m.Attachments || []).filter(a => a.MimeType && a.MimeType.startsWith('image/'));
            
            if (images.length > 0) {
                 if (pendingImages === 0) {
                    pendingImages = images.length;
                    loadedImages = 0;
                 }
                 // If all images loaded (rare case of cache) or no pending
                 if (loadedImages >= pendingImages) {
                     finishLoading();
                 }
            } else {
                // No images, ready immediately
                finishLoading();
            }
        } else {
            // Already ready, handle normal new message scroll
            const currentLastMsg = messages.length > 0 ? messages[messages.length-1] : null;
            const isNewMessage = currentLastMsg && currentLastMsg.ID !== lastMessageId;
            
            if (isNewMessage) {
                lastMessageId = currentLastMsg.ID;
                
                const distanceToBottom = containerRef.scrollHeight - containerRef.scrollTop - containerRef.clientHeight;
                const wasNearBottom = distanceToBottom < 100;
                const isUserSender = currentLastMsg.IsOutgoing;

                if (wasNearBottom || isUserSender) {
                    tick().then(() => {
                        scrollToBottom(true); 
                    });
                }
            }
        }
    }

    function onImageLoad(e) {
        // If image is already complete (cached), handle immediately
        if (e && e.target && e.target.complete) {
             // Logic handled below
        }

        if (chatReady) {
            // Smart scroll after image load if near bottom
            if (containerRef) {
                const dist = containerRef.scrollHeight - containerRef.scrollTop - containerRef.clientHeight;
                if (dist < 150) scrollToBottom(true);
            }
            return;
        }
        loadedImages++;
        if (loadedImages >= pendingImages) {
            finishLoading();
        }
    }

    function finishLoading() {
        if (chatReady) return;
        
        // 1. Force scroll to bottom WHILE INVISIBLE
        scrollToBottom(true);
        
        // 2. Make visible after brief delay to allow layout to settle
        requestAnimationFrame(() => {
            chatReady = true;
            imagesLoading = false;
            
            if (messages && messages.length > 0) {
                 lastMessageId = messages[messages.length-1].ID;
            }
            
            // 3. Force scroll again just in case opacity change affected anything
            setTimeout(() => scrollToBottom(true), 10);
        });
    }
    
    // ...
    
    async function handleScroll(e) {
        if (!chatReady) return; // CRITICAL: Don't load history if initial scroll isn't done
        
        const container = e.target;
        const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
        showScrollButton = distanceToBottom > 50;
        
        // ... rest of logic
        if (container.scrollTop < 100 && canLoadMore && !isLoadingMore && onLoadMore) {
             // ...
             isLoadingMore = true;
             const oldHeight = container.scrollHeight;
             await onLoadMore();
             await tick();
             const newHeight = container.scrollHeight;
             container.scrollTop += (newHeight - oldHeight);
             isLoadingMore = false;
        }
    }

    // Auto-expand textarea
    function resizeTextarea() {
        if (!textarea) return;
        textarea.style.height = 'auto'; // Reset height to calculate scrollHeight
        textarea.style.height = textarea.scrollHeight + 'px';
    }

    // Watch newMessage to resize (e.g. when cleared or pasted)
    $: if (newMessage !== undefined) {
        tick().then(resizeTextarea);
    }

    // === ЭТАП 4: СМАЙЛИКИ, ГОЛОСОВЫЕ, ВИДЕО-КРУЖКИ И ТАЙМЕР ===
    let showEmojiPanel = false;

    // === СТУДИЯ ВИДЕО-КРУЖКА (ДО 2 МИНУТ + ВЫБОР КАМЕРЫ + КРУГЛЫЕ КНОПКИ ПУСК/СТОП + ОБРЕЗКА) ===
    let showCircleStudio = false;
    let circleFacingMode = 'user';
    let circleFilterMode = 'none';
    let circleDraftDataUrl = '';
    let circleTrimStart = 0;
    let circleTrimEnd = 120;
    let circleTotalDuration = 15;
    let editingExistingCircleId = null;

    async function openCircleStudio(switchCam = false) {
        if (switchCam) {
            circleFacingMode = circleFacingMode === 'user' ? 'environment' : 'user';
        } else {
            showCircleStudio = true;
            circleDraftDataUrl = '';
            editingExistingCircleId = null;
            isRecordingMedia = false;
        }
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true },
                video: { facingMode: circleFacingMode, width: { ideal: 640 }, height: { ideal: 640 }, frameRate: { ideal: 24 } }
            });
            activeMediaStream = stream;
            setTimeout(() => {
                if (liveCirclePreviewEl && activeMediaStream) {
                    liveCirclePreviewEl.srcObject = activeMediaStream;
                    liveCirclePreviewEl.play().catch(() => {});
                }
            }, 80);
        } catch (e) {
            alert('Не удалось открыть камеру: ' + e);
        }
    }

    function startCircleStudioRecording() {
        if (!activeMediaStream) return;
        recordedChunks = [];
        recordMode = 'video';
        try {
            mediaRecorderInstance = new MediaRecorder(activeMediaStream, { videoBitsPerSecond: 650000, audioBitsPerSecond: 32000 });
        } catch (e) {
            mediaRecorderInstance = new MediaRecorder(activeMediaStream);
        }
        mediaRecorderInstance.ondataavailable = e => { if (e.data && e.data.size > 0) recordedChunks.push(e.data); };
        mediaRecorderInstance.onstop = () => {
            const blob = new Blob(recordedChunks, { type: 'video/webm' });
            const reader = new FileReader();
            reader.onloadend = () => {
                circleDraftDataUrl = reader.result;
                circleTotalDuration = Math.max(2, recordingSeconds || 10);
                circleTrimStart = 0;
                circleTrimEnd = circleTotalDuration;
            };
            reader.readAsDataURL(blob);
        };
        recordingSeconds = 0;
        clearInterval(recordingInterval);
        recordingInterval = setInterval(() => {
            recordingSeconds++;
            if (recordingSeconds >= 120 && isRecordingMedia) stopCircleStudioRecording();
        }, 1000);
        mediaRecorderInstance.start(200);
        isRecordingMedia = true;
    }

    function stopCircleStudioRecording() {
        clearInterval(recordingInterval);
        if (mediaRecorderInstance && isRecordingMedia) {
            mediaRecorderInstance.stop();
        }
        isRecordingMedia = false;
        if (activeMediaStream) {
            activeMediaStream.getTracks().forEach(t => t.stop());
            activeMediaStream = null;
        }
    }

    async function sendEditedCircle() {
        if (!circleDraftDataUrl || !selectedContact) return;
        const cleanBase = circleDraftDataUrl.split('#t=')[0];
        const trimSuffix = (circleTrimStart > 0 || circleTrimEnd < circleTotalDuration)
            ? `#t=${circleTrimStart},${Math.max(circleTrimStart + 1, circleTrimEnd)}`
            : '#t=0.1';
        const finalPayload = `[HYDRA_CIRCLE]:${cleanBase}${trimSuffix}`;
        if (editingExistingCircleId) {
            await AppActions.EditMessage(editingExistingCircleId, finalPayload);
            editingExistingCircleId = null;
        } else {
            const targetId = selectedContact.ID || selectedContact.ChatID;
            await AppActions.SendText(targetId, finalPayload, '');
        }
        circleDraftDataUrl = '';
        showCircleStudio = false;
        dispatch('refresh');
    }

    function closeCircleStudio() {
        stopCircleStudioRecording();
        circleDraftDataUrl = '';
        editingExistingCircleId = null;
        showCircleStudio = false;
    }

    if (typeof window !== 'undefined') {
        window.__openExistingCircleTrimmer = (msgObj) => {
            if (!msgObj || !msgObj.Content) return;
            const rawUrl = msgObj.Content.slice('[HYDRA_CIRCLE]:'.length).split('#t=')[0];
            editingExistingCircleId = msgObj.ID;
            circleDraftDataUrl = rawUrl;
            circleTrimStart = 0;
            circleTrimEnd = 30;
            circleTotalDuration = 30;
            showCircleStudio = true;
        };
    }
    

    // Режим правой круглой кнопки при пустом инпуте ('audio' = 🎤, 'video' = 📷) + подсказка со Скриншота 3
    let mediaButtonMode = 'audio';
    let showModeHintBanner = false;
    let modeHintTimeout = null;
    let pressHoldTimer = null;
    let isHoldTriggered = false;

    function handleMediaBtnTap() {
        if (isHoldTriggered) { isHoldTriggered = false; return; }
        if (isRecordingMedia) {
            toggleVoiceOrCircle(recordMode);
            return;
        }
        mediaButtonMode = mediaButtonMode === 'audio' ? 'video' : 'audio';
        showModeHintBanner = true;
        clearTimeout(modeHintTimeout);
        modeHintTimeout = setTimeout(() => showModeHintBanner = false, 3200);
    }

    function handleMediaBtnHoldStart() {
        isHoldTriggered = false;
        clearTimeout(pressHoldTimer);
        pressHoldTimer = setTimeout(() => {
            isHoldTriggered = true;
            showModeHintBanner = false;
            if (navigator.vibrate) navigator.vibrate(40);
            if (mediaButtonMode === 'video') { openCircleStudio(false); } else { toggleVoiceOrCircle('audio'); }
        }, 380);
    }

    function handleMediaBtnHoldEnd() {
        clearTimeout(pressHoldTimer);
    }

    // Кастомный плеер голосовых сообщений (Waveform + Play/Pause + 1x/1.5x/2x)
    let playingVoiceId = null;
    let voicePlaybackRate = 1;
    let voiceAudioElements = {};

    function togglePlayCustomVoice(msgId, srcUrl) {
        let audio = voiceAudioElements[msgId];
        if (!audio) {
            audio = new Audio(srcUrl);
            voiceAudioElements[msgId] = audio;
            audio.onended = () => { if (playingVoiceId === msgId) playingVoiceId = null; };
        }
        audio.playbackRate = voicePlaybackRate;
        if (playingVoiceId === msgId && !audio.paused) {
            audio.pause();
            playingVoiceId = null;
        } else {
            Object.values(voiceAudioElements).forEach(a => { try { a.pause(); } catch(e){} });
            audio.play().catch(() => {});
            playingVoiceId = msgId;
        }
    }

    function cycleVoiceSpeed(msgId) {
        voicePlaybackRate = voicePlaybackRate === 1 ? 1.5 : (voicePlaybackRate === 1.5 ? 2 : 1);
        if (voiceAudioElements[msgId]) {
            voiceAudioElements[msgId].playbackRate = voicePlaybackRate;
        }
    }

    // Реакции и закреплённое сообщение в чате
    let messageReactions = JSON.parse(localStorage.getItem('hydra_msg_reactions') || '{}');
    let pinnedChatMsg = null;
    $: if (selectedContact) {
        try {
            pinnedChatMsg = JSON.parse(localStorage.getItem('hydra_pinned_msg_' + selectedContact.ID) || 'null');
        } catch(e) { pinnedChatMsg = null; }
    }

    if (typeof window !== 'undefined') {
        window.__setHydraReaction = (msgId, emoji, sendRemote = false) => {
            messageReactions[msgId] = emoji;
            messageReactions = { ...messageReactions };
            localStorage.setItem('hydra_msg_reactions', JSON.stringify(messageReactions));
            if (sendRemote && selectedContact && !selectedContact.IsFavorites) {
                AppActions.SendText(selectedContact.ID, `[HYDRA_REACT]:${msgId}|${emoji}`, '').catch(() => {});
            }
        };
        window.__pinHydraMessageInChat = (msgObj) => {
            if (!selectedContact || !msgObj) return;
            pinnedChatMsg = { ID: msgObj.ID, Content: msgObj.Content };
            localStorage.setItem('hydra_pinned_msg_' + selectedContact.ID, JSON.stringify(pinnedChatMsg));
        };
        window.__triggerHydraCallFromProfile = () => startHydraCall();
        window.__onHydraCallNotifAction = (senderNick, action) => {
            if (action === 'accept_call') {
                acceptIncomingHydraCall();
            } else if (action === 'decline_call') {
                endHydraCall(true);
            }
        };
        window.__triggerHydraCircleFromProfile = () => openCircleStudio(false);
        window.__cycleChatTtlFromProfile = () => cycleDisappearingTimer();
    }

    
    EventsOn('remote_group_members', (raw) => {
        const [gId, membersCsv] = String(raw || '').split('|');
        if (gId && membersCsv) {
            const arr = membersCsv.split(',').map(s => s.trim()).filter(Boolean);
            localStorage.setItem('hydra_group_members_' + gId, JSON.stringify(arr));
            if (selectedContact && selectedContact.ID === gId) {
                currentGroupMembers = arr;
            }
        }
    });
    
    EventsOn('remote_reaction', (raw) => {
        const [mId, em] = String(raw || '').split('|');
        if (mId && em) {
            messageReactions[mId] = em;
            messageReactions = { ...messageReactions };
            localStorage.setItem('hydra_msg_reactions', JSON.stringify(messageReactions));
        }
    });
    
    let isRecordingMedia = false;
    let liveCirclePreviewEl = null;
    let activeMediaStream = null;
    let mediaRecorderInstance = null;
    let recordedChunks = [];
    let recordMode = 'audio'; // 'audio' | 'video'
    let disappearingTimer = 0; // 0 = выкл, 60 = 1 мин, 3600 = 1 час

    const hydraEmojis = ['😀','😂','🔥','❤️','👍','🛡️','🚀','🎉','😎','🤝','⚡','🕵️','💎','👀','✅','❌'];

    function insertHydraEmoji(em) {
        newMessage = (newMessage || '') + em;
        showEmojiPanel = false;
    }

    
    // Автоматическое удаление сообщений по таймеру (⏱ 1м / 1ч)
    let ttlCleanupTimer = null;
    $: if (selectedContact) {
        const savedTtl = localStorage.getItem('hydra_ttl_' + selectedContact.ID);
        if (savedTtl !== null) disappearingTimer = Number(savedTtl);
    }
    function toggleAndSaveDisappearingTimer() {
        if (disappearingTimer === 0) disappearingTimer = 60;
        else if (disappearingTimer === 60) disappearingTimer = 3600;
        else disappearingTimer = 0;
        if (selectedContact) {
            localStorage.setItem('hydra_ttl_' + selectedContact.ID, String(disappearingTimer));
        }
    }
    if (typeof window !== 'undefined') {
        setInterval(async () => {
            if (!disappearingTimer || disappearingTimer <= 0 || !messages || messages.length === 0) return;
            const nowMs = Date.now();
            const maxAgeMs = disappearingTimer * 1000;
            let deletedAny = false;
            for (const m of messages) {
                if (m.Timestamp && (nowMs - m.Timestamp >= maxAgeMs)) {
                    await AppActions.DeleteMessage(m.ID).catch(() => {});
                    deletedAny = true;
                }
            }
            if (deletedAny) {
                messages = messages.filter(m => !(m.Timestamp && (nowMs - m.Timestamp >= maxAgeMs)));
                dispatch('refresh');
            }
        }, 3000);
    }
    
    function cycleDisappearingTimer() {
        toggleAndSaveDisappearingTimer();
    }

    let recordingSeconds = 0;
    let recordingInterval = null;
    let activeCallModal = false;

    let isCallMuted = false;
    let isRecordingCall = false;
    let callRecorder = null;
    let callRecordedChunks = [];

    function toggleCallMute() {
        isCallMuted = !isCallMuted;
        if (window.__hydraCallStream) {
            window.__hydraCallStream.getAudioTracks().forEach(t => { t.enabled = !isCallMuted; });
        }
    }

    function toggleCallRecording() {
        if (isRecordingCall && callRecorder) {
            callRecorder.stop();
            isRecordingCall = false;
            return;
        }
        try {
            const s = window.__hydraCallStream;
            if (!s) return alert('Микрофон ещё не активен');
            callRecordedChunks = [];
            callRecorder = new MediaRecorder(s);
            callRecorder.ondataavailable = e => { if (e.data && e.data.size > 0) callRecordedChunks.push(e.data); };
            callRecorder.onstop = async () => {
                const blob = new Blob(callRecordedChunks, { type: 'audio/webm' });
                const reader = new FileReader();
                reader.onloadend = async () => {
                    await saveMediaDataUrlToGallery(reader.result, 'hydra_call_rec');
                    alert('✅ Запись разговора сохранена в папку Download/Hydra!');
                };
                reader.readAsDataURL(blob);
            };
            callRecorder.start(250);
            isRecordingCall = true;
        } catch (e) {
            alert('Ошибка записи: ' + e);
        }
    }
    
    let incomingCallOffer = null;
    let callConnected = false;
    let callStatusText = 'Соединение по защищённому WebRTC-каналу...';
    let hydraPeerConn = null;
    let remoteAudioEl = null;
    let callRingTimeout = null;

    // Зум кружка при удержании пальца 1 сек
    let zoomedCircleMsgId = null;
    let circleHoldTimer = null;
    function startCircleHold(msgId) {
        clearTimeout(circleHoldTimer);
        circleHoldTimer = setTimeout(() => {
            zoomedCircleMsgId = msgId;
            if (navigator.vibrate) navigator.vibrate(35);
        }, 750);
    }
    function endCircleHold() {
        clearTimeout(circleHoldTimer);
        zoomedCircleMsgId = null;
    }

    // Выбор участников для группы из существующих контактов
    
    let currentGroupMembers = ['Вы (Создатель)'];
    let currentGroupHideMembers = false;
    $: if (selectedContact) {
        try {
            currentGroupMembers = JSON.parse(localStorage.getItem('hydra_group_members_' + selectedContact.ID) || '["Вы (Создатель)"]');
            currentGroupHideMembers = localStorage.getItem('hydra_group_hide_members_' + selectedContact.ID) === '1';
        } catch (e) { currentGroupMembers = ['Вы (Создатель)']; }
    }
    
    let allExistingContactsForGroup = [];
    async function openGroupManagementModal() {
        showChatHeaderMenu = false;
        showRoomSettingsModal = true;
        try {
            const list = await AppActions.GetContacts();
            allExistingContactsForGroup = (list || []).filter(c => c.ID !== selectedContact?.ID && !String(c.Nickname || '').startsWith('👥') && !String(c.Nickname || '').startsWith('📢'));
        } catch (e) { allExistingContactsForGroup = []; }
    }
    async function inviteContactToGroup(targetContact) {
        if (!selectedContact || !targetContact) return;
        if (!currentGroupMembers.includes(targetContact.Nickname)) {
            currentGroupMembers = [...currentGroupMembers, targetContact.Nickname];
            localStorage.setItem('hydra_group_members_' + selectedContact.ID, JSON.stringify(currentGroupMembers));
        }
        const membersStr = currentGroupMembers.join(', ');
        const invitePayload = `[HYDRA_GROUP_INVITE]:${selectedContact.Nickname}|${selectedContact.I2PAddress}|${membersStr}`;
        try {
            await AppActions.SendText(targetContact.ID, invitePayload, '');
            alert(`✅ Пользователь ${targetContact.Nickname} добавлен в «${selectedContact.Nickname}»!`);
        } catch (e) {
            alert('Ошибка отправки приглашения: ' + e);
        }
    }

    // Отправка видео с обрезкой и выбором качества (Ролик с плеером ИЛИ Исходный файл)
    let showVideoTrimModal = false;
    let videoFileToTrim = null;
    let videoTrimQuality = 'compressed'; // 'compressed' | 'original'
    let videoTrimMaxSec = 30;

    function handleCustomVideoPick(e) {
        const file = e.target.files?.[0];
        if (!file) return;
        videoFileToTrim = file;
        showVideoTrimModal = true;
    }

    async function sendTrimmedOrQualityVideo() {
        if (!videoFileToTrim || !selectedContact) return;
        showVideoTrimModal = false;
        const reader = new FileReader();
        reader.onload = async () => {
            const b64 = reader.result;
            if (videoTrimQuality === 'original') {
                const tmp = await AppActions.SaveTempImage(b64, videoFileToTrim.name || 'video.mp4').catch(() => '');
                if (tmp) await AppActions.SendFileMessage(selectedContact.ID, '', '', [tmp], true);
            } else {
                await AppActions.SendText(selectedContact.ID, `[HYDRA_VIDEO]:${b64}`, '');
            }
            videoFileToTrim = null;
            dispatch('refresh');
        };
        reader.readAsDataURL(videoFileToTrim);
    }

    // Настоящий WebRTC P2P аудиовызов через VPS релей
    async function startHydraCall() {
        if (!selectedContact) return;
        activeCallModal = true;
        callConnected = false;
        callStatusText = '🔔 Вызов ' + (selectedContact.Nickname || '') + '... Ожидание ответа';
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            window.__hydraCallStream = stream;
            hydraPeerConn = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
            stream.getTracks().forEach(t => hydraPeerConn.addTrack(t, stream));
            hydraPeerConn.ontrack = (ev) => {
                if (remoteAudioEl && ev.streams[0]) {
                    remoteAudioEl.srcObject = ev.streams[0];
                    remoteAudioEl.play().catch(() => {});
                }
            };
            const offer = await hydraPeerConn.createOffer();
            await hydraPeerConn.setLocalDescription(offer);
            await new Promise(r => setTimeout(r, 600));
            const sdpJson = JSON.stringify(hydraPeerConn.localDescription);
            await AppActions.SendText(selectedContact.ID, '[HYDRA_CALL_RING]:' + btoa(unescape(encodeURIComponent(sdpJson))), '');
            dispatch('refresh');

            clearTimeout(callRingTimeout);
            callRingTimeout = setTimeout(async () => {
                if (activeCallModal && !callConnected) {
                    await AppActions.SendText(selectedContact.ID, '[HYDRA_CALL_MISSED]:Абонент не ответил', '');
                    endHydraCall(false);
                    dispatch('refresh');
                }
            }, 28000);
        } catch (err) {
            callStatusText = '❌ Ошибка микрофона/вызова: ' + err;
        }
    }

    async function acceptIncomingHydraCall() {
        if (!incomingCallOffer || !selectedContact) return;
        try {
            callStatusText = '🔐 Подключение защищённого аудиопотока...';
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            window.__hydraCallStream = stream;
            hydraPeerConn = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
            stream.getTracks().forEach(t => hydraPeerConn.addTrack(t, stream));
            hydraPeerConn.ontrack = (ev) => {
                if (remoteAudioEl && ev.streams[0]) {
                    remoteAudioEl.srcObject = ev.streams[0];
                    remoteAudioEl.play().catch(() => {});
                }
            };
            const remoteDesc = JSON.parse(decodeURIComponent(escape(atob(incomingCallOffer))));
            await hydraPeerConn.setRemoteDescription(new RTCSessionDescription(remoteDesc));
            const answer = await hydraPeerConn.createAnswer();
            await hydraPeerConn.setLocalDescription(answer);
            await new Promise(r => setTimeout(r, 600));
            const ansJson = JSON.stringify(hydraPeerConn.localDescription);
            await AppActions.SendText(selectedContact.ID, '[HYDRA_CALL_ANSWER]:' + btoa(unescape(encodeURIComponent(ansJson))), '');
            incomingCallOffer = null;
            callConnected = true;
            callStatusText = '🟢 Разговор идёт (E2EE Audio активно)';
        } catch (e) {
            callStatusText = '❌ Сбой ответа на звонок: ' + e;
        }
    }

    async function endHydraCall(notifyPeer = true) {
        clearTimeout(callRingTimeout);
        if (notifyPeer && selectedContact) {
            const tag = (incomingCallOffer && !callConnected) ? '[HYDRA_CALL_MISSED]:Вызов отклонён' : '[HYDRA_CALL_END]:Звонок завершён';
            await AppActions.SendText(selectedContact.ID, tag, '').catch(() => {});
            dispatch('refresh');
        }
        if (hydraPeerConn) {
            hydraPeerConn.close();
            hydraPeerConn = null;
        }
        if (window.__hydraCallStream) {
            window.__hydraCallStream.getTracks().forEach(t => t.stop());
            window.__hydraCallStream = null;
        }
        incomingCallOffer = null;
        callConnected = false;
        activeCallModal = false;
    }

    EventsOn('hydra_call_signal', async (sig) => {
        if (!sig || !sig.content) return;
        const c = String(sig.content);
        if (c.startsWith('[HYDRA_CALL_RING]:')) {
            const bUntil = Number(localStorage.getItem('hydra_block_until_' + (sig.contact_id || selectedContact?.ID)) || 0);
            if (bUntil > Date.now()) return;
            incomingCallOffer = c.slice('[HYDRA_CALL_RING]:'.length);
            activeCallModal = true;
            callConnected = false;
            callStatusText = '📲 Входящий вызов от ' + (sig.nick || selectedContact?.Nickname || 'контакта');
        } else if (c.startsWith('[HYDRA_CALL_ANSWER]:') && hydraPeerConn) {
            try {
                const b64 = c.slice('[HYDRA_CALL_ANSWER]:'.length);
                const ansDesc = JSON.parse(decodeURIComponent(escape(atob(b64))));
                await hydraPeerConn.setRemoteDescription(new RTCSessionDescription(ansDesc));
                clearTimeout(callRingTimeout);
                callConnected = true;
                callStatusText = '🟢 Собеседник ответил • Идёт разговор (E2EE)';
            } catch (e) {}
        } else if (c.startsWith('[HYDRA_CALL_END]:') || c.startsWith('[HYDRA_CALL_MISSED]:')) {
            endHydraCall(false);
            dispatch('refresh');
        }
    });

    async function toggleVoiceOrCircle(mode) {
        if (isRecordingMedia && mediaRecorderInstance) {
            clearInterval(recordingInterval);
            if (window.__addHydraLog) window.__addHydraLog('MEDIA-REC', 'Остановка записи ' + recordMode + ' (' + recordingSeconds + ' сек)');
            mediaRecorderInstance.stop();
            isRecordingMedia = false;
            return;
        }
        recordMode = mode;
        try {
            if (window.__addHydraLog) window.__addHydraLog('MEDIA-REC', 'Запрос микрофона/камеры для ' + mode);
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, sampleRate: 16000 },
                video: mode === 'video' ? { facingMode: 'user', width: { ideal: 180 }, height: { ideal: 180 }, frameRate: { ideal: 15 } } : false
            });
            recordedChunks = [];
            activeMediaStream = stream;
            if (mode === 'video') {
                setTimeout(() => {
                    if (liveCirclePreviewEl && activeMediaStream) {
                        liveCirclePreviewEl.srcObject = activeMediaStream;
                        liveCirclePreviewEl.play().catch(() => {});
                    }
                }, 60);
            }
            const options = mode === 'video'
                ? { videoBitsPerSecond: 96000, audioBitsPerSecond: 16000 }
                : { audioBitsPerSecond: 16000 };
            try {
                mediaRecorderInstance = new MediaRecorder(stream, options);
            } catch (e) {
                mediaRecorderInstance = new MediaRecorder(stream);
            }
            mediaRecorderInstance.ondataavailable = (e) => {
                if (e.data && e.data.size > 0) recordedChunks.push(e.data);
            };
            mediaRecorderInstance.onstop = () => {
                stream.getTracks().forEach(t => t.stop());
                activeMediaStream = null;
                const mime = recordMode === 'video' ? 'video/webm' : 'audio/webm';
                const blob = new Blob(recordedChunks, { type: mime });
                if (window.__addHydraLog) window.__addHydraLog('MEDIA-BLOB', `Готов ${mime}, размер: ${blob.size} байт`);
                const reader = new FileReader();
                reader.onloadend = async () => {
                    const base64Url = reader.result;
                    const payload = recordMode === 'video'
                        ? `[HYDRA_CIRCLE]:${base64Url}`
                        : `[HYDRA_VOICE]:${base64Url}`;
                    try {
                        const targetId = selectedContact?.ID || selectedContact?.ChatID;
                        if (window.__addHydraLog) window.__addHydraLog('MEDIA-SEND', `Отправка медиа в чат ${targetId} (${payload.length} симв.)`);
                        await AppActions.SendText(targetId, payload, '');
                        dispatch('refreshMessages');
                    } catch (sendErr) {
                        if (window.__addHydraLog) window.__addHydraLog('MEDIA-ERR', String(sendErr));
                        alert('Ошибка отправки медиа: ' + sendErr);
                    }
                };
                reader.readAsDataURL(blob);
            };
            recordingSeconds = 0;
            recordingInterval = setInterval(() => {
                recordingSeconds++;
                if (recordingSeconds >= 120 && isRecordingMedia && mediaRecorderInstance) {
                    clearInterval(recordingInterval);
                    mediaRecorderInstance.stop();
                    isRecordingMedia = false;
                }
            }, 1000);
            mediaRecorderInstance.start(250);
            isRecordingMedia = true;
        } catch (err) {
            if (window.__addHydraLog) window.__addHydraLog('MEDIA-PERM-ERR', String(err));
            alert('Разрешите доступ к микрофону и камере: ' + err);
        }
    }

    let showChatHeaderMenu = false;
    let showRoomSettingsModal = false;
    let newRoomMemberInput = '';

    function getContactPresenceText(contact, msgs) {
        if (!contact) return 'Оффлайн';
        if (contact.IsFavorites || contact.Nickname === 'Избранное') return 'Локальное хранилище E2EE';
        const sName = String(contact.Nickname || '');
        if (sName.startsWith('👥 ')) return 'Группа E2EE • Участники защищены';
        if (sName.startsWith('📢 ')) return 'Канал E2EE • Вещание';
        const hideExactTime = localStorage.getItem('hydra_hide_last_seen') === '1';
        const lastMsg = (msgs || []).filter(m => !m.IsOutgoing).slice(-1)[0];
        const lastTs = lastMsg?.Timestamp || (contact.LastMessageTime ? new Date(contact.LastMessageTime).getTime() : 0);
        const diffMs = Date.now() - lastTs;
        if (lastTs > 0 && diffMs < 180000) return 'В сети';
        const replyBoostTs = Number(localStorage.getItem('hydra_direct_reply_ts') || 0);
        if (hideExactTime && (Date.now() - replyBoostTs > 3600000)) return 'Был(а) за последние 24ч';
        if (lastTs > 0) {
            const d = new Date(lastTs);
            const hm = d.toTimeString().slice(0, 5);
            if (diffMs < 86400000) return 'Был(а) сегодня в ' + hm;
            return 'Был(а) ' + d.toLocaleDateString() + ' в ' + hm;
        }
        return 'Был(а) за последние 24ч';
    }

    async function clearCurrentChatHistory() {
        showChatHeaderMenu = false;
        if (!selectedContact || !messages) return;
        for (const m of messages) { await AppActions.DeleteMessage(m.ID).catch(() => {}); }
        messages = [];
        dispatch('refresh');
    }

    async function deleteCurrentChatContact() {
        showChatHeaderMenu = false;
        if (!selectedContact) return;
        await AppActions.DeleteContact(selectedContact.ID).catch(() => {});
        if (onBack) onBack();
        location.reload();
    }

    async function saveMediaDataUrlToGallery(dataUrl, prefix = 'hydra_media') {
        try {
            const ext = dataUrl.includes('video/') ? 'webm' : 'png';
            const fname = `${prefix}_${Date.now()}.${ext}`;
            const tempPath = await AppActions.SaveTempImage(dataUrl, fname);
            if (tempPath) await AppActions.SaveFileToLocation(tempPath, fname);
        } catch (e) { alert('Ошибка сохранения: ' + e); }
    }
    
</script>

<div class="chat-area animate-fade-in" class:mobile={isMobile}>
    <div class="chat-header">
        {#if isMobile && onBack}
            <button class="btn-back" on:click={onBack} style="margin-right: 8px;">
                <div class="icon-svg">{@html Icons.ArrowLeft || '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>'}</div>
            </button>
        {/if}
        <div 
            class="chat-contact-info" 
            role="button"
            tabindex="0"
            on:click={() => {
                if (isMobile) {
                    // On mobile, show profile or copy address option
                    // For now, let's copy address on long press or just show profile
                    // The user asked to "copy address". Let's use onOpenContactProfile which usually has it, 
                    // or add a copy action here. 
                    // Let's stick to onOpenContactProfile but ensure that profile has copy.
                    onOpenContactProfile();
                } else {
                     onOpenContactProfile();
                }
            }} 
            on:contextmenu|preventDefault={() => {
                 // Context menu to copy address
                 if (selectedContact?.PublicKey) {
                     // We need a way to call CopyToClipboard from here or pass it up
                     // Dispath event or use prop? 
                     // Using clipboard API directly might work if secured context
                     navigator.clipboard.writeText(selectedContact.PublicKey).then(() => alert('Адрес скопирован'));
                 }
            }}
            on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && onOpenContactProfile()}
            style="cursor: pointer;"
        >
            <div class="chat-avatar" style="background: {getAvatarGradient(selectedContact?.Nickname)};">
                {#if selectedContact?.Avatar}
                    <img src={selectedContact.Avatar} alt="av"/>
                {:else}
                    <div class="avatar-placeholder">{getInitials(selectedContact?.Nickname)}</div>
                {/if}
            </div>
            <div>
                <div class="chat-name">{selectedContact?.Nickname || 'Unknown'}</div>
                <div class="chat-status">
                    <span class="status-dot" style="background: {getUnifiedPresence(selectedContact, messages).online ? '#4CAF50' : '#9E9E9E'};"></span>
                    <span class="status-text">
                        {getUnifiedPresence(selectedContact, messages).text}
                    </span>
                    {#if isMobile}
                         <button class="btn-icon-xs" style="margin-left: 8px; opacity: 0.7;" on:click|stopPropagation={() => {
                             if(selectedContact?.I2PAddress) {
                                  import('../../wailsjs/go/main/App.js').then(Actions => {
                                      Actions.ClipboardSet(selectedContact.I2PAddress);
                                      dispatch('toast', { message: 'Адрес скопирован', type: 'success' });
                                  });
                             }
                         }}>
                            <div class="icon-svg-xs">{@html Icons.Copy || '📋'}</div>
                         </button>
                    {/if}
                </div>
            </div>
        </div>

    {#if selectedContact && !selectedContact.IsFavorites && selectedContact.Nickname !== 'Избранное'}
        <div style="margin-left: auto; display: flex; align-items: center; gap: 6px; flex-shrink: 0;">
            {#if !String(selectedContact.Nickname || '').startsWith('👥') && !String(selectedContact.Nickname || '').startsWith('📢')}
                <button type="button" on:click={cycleDisappearingTimer} style="background: #28292D; color: #8AB4F8; border: 1px solid rgba(138,180,248,0.3); border-radius: 100px; padding: 6px 10px; font-size: 11px; font-weight: 700; cursor: pointer; white-space: nowrap;" title="Таймер личного чата">
                    ⏱ {disappearingTimer === 0 ? 'Выкл' : (disappearingTimer === 60 ? '1м' : '1ч')}
                </button>
            {/if}
            {#if !String(selectedContact.Nickname || '').startsWith('👥') && !String(selectedContact.Nickname || '').startsWith('📢')}
                <button type="button" on:click={startHydraCall} style="background: #1D4ED8; color: #fff; border: none; border-radius: 50%; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; font-size: 16px; cursor: pointer; flex-shrink: 0;" title="Позвонить">📞</button>
            {/if}
            <div style="position: relative;">
                <button type="button" on:click|stopPropagation={() => showChatHeaderMenu = !showChatHeaderMenu} style="background: #22252A; color: #E5E7EB; border: 1px solid rgba(59,130,246,0.25); border-radius: 50%; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 800; cursor: pointer;">⋮</button>
                {#if showChatHeaderMenu}
                    <div on:click={() => showChatHeaderMenu = false} style="position: fixed; inset: 0; z-index: 99990; background: transparent;"></div>
                    <div style="position: fixed; top: 62px; right: 14px; background: #1A1C20; border: 1px solid rgba(59,130,246,0.3); border-radius: 16px; padding: 6px; z-index: 99995; min-width: 225px; box-shadow: 0 12px 32px rgba(0,0,0,0.7);">
                        {#if String(selectedContact?.Nickname || '').startsWith('👥') || String(selectedContact?.Nickname || '').startsWith('📢')}
            <button type="button" on:click={openGroupManagementModal} style="width: 100%; text-align: left; padding: 10px 12px; background: transparent; color: #60A5FA; border: none; border-radius: 10px; font-size: 13px; font-weight: 700; cursor: pointer;">👥 Добавить участников (по ID / из чатов)</button>
        {/if}
        <button type="button" on:click={clearCurrentChatHistory} style="width: 100%; text-align: left; padding: 10px 12px; background: transparent; color: #E5E7EB; border: none; border-radius: 10px; font-size: 13px; cursor: pointer;">🧹 Очистить историю чата</button>
                        {#if !selectedContact?.IsFavorites && selectedContact?.Nickname !== 'Избранное'}
                            <button type="button" on:click={deleteCurrentChatContact} style="width: 100%; text-align: left; padding: 10px 12px; background: transparent; color: #ff6b6b; border: none; border-radius: 10px; font-size: 13px; font-weight: 600; cursor: pointer;">🗑️ Удалить чат</button>
                        {/if}
                    </div>
                {/if}
            </div>
        </div>
    {/if}
</div>

{#if activeCallModal}
    <div style="position: fixed; inset: 0; background: rgba(12,14,20,0.96); backdrop-filter: blur(16px); z-index: 99999; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 20px; padding: 24px;">
        <audio bind:this={remoteAudioEl} autoplay></audio>
        <div style="width: 96px; height: 96px; border-radius: 50%; background: #1A1C20; border: 3px solid #3B82F6; display: flex; align-items: center; justify-content: center; font-size: 36px; color: #60A5FA; font-weight: 700; box-shadow: 0 0 32px rgba(29,78,216,0.4);">
            {getInitials(selectedContact?.Nickname)}
        </div>
        <div style="font-size: 22px; font-weight: 800; color: #E5E7EB;">{selectedContact?.Nickname}</div>
        <div style="font-size: 14px; color: #60A5FA; text-align: center;">{callStatusText}</div>
        <div style="display: flex; gap: 14px; margin-top: 8px;">
            <button type="button" on:click={toggleCallMute} style="background: {isCallMuted ? '#EF4444' : '#1E222B'}; color: #fff; border: 1px solid rgba(255,255,255,0.15); border-radius: 100px; padding: 11px 18px; font-size: 13px; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 6px;">
                <span>{isCallMuted ? '🔇' : '🎙️'}</span>
                <span>{isCallMuted ? 'Микрофон выкл' : 'Микрофон вкл'}</span>
            </button>
            <button type="button" on:click={toggleCallRecording} style="background: {isRecordingCall ? '#EF4444' : '#1E222B'}; color: #fff; border: 1px solid rgba(255,255,255,0.15); border-radius: 100px; padding: 11px 18px; font-size: 13px; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 6px;">
                <span>{isRecordingCall ? '⏹' : '🔴'}</span>
                <span>{isRecordingCall ? 'Стоп запись' : 'Запись вызова'}</span>
            </button>
        </div>
        <div style="display: flex; gap: 16px; margin-top: 24px;">
            {#if incomingCallOffer && !callConnected}
                <button type="button" on:click={acceptIncomingHydraCall} style="background: #10B981; color: #fff; border: none; border-radius: 100px; padding: 14px 28px; font-size: 15px; font-weight: 800; cursor: pointer;">
                    🟢 Принять
                </button>
            {/if}
            <button type="button" on:click={() => endHydraCall(true)} style="background: #ff4757; color: #fff; border: none; border-radius: 100px; padding: 14px 28px; font-size: 15px; font-weight: 800; cursor: pointer;">
                📴 {incomingCallOffer && !callConnected ? 'Отклонить' : 'Завершить'}
            </button>
        </div>
    </div>
{/if}
    
    {#if pinnedChatMsg}
            <div style="display: flex; align-items: center; justify-content: space-between; padding: 8px 16px; background: rgba(26,28,32,0.95); border-bottom: 1px solid rgba(59,130,246,0.2); z-index: 9;">
                <div style="border-left: 3px solid #3B82F6; padding-left: 10px; font-size: 12px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis;">
                    <div style="color: #60A5FA; font-weight: 700;">📌 Закреплённое сообщение</div>
                    <div style="color: #D1D5DB; overflow: hidden; text-overflow: ellipsis;">{pinnedChatMsg.Content}</div>
                </div>
                <button type="button" on:click={() => { pinnedChatMsg = null; if (selectedContact) localStorage.removeItem('hydra_pinned_msg_' + selectedContact.ID); }} style="background: transparent; border: none; color: #9CA3AF; font-size: 14px; cursor: pointer;">✕</button>
            </div>
        {/if}
        <div class="messages-container messages-scroll-area" bind:this={containerRef} on:scroll={handleScroll} style="opacity: {chatReady ? 1 : 0}; transition: opacity 0.2s;">
        {#each messages as msg (msg.ID)}
            <div 
                class="message animate-message" 
                id="msg-{msg.ID}" 
                class:outgoing={msg.IsOutgoing}
                on:touchstart={(e) => isMobile && handleTouchStart(e, msg.ID)}
                on:touchmove={(e) => isMobile && handleTouchMove(e)}
                on:touchend={() => isMobile && handleTouchEnd(msg)}
                on:dblclick={() => handleDoubleClick(msg)}
                on:click={(e) => {
                    if (isMobile) {
                        onShowMessageMenu(e, msg);
                    }
                }}
            >
                <div 
                    class="message-bubble-wrapper" 
                    style="transform: translateX(-{swipedMsgId === msg.ID ? touchMoveX : 0}px); transition: {swipedMsgId === msg.ID ? 'none' : 'transform 0.3s cubic-bezier(0.18, 0.89, 0.32, 1.28)'};"
                >
                    <div class="message-bubble" class:outgoing={msg.IsOutgoing} class:circle-only-bubble={msg.Content && msg.Content.startsWith("[HYDRA_CIRCLE]:")} 
                         on:contextmenu|preventDefault={(e) => onShowMessageMenu(e, msg)}
                    >
                        {#if msg.ReplyPreview}
                            <div 
                                class="reply-preview-bubble" 
                                role="button" 
                                tabindex="0"
                                on:click|stopPropagation={() => {
                                    const target = document.getElementById(`msg-${msg.ReplyToID}`);
                                    if (target) {
                                        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                        target.classList.add('highlight-scroll');
                                        setTimeout(() => target.classList.remove('highlight-scroll'), 2000);
                                    } else {
                                        dispatch('jumpToMessage', msg.ReplyToID);
                                    }
                                }}
                            >
                                <div class="reply-author">{msg.ReplyPreview.author_name || msg.ReplyPreview.AuthorName}</div>
                                <div class="reply-content-preview">{msg.ReplyPreview.content || msg.ReplyPreview.Content}</div>
                            </div>
                        {/if}
                        {#if msg.Attachments && msg.Attachments.length > 0}
                            <div class="message-images" style="grid-template-columns: {msg.Attachments.length === 1 ? '1fr' : 'repeat(2, 1fr)'}">
                                {#each msg.Attachments as att}
                                    {#if att.MimeType && att.MimeType.startsWith('image/')}
                                        <img 
                                            use:startLoadingImage={att.LocalPath} 
                                            alt="attachment" 
                                            class="msg-img" 
                                            style="height: {msg.Attachments.length === 1 ? 'auto' : '120px'}; min-height: 100px;" 
                                            role="button"
                                            tabindex="0"
                                            on:click|stopPropagation={() => onPreviewImage(att.LocalPath)}
                                            on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && onPreviewImage(att.LocalPath)}
                                            on:load={onImageLoad}
                                        />
                                    {:else}
                                        <div class="file-attachment-container">
                                            <div 
                                                class="file-attachment-card" 
                                                role="button"
                                                tabindex="0"
                                                on:click|stopPropagation={() => onOpenFile(att.LocalPath)}
                                                on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && onOpenFile(att.LocalPath)}
                                            >
                                                <div class="file-icon">📄</div>
                                                <div class="file-info">
                                                    <div class="file-name">{att.Filename || 'File'}</div>
                                                    <div class="file-size">{att.Size ? (att.Size / 1024).toFixed(1) + ' KB' : ''}</div>
                                                </div>
                                            </div>
                                            <button class="btn-file-save" on:click|stopPropagation={() => onSaveFile(att.LocalPath, att.Filename)} title="Сохранить в другое место">
                                                <div class="icon-svg-xs">{@html Icons.Download || '⬇️'}</div>
                                            </button>
                                        </div>
                                    {/if}
                                {/each}
                            </div>
                        {/if}

                        {#if editingMessageId === msg.ID}
                            <div class="message-edit-container">
                                <textarea class="message-edit-input" bind:value={editMessageContent} on:keydown={(e) => e.key === 'Escape' && onCancelEdit()}></textarea>
                                <div class="message-edit-actions">
                                    <button class="btn-sm btn-primary" on:click={onSaveEditMessage}>✓</button>
                                    <button class="btn-sm btn-secondary" on:click={onCancelEdit}>✕</button>
                                </div>
                            </div>
                        {:else if msg.ContentType === 'file_offer'}
                            <div class="file-offer-card">
                                <div class="file-icon-large">📁</div>
                                <div class="file-info">
                                    <div class="file-title">Файлов: {msg.FileCount || (msg.Attachments ? msg.Attachments.length : (msg.Filenames ? msg.Filenames.length : 0))}</div>
                                <div class="file-size">{((msg.TotalSize || 0) / (1024*1024)).toFixed(2)} MB</div>
                            </div>
                        </div>
                            <div class="file-actions">
                                {#if !msg.IsOutgoing}
                                    <button class="btn-small btn-success" on:click|stopPropagation={() => onAcceptTransfer(msg)}>Принять</button>
                                    <button class="btn-small btn-danger" on:click|stopPropagation={() => onDeclineTransfer(msg)}>Отклонить</button>
                                {/if}
                            </div>
                        {:else}
                            {#if msg.Content && msg.Content.startsWith('[HYDRA_VOICE]:')}
                    <div style="display: flex; align-items: center; gap: 12px; padding: 6px 4px; min-width: 215px;">
                        <button type="button" on:click|stopPropagation={() => togglePlayCustomVoice(msg.ID, msg.Content.slice('[HYDRA_VOICE]:'.length))} style="width: 44px; height: 44px; border-radius: 50%; background: #FFFFFF; color: #1E3A8A; border: none; display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 800; cursor: pointer; flex-shrink: 0; box-shadow: 0 4px 12px rgba(0,0,0,0.25);">
                            {playingVoiceId === msg.ID ? '⏸' : '▶'}
                        </button>
                        <div style="flex: 1; display: flex; flex-direction: column; gap: 5px;">
                            <div style="display: flex; align-items: center; gap: 2.5px; height: 22px;">
                                {#each [35,65,45,85,55,95,70,40,80,60,90,50,75,45,85,60,40,70,50,35,60,45] as barH, idx}
                                    <span style="width: 3px; height: {barH}%; border-radius: 2px; background: {playingVoiceId === msg.ID ? '#93C5FD' : 'rgba(255,255,255,0.65)'}; transition: height 0.2s;"></span>
                                {/each}
                            </div>
                            <div style="display: flex; justify-content: space-between; align-items: center; font-size: 11px; opacity: 0.85;">
                                <span>Голосовое • {playingVoiceId === msg.ID ? 'Идёт воспроизведение' : 'Аудио'}</span>
                                <button type="button" on:click|stopPropagation={() => cycleVoiceSpeed(msg.ID)} style="background: rgba(255,255,255,0.18); color: #fff; border: none; border-radius: 100px; padding: 1px 7px; font-size: 10px; font-weight: 800; cursor: pointer;">
                                    {voicePlaybackRate}x
                                </button>
                            </div>
                        </div>
                    </div>
                {:else if msg.Content && msg.Content.startsWith('[HYDRA_CIRCLE]:')}
                    <div
                        style="padding: 8px 4px; display: flex; flex-direction: column; align-items: center;"
                        on:touchstart={() => startCircleHold(msg.ID)}
                        on:touchend={endCircleHold}
                        on:touchcancel={endCircleHold}
                        on:mousedown={() => startCircleHold(msg.ID)}
                        on:mouseup={endCircleHold}
                        on:dblclick|stopPropagation={(e) => onShowMessageMenu(e, msg)}
                    >
                        <div style="width: 216px; height: 216px; border-radius: 50% !important; overflow: hidden !important; clip-path: circle(50% at 50% 50%) !important; -webkit-clip-path: circle(50% at 50% 50%) !important; border: 3px solid #3B82F6; background: #12131A; position: relative; display: flex; align-items: center; justify-content: center; box-shadow: 0 8px 24px rgba(0,0,0,0.5); transform: scale({zoomedCircleMsgId === msg.ID ? 1.32 : 1}); transition: transform 0.28s cubic-bezier(0.2, 0.9, 0.3, 1.2); z-index: {zoomedCircleMsgId === msg.ID ? 50 : 2};">
                            <video
                                preload="metadata"
                                autoplay
                                muted
                                loop
                                playsinline
                                src={msg.Content.slice('[HYDRA_CIRCLE]:'.length).includes('#t=') ? msg.Content.slice('[HYDRA_CIRCLE]:'.length) : (msg.Content.slice('[HYDRA_CIRCLE]:'.length) + '#t=0.1')}
                                on:loadedmetadata={(e) => { const v = e.currentTarget; v.currentTime = 0.1; v.play().catch(() => {}); }}
                                on:click|stopPropagation={(e) => {
                                    const v = e.currentTarget;
                                    if (v.muted) {
                                        v.muted = false;
                                        v.currentTime = 0;
                                        v.play();
                                    } else if (v.paused) {
                                        v.play();
                                    } else {
                                        v.muted = true;
                                        v.pause();
                                    }
                                }}
                                style="width: 100% !important; height: 100% !important; object-fit: cover !important; border-radius: 50% !important; cursor: pointer;"
                            ></video>
                            <div style="position: absolute; bottom: 12px; left: 50%; transform: translateX(-50%); background: rgba(12,14,20,0.75); color: #60A5FA; border-radius: 100px; padding: 3px 10px; font-size: 10px; font-weight: 700; white-space: nowrap; pointer-events: none;">🔊 Звук • 2x тап меню</div>
                        </div>
                    </div>
                {:else if msg.Content && msg.Content.startsWith('[HYDRA_VIDEO]:')}
                    <div style="padding: 4px; max-width: 260px;">
                        <video controls playsinline src={msg.Content.slice('[HYDRA_VIDEO]:'.length)} style="width: 100%; max-height: 320px; border-radius: 14px; background: #000;"></video>
                    </div>
                {:else if msg.Content && (msg.Content.startsWith('[HYDRA_CALL_RING]:') || msg.Content.startsWith('[HYDRA_CALL_ANSWER]:') || msg.Content.startsWith('[HYDRA_CALL_ICE]:') || msg.Content.startsWith('[HYDRA_CALL_MISSED]:') || msg.Content.startsWith('[HYDRA_CALL_END]:'))}
                    <div style="display: flex; align-items: center; gap: 10px; padding: 6px 10px;">
                        <span style="font-size: 20px;">{msg.Content.startsWith('[HYDRA_CALL_MISSED]:') ? '☎️' : '📞'}</span>
                        <div>
                            <div style="font-weight: 700; font-size: 13px; color: {msg.Content.startsWith('[HYDRA_CALL_MISSED]:') ? '#FCA5A5' : '#E5E7EB'};">
                                {msg.Content.startsWith('[HYDRA_CALL_RING]:') ? (msg.IsOutgoing ? 'Исходящий вызов' : 'Входящий вызов') : (msg.Content.startsWith('[HYDRA_CALL_ANSWER]:') ? '🟢 Вызов принят (E2EE)' : (msg.Content.startsWith('[HYDRA_CALL_MISSED]:') ? 'Пропущенный вызов' : 'Аудиозвонок завершён'))}
                            </div>
                            <button type="button" on:click|stopPropagation={startHydraCall} style="margin-top: 4px; background: rgba(59,130,246,0.2); color: #60A5FA; border: 1px solid rgba(59,130,246,0.35); border-radius: 100px; padding: 3px 10px; font-size: 11px; font-weight: 700; cursor: pointer;">Перезвонить</button>
                        </div>
                    </div>
                {:else if msg.Content && (msg.Content.trim().startsWith('SFlEUkFfUk9PTV') || msg.Content.startsWith('🎟️ Вас добавили в'))}
                    <div style="display: flex; flex-direction: column; gap: 8px; padding: 4px;">
                        <div style="font-weight: 700; font-size: 13px; color: #93C5FD;">
                            {msg.Content.startsWith('🎟️') ? msg.Content : '🎟️ Ссылка-приглашение в группу Hydra'}
                        </div>
                        {#if msg.Content.trim().startsWith('SFlEUkFfUk9PTV')}
                            <div style="font-size: 11px; opacity: 0.75; font-family: monospace; word-break: break-all;">{msg.Content.trim()}</div>
                            <button type="button" on:click|stopPropagation={() => { if (window.__joinHydraGroupByKey) window.__joinHydraGroupByKey(msg.Content.trim()); }} style="background: #fff; color: #1E3A8A; border: none; border-radius: 100px; padding: 8px 16px; font-size: 12px; font-weight: 800; cursor: pointer; align-self: flex-start;">
                                ➡️ Перейти / Вступить в группу
                            </button>
                        {/if}
                    </div>
                {:else}
                    <div class="message-content">{@html parseMarkdown(msg.Content)}</div>
                {/if}
                        {/if}

                        <div class="message-meta" style="display: flex; align-items: center; gap: 6px;">
                            {#if messageReactions[msg.ID]}
                                <span style="background: rgba(255,255,255,0.16); border-radius: 100px; padding: 1px 6px; font-size: 12px;">{messageReactions[msg.ID]}</span>
                            {/if}
                            <span class="message-time">{formatTime(msg.Timestamp)}</span>
                            {#if msg.IsOutgoing}
                                <span class="message-status"><div class="icon-svg-sm" style="display:inline-block; width:14px; height:14px;">{@html msg.Status === 'sending' ? Icons.StatusSent : (msg.Status === 'read' ? Icons.StatusRead : Icons.StatusDelivered)}</div></span>
                            {/if}
                        </div>
                    </div>
                </div>
                {#if isMobile && swipedMsgId === msg.ID}
                    <div class="swipe-reply-icon" style="opacity: {Math.min(touchMoveX / swipeThreshold, 1)}; transform: scale({Math.min(touchMoveX / swipeThreshold, 1)})">
                        <div class="icon-svg-sm">{@html Icons.Reply || '↩️'}</div>
                    </div>
                {/if}
            </div>
        {/each}
        
        {#if showScrollButton}
            <button class="btn-scroll-bottom" transition:fly={{ y: 20, duration: 200 }} on:click={() => scrollToBottom(true)}>
                <div class="icon-svg">{@html Icons.ArrowDown}</div>
            </button> 
        {/if}
    </div>

    <!-- Loading Spinner Overlay -->
    {#if !chatReady}
        <div class="loading-overlay" transition:fade={{duration: 200}}>
            <div class="spinner"></div>
        </div>
    {/if}

    <div class="input-area-wrapper">
        {#if replyingTo}
            <div class="replying-to-bar" transition:fade={{duration: 150}}>
                <div class="reply-line"></div>
                <div class="reply-info">
                    <div class="reply-author-name">Ответ для {replyingTo.IsOutgoing ? 'Меня' : ((selectedContact.Nickname?.length > 50 ? selectedContact.Nickname.substring(0, 47) + '...' : selectedContact.Nickname) || 'Unknown')}</div>
                    <div class="reply-text-preview">{replyingTo.Content?.length > 100 ? replyingTo.Content.substring(0, 97) + '...' : (replyingTo.Content || (replyingTo.ContentType === 'mixed' ? '📷 Фото' : '📎 Файл'))}</div>
                </div>
                <button class="btn-cancel-reply" on:click={() => { replyingTo = null; if (onCancelReply) onCancelReply(); }}>
                    <div class="icon-svg-xs">{@html Icons.Plus}</div>
                </button>
            </div>
        {/if}

        <div class="attachment-preview-wrapper" style="display: {selectedFiles.length > 0 ? 'block' : 'none'}">
            <div class="attachment-preview-container">
                <div class="attachment-preview" style="max-width: calc(100% - 60px);">
                    {#each selectedFiles as file, i}
                        <div class="preview-item">
                            {#if filePreviews[file]}
                                <img src={`data:image/png;base64,${filePreviews[file]}`} alt="preview" />
                            {:else}
                                <div class="file-icon-preview">📄</div>
                            {/if}
                            <button class="btn-remove-att" on:click={() => onRemoveFile(i)}>X</button>
                        </div>
                    {/each}
                </div>
                <div class="preview-actions">
                    <button class="btn-toggle-comp {isCompressed ? 'active' : ''}" on:click={() => isCompressed = !isCompressed} title={isCompressed ? 'Сжимать изображения (быстро)' : 'Отправлять оригинал (требует принятия)'}>
                        <div class="icon-svg" style="opacity: {isCompressed ? 1 : 0.5}">{@html isCompressed ? Icons.Image : Icons.File}</div>
                    </button>
                </div>
            </div>
        </div>
        
        
    {#if showEmojiPanel}
        <div style="display: flex; flex-wrap: wrap; gap: 8px; padding: 10px 14px; background: #1E1F22; border-top: 1px solid rgba(255,255,255,0.06);">
            {#each hydraEmojis as em}
                <button type="button" on:click={() => insertHydraEmoji(em)} style="font-size: 22px; background: transparent; border: none; cursor: pointer; padding: 4px;">{em}</button>
            {/each}
        </div>
    {/if}
    {#if showCircleStudio}
        <div style="position: fixed; inset: 0; background: rgba(8,10,16,0.95); backdrop-filter: blur(18px); z-index: 99990; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 18px; padding: 20px;">
            <div style="display: flex; justify-content: space-between; align-items: center; width: 100%; max-width: 320px;">
                <span style="font-size: 15px; font-weight: 800; color: {isRecordingMedia ? '#EF4444' : '#60A5FA'};">
                    {isRecordingMedia ? `🔴 Запись: ${recordingSeconds} сек / 120 сек` : (circleDraftDataUrl ? '✂️ Обрезка и предпросмотр' : '📹 Камера кружка (до 2 мин)')}
                </span>
                <button type="button" on:click={closeCircleStudio} style="width: 38px; height: 38px; border-radius: 50%; background: #1A1C20; color: #fff; border: 1px solid rgba(255,255,255,0.1); font-size: 16px; cursor: pointer;">✕</button>
            </div>

            <div style="width: 250px; height: 250px; border-radius: 50%; overflow: hidden; clip-path: circle(50% at 50% 50%); -webkit-clip-path: circle(50% at 50% 50%); border: 4px solid {isRecordingMedia ? '#EF4444' : '#3B82F6'}; background: #000; box-shadow: 0 0 36px rgba(29,78,216,0.45); position: relative;">
                {#if circleDraftDataUrl}
                    <video
                        autoplay
                        loop
                        playsinline
                        src={circleDraftDataUrl + `#t=${circleTrimStart},${circleTrimEnd}`}
                        on:loadedmetadata={(e) => {
                            const d = Math.round(e.currentTarget.duration || 15);
                            if (d > 0 && d < 600) { circleTotalDuration = d; circleTrimEnd = d; }
                        }}
                        on:timeupdate={(e) => {
                            const v = e.currentTarget;
                            if (v.currentTime < circleTrimStart || v.currentTime > circleTrimEnd) {
                                v.currentTime = circleTrimStart;
                            }
                        }}
                        style="width: 100%; height: 100%; object-fit: cover; border-radius: 50%;"
                    ></video>
                {:else}
                    <video bind:this={liveCirclePreviewEl} autoplay muted playsinline style="width: 100%; height: 100%; object-fit: cover; transform: {circleFacingMode === 'user' ? 'scaleX(-1)' : 'none'}; border-radius: 50%;"></video>
                {/if}
            </div>

            {#if circleDraftDataUrl}
                <div style="width: 100%; max-width: 300px; background: #16181F; border: 1px solid rgba(59,130,246,0.25); border-radius: 18px; padding: 12px 16px; display: flex; flex-direction: column; gap: 8px;">
                    <div style="display: flex; justify-content: space-between; font-size: 12px; color: #60A5FA; font-weight: 700;">
                        <span>✂️ Начало: {circleTrimStart} сек</span>
                        <span>Конец: {circleTrimEnd} сек</span>
                    </div>
                    <input type="range" min="0" max={Math.max(1, circleTrimEnd - 1)} step="1" bind:value={circleTrimStart} style="width: 100%; accent-color: #3B82F6;" />
                    <input type="range" min={circleTrimStart + 1} max={Math.max(2, circleTotalDuration)} step="1" bind:value={circleTrimEnd} style="width: 100%; accent-color: #3B82F6;" />
                </div>
            {/if}

            <div style="display: flex; align-items: center; justify-content: center; gap: 24px; margin-top: 6px;">
                {#if !circleDraftDataUrl}
                    <button type="button" on:click={() => openCircleStudio(true)} style="width: 54px; height: 54px; border-radius: 50%; background: #1A1C20; color: #60A5FA; border: 1.5px solid rgba(59,130,246,0.4); display: flex; align-items: center; justify-content: center; font-size: 22px; cursor: pointer;" title="Переключить камеру">
                        🔄
                    </button>
                    {#if !isRecordingMedia}
                        <button type="button" on:click={startCircleStudioRecording} style="width: 74px; height: 74px; border-radius: 50%; background: linear-gradient(135deg, #EF4444, #DC2626); color: #fff; border: 4px solid rgba(255,255,255,0.25); display: flex; align-items: center; justify-content: center; font-size: 28px; cursor: pointer; box-shadow: 0 6px 24px rgba(239,68,68,0.5);" title="Начать запись кружка">
                            ▶
                        </button>
                    {:else}
                        <button type="button" on:click={stopCircleStudioRecording} style="width: 74px; height: 74px; border-radius: 50%; background: linear-gradient(135deg, #2563EB, #1D4ED8); color: #fff; border: 4px solid rgba(255,255,255,0.25); display: flex; align-items: center; justify-content: center; font-size: 26px; cursor: pointer; box-shadow: 0 6px 24px rgba(37,99,235,0.5);" title="Остановить запись">
                            ⏹
                        </button>
                    {/if}
                {:else}
                    <button type="button" on:click={() => { editingExistingCircleId = null; openCircleStudio(false); }} style="width: 56px; height: 56px; border-radius: 50%; background: #1A1C20; color: #FCA5A5; border: 1.5px solid rgba(239,68,68,0.4); display: flex; align-items: center; justify-content: center; font-size: 22px; cursor: pointer;" title="Переснять">
                        🔄
                    </button>
                    <button type="button" on:click={sendEditedCircle} style="width: 74px; height: 74px; border-radius: 50%; background: linear-gradient(135deg, #2563EB, #1D4ED8); color: #fff; border: 4px solid rgba(255,255,255,0.25); display: flex; align-items: center; justify-content: center; font-size: 28px; cursor: pointer; box-shadow: 0 6px 24px rgba(37,99,235,0.5);" title="Отправить кружок">
                        ➤
                    </button>
                {/if}
            </div>
        </div>
    {/if}
    {#if isRecordingMedia && recordMode === 'audio'}
        <div style="padding: 8px 14px; background: rgba(255,71,87,0.15); border-top: 1px solid rgba(255,71,87,0.3); display: flex; align-items: center; justify-content: space-between;">
            <span style="color: #ff6b6b; font-size: 12px; font-weight: 700;">🎤 Запись голосового: {recordingSeconds} сек...</span>
            <button type="button" on:click={() => toggleVoiceOrCircle('audio')} style="background: #ff4757; color: #fff; border: none; border-radius: 100px; padding: 5px 14px; font-size: 11px; font-weight: 700;">⏹ Отправить</button>
        </div>
    {/if}
    {#if showModeHintBanner}
        <div style="margin: 0 8px 8px; padding: 10px 14px; background: rgba(20, 22, 28, 0.95); border: 1px solid rgba(59,130,246,0.25); border-radius: 16px; color: #E5E7EB; font-size: 12px; line-height: 1.35; box-shadow: 0 8px 24px rgba(0,0,0,0.5);">
            Удерживайте для записи {mediaButtonMode === 'video' ? 'видео-кружка' : 'голосового'}. Нажмите для переключения в {mediaButtonMode === 'video' ? 'голосовой' : 'видео'} режим.
        </div>
    {/if}

    <div class="input-area" style="background: #1C1E24; border: 1px solid rgba(255,255,255,0.06); border-radius: 28px; padding: 6px 8px 6px 12px; display: flex; align-items: center; gap: 8px;">
        <button type="button" class="btn-icon" on:click={() => showEmojiPanel = !showEmojiPanel} title="Стикеры и эмодзи" style="padding: 6px; color: #9CA3AF;">
            <span style="font-size: 20px;">😊</span>
        </button>
        <div style="flex: 1; position: relative;">
            <textarea
                bind:this={textarea}
                class="message-input"
                placeholder="Сообщение"
                bind:value={newMessage}
                on:keypress={onKeyPress}
                on:paste={onPaste}
                on:input={resizeTextarea}
                rows="1"
                style="width: 100%; font-size: 16px;"
            ></textarea>
        </div>
        <button type="button" class="btn-icon" on:click={onSelectFiles} title="Прикрепить фото или файл" style="padding: 6px; color: #9CA3AF;">
            <div class="icon-svg">{@html Icons.Paperclip}</div>
        </button>

        {#if (newMessage || "").trim().length > 0 || (selectedFiles || []).length > 0}
            <button type="button" class="btn-send" on:click={onSendMessage} style="width: 44px; height: 44px; border-radius: 50%; background: linear-gradient(135deg, #2563EB, #1D4ED8); color: #fff; border: none; display: flex; align-items: center; justify-content: center; flex-shrink: 0;">
                <div class="icon-svg">{@html Icons.Send}</div>
            </button>
        {:else}
            <button
                type="button"
                on:click={handleMediaBtnTap}
                on:touchstart={handleMediaBtnHoldStart}
                on:touchend={handleMediaBtnHoldEnd}
                on:mousedown={handleMediaBtnHoldStart}
                on:mouseup={handleMediaBtnHoldEnd}
                style="width: 44px; height: 44px; border-radius: 50%; background: {isRecordingMedia ? '#EF4444' : 'linear-gradient(135deg, #2563EB, #1D4ED8)'}; color: #fff; border: none; display: flex; align-items: center; justify-content: center; font-size: 19px; cursor: pointer; flex-shrink: 0; user-select: none; box-shadow: 0 4px 14px rgba(29,78,216,0.4);"
                title="Нажмите для смены режима (Микрофон/Кружок), удерживайте для записи"
            >
                {#if isRecordingMedia}
                    ⏹
                {:else if mediaButtonMode === 'video'}
                    📷
                {:else}
                    🎤
                {/if}
            </button>
        {/if}
    </div>
    </div>
</div>


{#if showVideoTrimModal && videoFileToTrim}
    <div style="position: fixed; inset: 0; background: rgba(10,11,16,0.92); z-index: 99999; display: flex; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: #1A1C20; border: 1px solid rgba(59,130,246,0.3); border-radius: 24px; padding: 20px; width: 100%; max-width: 360px;">
            <h3 style="margin: 0 0 8px; color: #E5E7EB; font-size: 16px;">🎬 Параметры отправки видео</h3>
            <p style="font-size: 12px; color: #9CA3AF; margin: 0 0 12px;">Файл: <b>{videoFileToTrim.name}</b> ({(videoFileToTrim.size / 1024 / 1024).toFixed(2)} МБ)</p>
            <label style="display: block; font-size: 12px; color: #9CA3AF; margin-bottom: 10px;">
                Формат и качество:
                <select bind:value={videoTrimQuality} style="width: 100%; margin-top: 6px; padding: 10px; border-radius: 12px; background: #121316; color: #fff; border: 1px solid rgba(59,130,246,0.3);">
                    <option value="compressed">🎬 Видеоролик с плеером в чате (сжатый)</option>
                    <option value="original">📄 Исходное качество (в виде файла)</option>
                </select>
            </label>
            <label style="display: block; font-size: 12px; color: #9CA3AF; margin-bottom: 14px;">
                Обрезать длительность (сек): <b>до {videoTrimMaxSec} сек</b>
                <input type="range" min="5" max="120" step="5" bind:value={videoTrimMaxSec} style="width: 100%; margin-top: 6px; accent-color: #1D4ED8;" />
            </label>
            <div style="display: flex; gap: 8px;">
                <button type="button" on:click={() => { showVideoTrimModal = false; videoFileToTrim = null; }} style="flex: 1; padding: 10px; border-radius: 100px; background: #28292D; color: #fff; border: none;">Отмена</button>
                <button type="button" on:click={sendTrimmedOrQualityVideo} style="flex: 1; padding: 10px; border-radius: 100px; background: #1D4ED8; color: #fff; border: none; font-weight: 700;">Отправить</button>
            </div>
        </div>
    </div>
{/if}

{#if showRoomSettingsModal && selectedContact}
    <div style="position: fixed; inset: 0; background: rgba(10,11,16,0.92); z-index: 99999; display: flex; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: #1A1C20; border: 1px solid rgba(59,130,246,0.3); border-radius: 24px; padding: 20px; width: 100%; max-width: 380px; max-height: 85vh; overflow-y: auto;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <h3 style="margin: 0; color: #E5E7EB; font-size: 16px;">👥 Участники: {selectedContact.Nickname}</h3>
                <button type="button" on:click={() => showRoomSettingsModal = false} style="background: #28292D; color: #fff; border: none; border-radius: 50%; width: 32px; height: 32px;">✕</button>
            </div>
            <div style="padding: 10px 12px; background: #121316; border: 1px solid rgba(59,130,246,0.25); border-radius: 14px; margin-bottom: 12px;">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                    <span style="font-size: 12px; font-weight: 800; color: #60A5FA;">👥 Участники ({currentGroupMembers.length}):</span>
                    <button type="button" on:click={() => { currentGroupHideMembers = !currentGroupHideMembers; localStorage.setItem('hydra_group_hide_members_' + selectedContact.ID, currentGroupHideMembers ? '1' : '0'); }} style="background: rgba(59,130,246,0.18); color: #93C5FD; border: none; border-radius: 100px; padding: 3px 9px; font-size: 10px; font-weight: 700; cursor: pointer;">
                        {currentGroupHideMembers ? '🙈 Скрыты от других' : '👁️ Видны всем'}
                    </button>
                </div>
                <div style="font-size: 12px; color: #E5E7EB;">{currentGroupMembers.join(', ')}</div>
            </div>
            <div style="font-size: 12px; color: #9CA3AF; margin-bottom: 6px;">Добавить по @нику или крипто-ID:</div>
            <div style="display: flex; gap: 8px; margin-bottom: 14px;">
                <input type="text" placeholder="@никнейм или ключ..." bind:value={newRoomMemberInput} style="flex: 1; padding: 10px; border-radius: 12px; background: #121316; color: #fff; border: 1px solid rgba(59,130,246,0.3); font-size: 12px;" />
                <button type="button" on:click={async () => {
                    if (!newRoomMemberInput.trim()) return;
                    try {
                        const c = await AppActions.AddContact(newRoomMemberInput.trim(), newRoomMemberInput.trim());
                        if (c) await inviteContactToGroup(c);
                        newRoomMemberInput = '';
                    } catch (e) { alert('Ошибка: ' + e); }
                }} style="background: #1D4ED8; color: #fff; border: none; border-radius: 12px; padding: 0 14px; font-size: 12px; font-weight: 700;">+ По ID</button>
            </div>
            <div style="font-size: 12px; color: #9CA3AF; margin-bottom: 8px;">Или выберите прямо из ваших чатов:</div>
            {#if allExistingContactsForGroup.length === 0}
                <div style="font-size: 12px; color: #6B7280; padding: 8px 0;">Нет других контактов в списке чатов.</div>
            {:else}
                <div style="display: flex; flex-direction: column; gap: 6px; max-height: 200px; overflow-y: auto;">
                    {#each allExistingContactsForGroup as c}
                        <div style="display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: #121316; border-radius: 12px;">
                            <span style="font-size: 13px; color: #E5E7EB; font-weight: 600;">{c.Nickname}</span>
                            {#if currentGroupMembers.includes(c.Nickname)}
                                <span style="color: #10B981; font-size: 12px; font-weight: 800; padding: 4px 10px;">✅ Добавлен</span>
                            {:else}
                                <button type="button" on:click={() => inviteContactToGroup(c)} style="background: #1E3A8A; color: #60A5FA; border: 1px solid rgba(59,130,246,0.3); border-radius: 100px; padding: 5px 12px; font-size: 11px; font-weight: 700; cursor: pointer;">+ Добавить</button>
                            {/if}
                        </div>
                    {/each}
                </div>
            {/if}
        </div>
    </div>
{/if}

<style>
    .chat-area { flex: 1; display: flex; flex-direction: column; background: var(--bg-primary, #121316); overflow: hidden; overflow-x: hidden; position: relative; overscroll-behavior: contain; width: 100%; }
    .chat-header { 
        height: auto; 
        min-height: 64px; 
        padding: 0 16px; 
        padding-top: env(safe-area-inset-top, 0px); 
        display: flex; 
        align-items: center; 
        justify-content: flex-start; 
        gap: 4px; 
        background: var(--bg-secondary, #1A1C20); 
        border-bottom: 1px solid var(--border); 
        z-index: 10; 
        flex-shrink: 0; 
    }
    .chat-contact-info { display: flex; align-items: center; gap: 12px; }
    .chat-avatar { width: 40px; height: 40px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: white; font-weight: bold; overflow: hidden; background: var(--accent); }
    .chat-avatar img { width: 100%; height: 100%; object-fit: cover; }
    .avatar-placeholder { font-size: 14px; }
    .chat-name { font-weight: 600; font-size: 16px; color: white; }
    .chat-status { display: flex; align-items: center; gap: 6px; }
    .status-dot { width: 8px; height: 8px; border-radius: 50%; }
    .status-text { font-size: 12px; color: var(--text-secondary); }

    .messages-container { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 4px; }
    .message { display: flex; margin-bottom: 2px; position: relative; align-items: center; }
    .message.outgoing { justify-content: flex-end; }
    
    .message-bubble-wrapper {
        max-width: 85%;
        display: flex;
        flex-direction: column;
        z-index: 2;
    }
    .message.outgoing .message-bubble-wrapper { align-items: flex-end; }

    .message-bubble { 
        padding: 8px 12px; border-radius: 18px; background: var(--bg-secondary, #1A1C20); color: var(--text-primary); position: relative; box-shadow: 0 2px 5px rgba(0,0,0,0.1); width: fit-content;
    }
    .message.outgoing .message-bubble { background: var(--accent, #1D4ED8); color: white; border-bottom-right-radius: 4px; }
    .message:not(.outgoing) .message-bubble { border-bottom-left-radius: 4px; }

    .swipe-reply-icon {
        position: absolute;
        right: -40px;
        width: 32px;
        height: 32px;
        background: rgba(255, 255, 255, 0.1);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--accent);
        pointer-events: none;
    }

    .message-images { display: grid; gap: 4px; margin-bottom: 6px; border-radius: 8px; overflow: hidden; }
    .msg-img { width: 100%; object-fit: cover; cursor: pointer; background: rgba(0,0,0,0.2); }

    .message-content { 
        overflow-wrap: anywhere; 
        word-break: break-word; 
        max-width: 100%;
    }
    .message-meta { display: flex; align-items: center; gap: 6px; margin-top: 4px; justify-content: flex-end; opacity: 0.7; font-size: 10px; }
    .message-time { white-space: nowrap; }

    .input-area-wrapper { padding: 10px 20px 20px; background: var(--bg-primary); position: sticky; bottom: 0; z-index: 50; border-top: 1px solid var(--border); }
    .input-area { display: flex; align-items: center; gap: 10px; background: var(--bg-secondary); padding: 8px 12px; border-radius: 24px; }
    .message-input { flex: 1; background: transparent; border: none; color: white; outline: none; resize: none; font-size: 15px; max-height: 120px; padding: 8px 0; overflow-y: auto; line-height: 1.4; }

    .btn-icon { background: transparent; border: none; color: var(--text-secondary); cursor: pointer; padding: 8px; border-radius: 50%; display: flex; align-items: center; justify-content: center; transition: background 0.2s; }
    .btn-icon:hover { background: rgba(255,255,255,0.1); color: white; }
    .btn-icon.active { color: var(--accent); }

    .btn-send { width: 40px; height: 40px; border-radius: 50%; background: var(--accent); color: white; border: none; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: transform 0.2s; flex-shrink: 0; }
    .btn-send:hover { transform: scale(1.05); }
    .btn-send:disabled { opacity: 0.5; cursor: not-allowed; }

    .attachment-preview-container { display: flex; align-items: center; justify-content: space-between; background: var(--bg-secondary); border-radius: 12px; margin-bottom: 10px; padding: 10px; }
    .attachment-preview { display: flex; gap: 10px; overflow-x: auto; scrollbar-width: none; }
    .attachment-preview::-webkit-scrollbar { display: none; }
    .preview-item { position: relative; width: 60px; height: 60px; border-radius: 8px; overflow: hidden; flex-shrink: 0; background: rgba(0,0,0,0.2); }
    .preview-item img { width: 100%; height: 100%; object-fit: cover; }
    .btn-remove-att { position: absolute; top: 2px; right: 2px; background: rgba(0,0,0,0.5); color: white; border: none; border-radius: 50%; width: 18px; height: 18px; font-size: 10px; cursor: pointer; }
    
    .preview-actions { padding-left: 10px; border-left: 1px solid var(--border); }
    .btn-toggle-comp { background: transparent; border: none; color: var(--text-secondary); cursor: pointer; padding: 8px; border-radius: 8px; display: flex; align-items: center; justify-content: center; transition: all 0.2s; }
    .btn-toggle-comp.active { color: var(--accent); background: rgba(29, 78, 216, 0.1); }
    .btn-toggle-comp:hover { background: rgba(255,255,255,0.1); }

    .file-attachment-container { display: flex; align-items: center; gap: 8px; background: rgba(0,0,0,0.1); border-radius: 12px; padding: 4px; overflow: hidden; }
    .file-info { flex: 1; overflow: hidden; display: flex; flex-direction: column; justify-content: center; min-width: 0; }
    .file-name { 
        font-weight: 500; font-size: 13px; color: var(--text-primary); 
        white-space: nowrap; overflow: hidden; text-overflow: ellipsis; 
    }
    .file-size { font-size: 11px; color: var(--text-secondary); }
    .btn-file-save { background: transparent; border: none; color: white; opacity: 0.6; cursor: pointer; padding: 8px; border-radius: 50%; transition: opacity 0.2s, background 0.2s; }
    .btn-file-save:hover { opacity: 1; background: rgba(255,255,255,0.1); }
    .icon-svg-xs { width: 14px; height: 14px; }
    .icon-svg-xs :global(svg) { width: 100%; height: 100%; }

    .icon-svg { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; }
    .icon-svg :global(svg) { width: 100%; height: 100%; }
    .icon-svg-sm { width: 18px; height: 18px; display: flex; align-items: center; justify-content: center; }
    .icon-svg-sm :global(svg) { width: 100%; height: 100%; }

    /* Back button */
    .btn-back {
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
        flex-shrink: 0;
    }
    .btn-back:hover { background: rgba(255,255,255,0.1); color: white; }

    /* Mobile-specific overrides */
    .chat-area.mobile .input-area-wrapper { padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 5px)); background: var(--bg-primary); }
    .chat-area.mobile .input-area { padding: 6px 10px; border-radius: 20px; }
    .chat-area.mobile .chat-header { min-height: 56px; padding: 0 12px; padding-top: env(safe-area-inset-top, 0px); }
    /* Fix header alignment for mobile */
    .chat-area.mobile .chat-contact-info { margin-left: auto; flex-direction: row-reverse; }
    .chat-area.mobile .chat-status { flex-direction: row-reverse; }
    .chat-area.mobile .chat-name { text-align: right; }

    /* Reply Styling */
    .reply-preview-bubble {
        background: rgba(0, 0, 0, 0.05);
        border-left: 3px solid var(--accent);
        padding: 4px 8px;
        margin-bottom: 6px;
        border-radius: 4px;
        font-size: 13px;
        cursor: pointer;
        max-width: 100%;
        overflow: hidden;
    }
    .message.outgoing .reply-preview-bubble { background: rgba(255, 255, 255, 0.15); border-left-color: white; }
    .reply-author { font-weight: 600; color: var(--accent); margin-bottom: 2px; font-size: 12px; }
    .message.outgoing .reply-author { color: white; }
    .reply-content-preview { 
        color: var(--text-secondary); 
        white-space: nowrap; 
        overflow: hidden; 
        text-overflow: ellipsis; 
        font-size: 11px; 
        max-width: 100%; 
        display: -webkit-box;
        -webkit-line-clamp: 1;
        -webkit-box-orient: vertical;
        white-space: normal; /* Override nowrap for line-clamping */
    }
    .message.outgoing .reply-content-preview { color: rgba(255, 255, 255, 0.8); }

    .replying-to-bar {
        display: flex;
        align-items: center;
        gap: 12px;
        background: var(--bg-secondary);
        padding: 8px 16px;
        border-top: 1px solid var(--border);
        margin-bottom: 4px;
        border-radius: 12px 12px 0 0;
    }
    .reply-line { width: 3px; height: 32px; background: var(--accent); border-radius: 2px; }
    .reply-info { flex: 1; overflow: hidden; }
    .reply-author-name { font-size: 12px; font-weight: 600; color: var(--accent); margin-bottom: 2px; }
    .reply-text-preview { font-size: 11px; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }
    .btn-cancel-reply { background: transparent; border: none; color: var(--text-secondary); cursor: pointer; padding: 4px; border-radius: 50%; display: flex; transform: rotate(45deg); }
    .btn-cancel-reply:hover { color: #ff6b6b; }

    .btn-scroll-bottom {
        position: fixed;
        bottom: 90px;
        right: 20px;
        width: 44px;
        height: 44px;
        border-radius: 50%;
        background: var(--bg-secondary);
        border: 1px solid var(--border);
        box-shadow: 0 4px 12px rgba(0,0,0,0.3);
        color: var(--accent);
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 90;
        transition: transform 0.2s, background 0.2s;
    }
    .btn-scroll-bottom:hover {
        transform: translateY(-2px);
        background: rgba(255, 255, 255, 0.1);
    }
    
    .loading-overlay {
        position: absolute;
        top: 64px;
        left: 0;
        right: 0;
        bottom: 90px;
        background: var(--bg-primary);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 20;
    }
    .spinner {
        width: 40px;
        height: 40px;
        border: 3px solid rgba(255,255,255,0.1);
        border-top-color: var(--accent);
        border-radius: 50%;
        animation: spin 1s linear infinite;
    }
    @keyframes spin {
        to { transform: rotate(360deg); }
    }

    :global(.highlight-scroll) {
        animation: highlight-flash 2.5s ease-out !important;
    }
    @keyframes highlight-flash {
        0% { filter: brightness(1.7); transform: scale(1.02); box-shadow: 0 0 30px var(--accent); z-index: 10; }
        30% { filter: brightness(1.7); transform: scale(1.02); box-shadow: 0 0 30px var(--accent); z-index: 10; }
        100% { filter: brightness(1); transform: scale(1); box-shadow: none; z-index: 1; }
    }

    .message-bubble.circle-only-bubble,
    .message.outgoing .message-bubble.circle-only-bubble {
        background: transparent !important;
        box-shadow: none !important;
        padding: 2px 0 !important;
        border: none !important;
    }
    .chat-area.mobile .chat-header {
        display: flex !important;
        align-items: center !important;
        justify-content: space-between !important;
        width: 100% !important;
        box-sizing: border-box !important;
        padding: 0 12px !important;
    }
    .chat-area.mobile .chat-contact-info {
        margin-left: 0 !important;
        flex-direction: row !important;
        flex: 1 !important;
        min-width: 0 !important;
        overflow: hidden !important;
    }
    .chat-area.mobile .chat-name {
        text-align: left !important;
        white-space: nowrap !important;
        overflow: hidden !important;
        text-overflow: ellipsis !important;
        max-width: 145px !important;
    }
    .chat-area.mobile .chat-status {
        flex-direction: row !important;
    }
    

    video::-webkit-media-controls-overlay-play-button,
    video::-webkit-media-controls-start-playback-button {
        display: none !important;
        -webkit-appearance: none !important;
        opacity: 0 !important;
    }
    

    .chat-header {
        background: rgba(20, 22, 28, 0.88) !important;
        backdrop-filter: blur(16px) !important;
        border-bottom: 1px solid rgba(255,255,255,0.05) !important;
    }
    .chat-contact-info {
        background: rgba(255, 255, 255, 0.04);
        padding: 5px 14px 5px 6px;
        border-radius: 100px;
        border: 1px solid rgba(255, 255, 255, 0.05);
    }
    .messages-container {
        background-color: #0E1015 !important;
        background-image: radial-gradient(rgba(59, 130, 246, 0.06) 1.5px, transparent 1.5px), radial-gradient(rgba(255, 255, 255, 0.03) 1px, #0E1015 1px) !important;
        background-size: 36px 36px !important;
        background-position: 0 0, 18px 18px !important;
    }
    .message.outgoing .message-bubble:not(.circle-only-bubble) {
        background: linear-gradient(135deg, #2B5278, #1E3A8A) !important;
        border-radius: 20px 20px 4px 20px !important;
    }
    .message:not(.outgoing) .message-bubble:not(.circle-only-bubble) {
        background: #1C1F26 !important;
        border-radius: 20px 20px 20px 4px !important;
    }
    
</style>
