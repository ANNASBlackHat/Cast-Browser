(function() {
    if (window.__castBrowserInjected) return;
    window.__castBrowserInjected = true;

    function notifyStream(url, source) {
        if (!url || typeof url !== 'string') return;
        if (url.startsWith('blob:') || url.startsWith('data:')) return;
        try {
            if (window.CastBrowserBridge && window.CastBrowserBridge.postMessage) {
                window.CastBrowserBridge.postMessage(JSON.stringify({
                    type: 'STREAM_CANDIDATE',
                    url: url,
                    source: source,
                    title: document.title || ''
                }));
            }
        } catch(e) {}
    }

    function notifySubtitle(url, label, lang) {
        if (!url || typeof url !== 'string') return;
        if (url.startsWith('blob:') || url.startsWith('data:')) return;
        try {
            if (window.CastBrowserBridge && window.CastBrowserBridge.postMessage) {
                window.CastBrowserBridge.postMessage(JSON.stringify({
                    type: 'SUBTITLE_CANDIDATE',
                    url: url,
                    label: label || 'Subtitle',
                    language: lang || ''
                }));
            }
        } catch(e) {}
    }

    function scanTracks() {
        try {
            const tracks = document.querySelectorAll('track');
            for (const t of tracks) {
                if (t.src) {
                    notifySubtitle(t.src, t.label || t.getAttribute('label') || 'Subtitle', t.srclang || t.getAttribute('srclang') || '');
                }
            }
        } catch(e) {}
    }

    // 1. Hook HTMLMediaElement src
    const originalSrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (originalSrc && originalSrc.set) {
        Object.defineProperty(HTMLMediaElement.prototype, 'src', {
            set: function(val) {
                notifyStream(val, 'media_src');
                return originalSrc.set.call(this, val);
            },
            get: originalSrc.get
        });
    }

    // 2. Hook fetch
    const originalFetch = window.fetch;
    if (originalFetch) {
        window.fetch = function(input, init) {
            const url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
            if (url) {
                const lower = url.toLowerCase();
                if (lower.includes('.m3u8') || lower.includes('.mpd') || lower.includes('.mp4') || lower.includes('videoplayback')) {
                    notifyStream(url, 'fetch');
                } else if (lower.includes('.vtt') || lower.includes('.srt') || lower.includes('.ass') || lower.includes('.ssa')) {
                    notifySubtitle(url, 'Detected Subtitle', '');
                }
            }
            return originalFetch.apply(this, arguments);
        };
    }

    // 3. Hook XMLHttpRequest
    const originalOpen = XMLHttpRequest.prototype.open;
    if (originalOpen) {
        XMLHttpRequest.prototype.open = function(method, url) {
            if (url && typeof url === 'string') {
                const lower = url.toLowerCase();
                if (lower.includes('.m3u8') || lower.includes('.mpd') || lower.includes('.mp4') || lower.includes('videoplayback')) {
                    notifyStream(url, 'xhr');
                } else if (lower.includes('.vtt') || lower.includes('.srt') || lower.includes('.ass') || lower.includes('.ssa')) {
                    notifySubtitle(url, 'Detected Subtitle', '');
                }
            }
            return originalOpen.apply(this, arguments);
        };
    }

    // 4. MutationObserver for video and track tags
    const observer = new MutationObserver(function(mutations) {
        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (node.tagName === 'VIDEO') {
                    if (node.src) notifyStream(node.src, 'video_node');
                    const tracks = node.querySelectorAll('track');
                    for (const t of tracks) {
                        if (t.src) notifySubtitle(t.src, t.label, t.srclang);
                    }
                } else if (node.tagName === 'SOURCE' && node.src) {
                    notifyStream(node.src, 'source_node');
                } else if (node.tagName === 'TRACK' && node.src) {
                    notifySubtitle(node.src, node.label, node.srclang);
                }
            }
        }
    });
    observer.observe(document.documentElement || document, { childList: true, subtree: true });

    // Initial scan
    scanTracks();

    // 5. Watch playback progress & periodic track scan
    setInterval(function() {
        scanTracks();
        const videos = document.querySelectorAll('video');
        for (const v of videos) {
            if (!v.paused && v.duration > 0 && v.currentTime > 5) {
                try {
                    if (window.CastBrowserBridge && window.CastBrowserBridge.postMessage) {
                        window.CastBrowserBridge.postMessage(JSON.stringify({
                            type: 'PLAYBACK_PROGRESS',
                            currentTime: Math.floor(v.currentTime),
                            duration: Math.floor(v.duration),
                            url: v.currentSrc || v.src || window.location.href,
                            title: document.title || ''
                        }));
                    }
                } catch(e) {}
                break;
            }
        }
    }, 5000);
})();
