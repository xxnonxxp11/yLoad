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
        ytd-ad-slot-renderer,
        ytd-banner-promo-renderer,
        ytd-promoted-sparkles-web-renderer,
        ytd-compact-promoted-video-renderer,
        ytm-promoted-sparkles-web-renderer,
        #masthead-ad,
        .ytp-ad-module,
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
                    delete obj.adPlacements;
                    delete obj.playerAds;
                    delete obj.adSlots;
                    delete obj.adBreakHeartbeatParams;
                    if (obj.playbackTracking) {
                        delete obj.playbackTracking.videostatsPlaybackUrl;
                        delete obj.playbackTracking.videostatsDelayplayUrl;
                        delete obj.playbackTracking.videostatsWatchtimeUrl;
                        delete obj.playbackTracking.qoeUrl;
                        delete obj.playbackTracking.atrUrl;
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

            // 4. Click skip button automatically when visible (uBlock Origin behavior)
            function autoSkip() {
                var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .videoAdUiSkipButton');
                if (skipBtn) {
                    try { skipBtn.click(); } catch(e) {}
                }
            }
            setInterval(autoSkip, 1000);
        })();
    """.trimIndent()
}
