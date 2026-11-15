export function getInitials(name) {
    if (!name) return 'H';
    const clean = String(name)
        .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, '')
        .replace(/^[@+#\s]+/, '')
        .trim();
    if (!clean) return '👥';
    const words = clean.split(/\s+/).filter(Boolean);
    if (words.length >= 2) {
        return (Array.from(words[0])[0] + Array.from(words[1])[0]).toUpperCase();
    }
    return Array.from(clean).slice(0, 2).join('').toUpperCase();
}

export function formatPreviewText(raw) {
    if (!raw) return 'Нет сообщений';
    const s = String(raw).trim();
    if (s.startsWith('[HYDRA_VOICE]:')) return '🎤 Голосовое сообщение';
    if (s.startsWith('[HYDRA_CIRCLE]:')) return '📹 Видео-кружок';
    if (s.startsWith('[HYDRA_VIDEO]:')) return '🎬 Видеоролик';
    if (s.startsWith('[HYDRA_GROUP_INVITE]:') || s.startsWith('SFlEUkFfUk9PTV')) return '🎟️ Приглашение в группу/канал';
    if (s.startsWith('[HYDRA_CALL_RING]:')) return '📞 Входящий вызов...';
    if (s.startsWith('[HYDRA_CALL_ANSWER]:')) return '🟢 Вызов принят';
    if (s.startsWith('[HYDRA_CALL_END]:')) return '☎️ Вызов завершён';
    if (s.startsWith('[HYDRA_CALL_MISSED]:')) return '☎️ Пропущенный звонок';
    if (s.startsWith('[HYDRA_CALL_ICE]:')) return '🔄 Установка соединения...';
    return s;
}

export function getAvatarGradient(name) {
    if (!name) return 'linear-gradient(135deg, #1E3A8A, #1D4ED8)';
    let hash = 0;
    for (let i = 0; i < name.length; i++) {
        hash = name.charCodeAt(i) + ((hash << 5) - hash);
    }
    const palettes = [
        ['#172554', '#1D4ED8'],
        ['#1E3A8A', '#2563EB'],
        ['#0F172A', '#1E40AF'],
        ['#0369A1', '#1D4ED8'],
        ['#0D9488', '#1E3A8A'],
        ['#1E293B', '#3B82F6'],
        ['#0284C7', '#1E3A8A'],
        ['#115E59', '#0369A1']
    ];
    const idx = Math.abs(hash) % palettes.length;
    return `linear-gradient(135deg, ${palettes[idx][0]}, ${palettes[idx][1]})`;
}

export function formatTime(timestamp) {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function getStatusColor(status) {
    switch (status) {
        case 'online': return '#4CAF50';
        case 'connecting': return '#FFC107';
        case 'starting': return '#3B82F6';
        case 'error': return '#F44336';
        default: return '#9E9E9E';
    }
}

export function getStatusText(status) {
    switch (status) {
        case 'online': return 'В сети';
        case 'connecting': return 'Подключение...';
        case 'starting': return 'Подключение к узлу Hydra...';
        case 'error': return 'Ожидание сети';
        default: return 'Оффлайн';
    }
}

export function parseMarkdown(text) {
    if (!text) return '';
    let html = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    html = html.replace(/```([\s\S]*?)```/g, '<pre class="md-code-block">$1</pre>');
    html = html.replace(/`([^`]+)`/g, '<code class="md-code">$1</code>');
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/__([^_]+)__/g, '<strong>$1</strong>');
    html = html.replace(/(?<!\w)\*([^*]+)\*(?!\w)/g, '<em>$1</em>');
    html = html.replace(/(?<!\w)_([^_]+)_(?!\w)/g, '<em>$1</em>');
    html = html.replace(/~~([^~]+)~~/g, '<del>$1</del>');
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    html = html.replace(/<[^>]+>|https?:\/\/[^\s<]+/g, (match) => {
        if (match.startsWith('<')) return match;
        return `<a href="${match}" target="_blank" rel="noopener noreferrer">${match}</a>`;
    });
    return html.replace(/\n/g, '<br>');
}
