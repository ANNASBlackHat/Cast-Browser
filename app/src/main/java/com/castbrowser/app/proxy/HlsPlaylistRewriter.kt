package com.castbrowser.app.proxy

object HlsPlaylistRewriter {

    fun rewritePlaylist(
        playlistContent: String,
        baseMediaUrl: String,
        proxyHostPort: String,
        sessionToken: String
    ): String {
        val lines = playlistContent.lines()
        val rewritten = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                rewritten.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                // If it's a URI tag like #EXT-X-KEY or #EXT-X-MAP, rewrite the URI attribute
                if (trimmed.contains("URI=\"")) {
                    val newTag = rewriteUriAttribute(trimmed, baseMediaUrl, proxyHostPort, sessionToken)
                    rewritten.append(newTag).append("\n")
                } else {
                    rewritten.append(trimmed).append("\n")
                }
            } else {
                // This is a media segment or sub-playlist URI line
                val absoluteUri = resolveUri(baseMediaUrl, trimmed)
                val proxiedUri = buildProxiedUrl(proxyHostPort, sessionToken, absoluteUri)
                rewritten.append(proxiedUri).append("\n")
            }
        }

        return rewritten.toString()
    }

    private fun rewriteUriAttribute(
        tag: String,
        baseMediaUrl: String,
        proxyHostPort: String,
        sessionToken: String
    ): String {
        val regex = Regex("""URI="([^"]+)"""")
        return regex.replace(tag) { match ->
            val origUri = match.groupValues[1]
            val absoluteUri = resolveUri(baseMediaUrl, origUri)
            val proxiedUri = buildProxiedUrl(proxyHostPort, sessionToken, absoluteUri)
            """URI="$proxiedUri""""
        }
    }

    fun resolveUri(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            val base = java.net.URI(baseUrl)
            base.resolve(relativeOrAbsolute).toString()
        } catch (_: Exception) {
            relativeOrAbsolute
        }
    }

    private fun buildProxiedUrl(proxyHostPort: String, sessionToken: String, targetUrl: String): String {
        val encoded = java.net.URLEncoder.encode(targetUrl, "UTF-8")
        return "http://$proxyHostPort/proxy?token=$sessionToken&url=$encoded"
    }
}
