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
                }
            }
            return originalOpen.apply(this, arguments);
        };
    }

    // 4. MutationObserver for video tags
    const observer = new MutationObserver(function(mutations) {
        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (node.tagName === 'VIDEO' && node.src) {
                    notifyStream(node.src, 'video_node');
                } else if (node.tagName === 'SOURCE' && node.src) {
                    notifyStream(node.src, 'source_node');
                }
            }
        }
    });
    observer.observe(document.documentElement || document, { childList: true, subtree: true });

    // 5. Watch playback progress
    setInterval(function() {
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
