package com.omnideck.shell.navigation

import android.net.Uri
import com.omnideck.sdk.Route

/**
 * Turns anything the OS hands the Shell into an OmniDeck [Route] (OD-204).
 *
 * There are three external entry points — the `omnideck://` scheme, an https App Link,
 * and an app shortcut — and all three converge here so that the Router remains the
 * single place that decides what a route means. A module never learns which door the
 * user came through.
 *
 * App Link *verification* is deliberately not on yet: `autoVerify` requires a
 * published `assetlinks.json` at [WEB_HOST], which is a store-readiness task
 * (OD-321). Until then the filter still works — Android just shows a
 * disambiguation chooser rather than opening OmniDeck directly.
 */
object ExternalRoutes {

    /** The web origin that mirrors the app's URI space. */
    const val WEB_HOST = "omnideck.app"

    /**
     * Returns the route this URI addresses, or null if it addresses nothing we own.
     *
     * Null is the important case: an intent can carry anything, including a URI aimed
     * at another app entirely, and treating an unknown one as a route would hand
     * arbitrary external input to the Router as if the user had asked for it.
     */
    fun from(uri: Uri?): Route? {
        val parsed = uri ?: return null
        return when (parsed.scheme?.lowercase()) {
            Route.SCHEME -> parsed.toString().takeIf { it.startsWith(Route.SCHEME_PREFIX) }?.let(::Route)
            HTTPS -> fromWeb(parsed)
            else -> null
        }
    }

    /**
     * `https://omnideck.app/<module>/item/42?x=1` becomes `omnideck://<module>/item/42?x=1`.
     *
     * The path maps one-to-one on purpose: a link that works in a browser and a link
     * that works in the app are then the same link, which is what makes shared URLs
     * and email deep links work without a per-module redirect table.
     */
    private fun fromWeb(uri: Uri): Route? {
        if (!uri.host.equals(WEB_HOST, ignoreCase = true)) return null
        val path = uri.path.orEmpty().trim('/')
        if (path.isEmpty()) return null
        val query = uri.query?.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()
        return Route("${Route.SCHEME_PREFIX}$path$query")
    }

    private const val HTTPS = "https"
}
