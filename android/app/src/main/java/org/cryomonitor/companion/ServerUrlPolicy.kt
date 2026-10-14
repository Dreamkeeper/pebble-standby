package org.cryomonitor.companion

import java.net.URI

/**
 * Where the companion may speak plain HTTP (owner decision 2026-10-14,
 * audit finding 12). HTTPS is accepted anywhere. Cleartext is accepted
 * only where the wire is already private: the Tailscale range and its
 * MagicDNS names, the RFC 1918 home-network ranges, loopback and local
 * names. Everything else must be https://, so a hostile Wi-Fi cannot
 * downgrade the server link. Android's network security config cannot
 * express address ranges, so this is the enforcement point: every server
 * call goes through ServerClient, which refuses a URL this rejects.
 */
object ServerUrlPolicy {
    enum class Problem { NOT_A_URL, NOT_HTTP, CLEARTEXT_NOT_PRIVATE }

    private val privateSuffixes = listOf(".ts.net", ".local", ".lan", ".home.arpa", ".internal")

    /** null when the URL is acceptable. */
    fun check(url: String): Problem? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return Problem.NOT_A_URL
        val scheme = u.scheme?.lowercase() ?: return Problem.NOT_A_URL
        val host = u.host?.lowercase()?.trim('[', ']') ?: return Problem.NOT_A_URL
        return when (scheme) {
            "https" -> null
            "http" -> if (isPrivateHost(host)) null else Problem.CLEARTEXT_NOT_PRIVATE
            else -> Problem.NOT_HTTP
        }
    }

    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        if (privateSuffixes.any { host.endsWith(it) }) return true
        if (host.startsWith("fd") || host.startsWith("fc") || host.startsWith("fe80:")) return true // IPv6 ULA / link-local
        val o = host.split('.').map { it.toIntOrNull() ?: return false }
        if (o.size != 4 || o.any { it !in 0..255 }) return false
        return when {
            o[0] == 10 -> true
            o[0] == 127 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 100 && o[1] in 64..127 -> true   // Tailscale CGNAT range 100.64.0.0/10
            else -> false
        }
    }
}
