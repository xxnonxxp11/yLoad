package com.cookiegames.smartcookie.adblock

import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-performance ad defuser inspired by uBlock Origin's scriptlets and cosmetic filters.
 * Neutralizes video ad placements (YouTube) and cosmetic ad containers with zero latency.
 */
@Singleton
class UBlockAdDefuser @Inject constructor() {

    /**
     * Universal cosmetic stylesheet that hides ad banners, sticky overlays, and auto-placed ad units.
     * Evaluated natively by the browser's CSS rendering engine with GPU acceleration.
     */
    val cosmeticAdFilterCss: String = """
        #cts_test,
        #ad_ctd,
        #s_test_ads,
        #s_test_pagead,
        ins.adsbygoogle,
        [class*="google-auto-placed"],
        [id*="google_ads_"],
        [class*="banner-ad"],
        [class*="ad-banner"],
        [class*="ad-container"],
        [class*="ads-wrapper"],
        [class*="ad-slot"],
        [class*="ad_slot"],
        [id*="taboola-"],
        [id*="outbrain-"],
        [class*="outbrain_"],
        [class*="rc-ad"],
        [data-ad-unit],
        [data-ad-slot],
        [data-ad-client],
        .sponsored-post,
        .ad-header,
        .ad-footer,
        .ad-sidebar,
        [aria-label="advertisement" i],
        [aria-label="sponsored" i],
        #yandex_rtb_R-A-491776-1,
        [id*="yandex_rtb_"],
        [class*="yandex-rtb"],
        [data-ads],
        .includeWrapper,
        ytd-ad-slot-renderer,
        ytd-banner-promo-renderer,
        ytd-promoted-sparkles-web-renderer,
        ytd-compact-promoted-video-renderer,
        ytm-promoted-sparkles-web-renderer,
        #player-ads,
        #masthead-ad,
        .ytp-ad-module,
        .ytp-ad-overlay-container,
        .ytp-ad-message-container,
        .video-ads {
            display: none !important;
            height: 0 !important;
            min-height: 0 !important;
            visibility: hidden !important;
            pointer-events: none !important;
        }
    """.trimIndent().replace("\n", " ").replace("\r", "")

    /**
     * JavaScript snippet that injects the cosmetic stylesheet into the DOM as early as possible.
     */
    val cosmeticInjectionJs: String
        get() = """
        (function() {
            var cssId = 'yload-ublock-cosmetic';
            if (document.getElementById(cssId)) return;
            var style = document.createElement('style');
            style.id = cssId;
            style.type = 'text/css';
            style.textContent = '$cosmeticAdFilterCss';
            var target = document.head || document.documentElement;
            if (target) {
                target.appendChild(style);
            }
        })();
    """.trimIndent()

    /**
     * Scriptlet that intercepts fetch & XHR to immediately reject ad and tracking requests.
     */
    val generalAdDefuserJs: String = """
        (function() {
            if (window.__yload_ad_defuser__) return;
            window.__yload_ad_defuser__ = true;

            var adPattern = /(?:doubleclick|googleads|adservice\.google|googlesyndication|google-analytics|criteo|taboola|outbrain|adnxs|pubmatic|rubiconproject|openx|scorecardresearch|chartbeat|hotjar|clarity\.ms|sentry\.io|bugsnag|luckyorange|mouseflow|fakepage\.html|\/pagead\/|\/ads?\.js|advert|tracking|analytics)/i;

            if (window.fetch) {
                var _fetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    if (url && adPattern.test(url)) {
                        return Promise.reject(new TypeError('Failed to fetch (Blocked by yLoad AdBlock)'));
                    }
                    return _fetch.apply(this, arguments);
                };
            }

            if (window.XMLHttpRequest) {
                var _open = XMLHttpRequest.prototype.open;
                var _send = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__blocked = (typeof url === 'string') && adPattern.test(url);
                    return _open.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    if (this.__blocked) {
                        var self = this;
                        setTimeout(function() {
                            if (self.onerror) self.onerror(new ProgressEvent('error'));
                        }, 1);
                        return;
                    }
                    return _send.apply(this, arguments);
                };
            }
        })();
    """.trimIndent()

    /**
     * uBlock Origin-style scriptlet defuser for YouTube.
     * 1. Prunes ad placements from ytInitialPlayerResponse.
     * 2. Intercepts fetch & XHR requests to /youtubei/v1/player.
     * 3. Fallback: Fast-forwards and auto-skips any video ads.
     */
    val youtubeAdDefuserJs: String = """
        (function() {
            if (window.__yload_ublock_yt_defuser__) return;
            window.__yload_ublock_yt_defuser__ = true;

            function pruneAds(obj) {
                if (!obj || typeof obj !== 'object') return obj;
                try {
                    if (obj.adPlacements) obj.adPlacements = [];
                    if (obj.playerAds) obj.playerAds = [];
                    if (obj.adSlots) obj.adSlots = [];
                    delete obj.adBreakHeartbeatParams;
                    if (obj.playbackTracking) {
                        delete obj.playbackTracking.videostatsPlaybackUrl;
                        delete obj.playbackTracking.videostatsDelayplayUrl;
                        delete obj.playbackTracking.videostatsWatchtimeUrl;
                        delete obj.playbackTracking.qoeUrl;
                        delete obj.playbackTracking.atrUrl;
                        delete obj.playbackTracking.ptrackingUrl;
                    }
                    if (obj.playerConfig && obj.playerConfig.adConfig) {
                        delete obj.playerConfig.adConfig;
                    }
                } catch(e) {}
                return obj;
            }

            // 1. Trap ytInitialPlayerResponse
            var _ytPlayerResp = window.ytInitialPlayerResponse;
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
                get: function() { return _ytPlayerResp; },
                set: function(val) {
                    _ytPlayerResp = pruneAds(val);
                },
                configurable: true,
                enumerable: true
            });
            if (_ytPlayerResp) pruneAds(_ytPlayerResp);

            // 2. Intercept Fetch API for /youtubei/v1/player
            if (window.fetch) {
                var originalFetch = window.fetch;
                window.fetch = function() {
                    var args = arguments;
                    var url = args[0] ? (args[0].url || args[0].toString()) : '';
                    if (typeof url === 'string' && url.indexOf('/youtubei/v1/player') !== -1) {
                        return originalFetch.apply(this, args).then(function(response) {
                            return response.clone().json().then(function(data) {
                                var pruned = pruneAds(data);
                                return new Response(JSON.stringify(pruned), {
                                    status: response.status,
                                    statusText: response.statusText,
                                    headers: response.headers
                                });
                            }).catch(function() {
                                return response;
                            });
                        });
                    }
                    return originalFetch.apply(this, args);
                };
            }

            // 3. Intercept XMLHttpRequest for /youtubei/v1/player
            if (window.XMLHttpRequest) {
                var originalXhrOpen = XMLHttpRequest.prototype.open;
                var originalXhrSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__url = url;
                    return originalXhrOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    if (this.__url && typeof this.__url === 'string' && this.__url.indexOf('/youtubei/v1/player') !== -1) {
                        this.addEventListener('readystatechange', function() {
                            if (this.readyState === 4 && this.responseText) {
                                try {
                                    var data = JSON.parse(this.responseText);
                                    pruneAds(data);
                                    Object.defineProperty(this, 'responseText', { value: JSON.stringify(data), writable: false });
                                    Object.defineProperty(this, 'response', { value: JSON.stringify(data), writable: false });
                                } catch(e) {}
                            }
                        });
                    }
                    return originalXhrSend.apply(this, arguments);
                };
            }

            // 4. Auto-skip video ads and fast-forward in 300ms safely
            function autoSkip() {
                var player = document.querySelector('.html5-video-player');
                var isAdActive = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
                if (isAdActive) {
                    var video = player.querySelector('video') || document.querySelector('video');
                    if (video) {
                        try {
                            video.muted = true;
                            video.playbackRate = 16.0;
                            if (isFinite(video.duration) && video.duration > 0) {
                                video.currentTime = video.duration;
                            }
                        } catch(e) {}
                    }
                }
                var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button, .videoAdUiSkipButton');
                if (skipBtn) {
                    try { skipBtn.click(); } catch(e) {}
                }
            }
            setInterval(autoSkip, 300);
        })();
    """.trimIndent()
}
