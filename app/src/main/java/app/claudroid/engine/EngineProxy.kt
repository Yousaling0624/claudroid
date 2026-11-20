package app.claudroid.engine

import android.content.Context
import android.net.ConnectivityManager
import app.claudroid.data.AppSettings
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * Resolves the HTTP proxy that Claude Code and the workspace terminal should use.
 *
 * `targetSdk 28` keeps the CLI executable, but it also means Android's per-app VPN
 * routing only covers this process when the user includes the app: a browser can reach
 * GitHub while the jail has no route at all. The CLI does not read Android's system
 * proxy either, so the only supported way to route WebFetch / curl / git through the
 * user's VPN HTTP port is to export the standard proxy variables into every child.
 *
 * Proxy URLs may embed userinfo (for example `http://user@host:port`), so this object
 * never logs them. Callers that need a log line must record only the source
 * (manual/system).
 */
internal object EngineProxy {

    private val SUPPORTED_SCHEMES = setOf("http", "https")

    /**
     * Canonicalizes a user- or system-supplied proxy URL.
     *
     * Claude Code only understands HTTP/HTTPS proxies (SOCKS is not supported), so a
     * SOCKS value is rejected instead of silently producing a dead environment. A bare
     * `host:port` is what users type into proxy fields, hence the implicit `http://`.
     * Paths and queries are dropped: the CLI and curl only consume the origin.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in SUPPORTED_SCHEMES) return null
        // A malformed authority (`http://host:abc`) leaves host null; an empty host is
        // equally unusable. Both must not reach the child environment.
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        val userInfo = uri.rawUserInfo?.let { "$it@" } ?: ""
        val portSuffix = if (port == -1) "" else ":$port"
        return "$scheme://$userInfo$host$portSuffix"
    }

    /**
     * Environment for one turn or terminal command.
     *
     * Both upper- and lowercase names are written because tools disagree (curl checks
     * lowercase, most runtimes prefer uppercase). `NO_PROXY` always keeps loopback so
     * the in-app OpenAI bridge at `127.0.0.1` never goes through the user's VPN; a local
     * or LAN Base URL is added for the same reason, while a public API host is not —
     * that traffic is exactly what the proxy is for.
     */
    fun env(proxy: String, apiBaseUrl: String, apiIsLocal: Boolean): Map<String, String> {
        val noProxy = mutableListOf("localhost", "127.0.0.1", "::1")
        if (apiIsLocal) {
            runCatching { URI(apiBaseUrl).host }.getOrNull()
                ?.removeSurrounding("[", "]")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { host -> if (host !in noProxy) noProxy += host }
        }
        val noProxyValue = noProxy.joinToString(",")
        return mapOf(
            "HTTPS_PROXY" to proxy,
            "https_proxy" to proxy,
            "HTTP_PROXY" to proxy,
            "http_proxy" to proxy,
            "NO_PROXY" to noProxyValue,
            "no_proxy" to noProxyValue,
        )
    }

    /**
     * Best-effort system HTTP proxy. `LinkProperties.httpProxy` is what Android VPN apps
     * and Wi-Fi settings populate, so it covers the "VPN excluded this app but exposes a
     * local HTTP port" setup the user actually has. `ProxySelector` is the fallback for
     * networks that configure the proxy through Java system properties instead.
     *
     * Everything is guarded: connectivity APIs throw on some ROMs without the network
     * permission, and a failed probe must mean "no proxy", never a broken turn.
     */
    fun systemProxy(ctx: Context): String? {
        val linkProxy = runCatching {
            val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = manager?.activeNetwork ?: return@runCatching null
            manager.getLinkProperties(network)?.httpProxy
        }.getOrNull()
        if (linkProxy != null) {
            // A PAC script has no fixed host/port and resolving it is a browser's job;
            // treat it as absent and let the ProxySelector fallback try.
            val hasPac = !linkProxy.pacFileUrl?.toString().isNullOrEmpty()
            if (!hasPac) {
                val host = linkProxy.host?.trim().orEmpty()
                if (host.isNotEmpty() && linkProxy.port in 1..65535) {
                    // normalize may still reject an unusable host; fall through to the
                    // selector rather than giving up on the system proxy entirely.
                    normalize(httpUrl(host, linkProxy.port))?.let { return it }
                }
            }
        }

        val selected = runCatching {
            ProxySelector.getDefault()
                ?.select(URI("https://api.anthropic.com"))
                ?.firstOrNull { it.type() == Proxy.Type.HTTP && it.address() is InetSocketAddress }
                ?.address() as? InetSocketAddress
        }.getOrNull()
        if (selected != null) {
            val host = selected.hostString?.trim().orEmpty()
            if (host.isNotEmpty() && selected.port in 1..65535) {
                normalize(httpUrl(host, selected.port))?.let { return it }
            }
        }
        return null
    }

    /**
     * The proxy for this request, or null for a direct connection.
     *
     * A configured value is authoritative: an invalid manual proxy means "no proxy"
     * rather than a silent fallback to the system one, because the user explicitly
     * overrode it and mixing the two would make the effective route unpredictable.
     * Re-read on every turn, so a settings change or network switch applies immediately.
     */
    fun effective(settings: AppSettings, ctx: Context): String? {
        val manual = settings.networkProxy.trim()
        return if (manual.isNotEmpty()) normalize(manual) else systemProxy(ctx)
    }

    /** [ProxyInfo] carries host and port separately and never a scheme. */
    private fun httpUrl(host: String, port: Int): String {
        // ProxyInfo reports IPv6 literals bare; a URI authority needs the brackets.
        val authority = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "http://$authority:$port"
    }
}
