package com.castbrowser.app.browser

object AdBlockEngine {
    private val BLOCKED_HOSTS = setOf(
        "doubleclick.net",
        "googleads.g.doubleclick.net",
        "googlesyndication.com",
        "adservice.google.com",
        "adnxs.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "adsterra.com",
        "monetag.com",
        "juicyads.com",
        "trafficjunky.com",
        "outbrain.com",
        "taboola.com",
        "criteo.com",
        "rubiconproject.com",
        "openx.net",
        "pubmatic.com",
        "yieldmo.com",
        "bet365.com",
        "1xbet.com",
        "onclickpredictiv.com",
        "onclickperformance.com",
        "deloton.com",
        "alwingulla.com",
        "wigetmedia.com",
        "hilltopads.net",
        "clickadu.com",
        "clickaine.com",
        "adcash.com",
        "bidvertiser.com",
        "ero-advertising.com",
        "trafficstars.com",
        "plugrush.com",
        "richpush.co",
        "evadav.com",
        "rollerads.com",
        "yengo.com",
        "mgid.com"
    )

    fun isAdOrTracker(url: String): Boolean {
        val lower = url.lowercase()
        // Block external app intent redirects, market urls, etc.
        if (lower.startsWith("intent:") || lower.startsWith("market:") || lower.startsWith("whatsapp:")) {
            return true
        }

        val host = try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host.isEmpty()) return false

        if (BLOCKED_HOSTS.any { blocked -> host == blocked || host.endsWith(".$blocked") }) {
            return true
        }

        // Generic keyword check for popup ad trackers
        return host.contains("popunder") ||
                host.contains("poptm") ||
                host.contains("propeller") ||
                host.contains("adnetwork") ||
                host.contains("trackvoluum")
    }
}
