package com.omnideck.shell.navigation

import android.net.Uri
import com.omnideck.sdk.Route

/**
 * Turns anything the OS hands the Shell into an OmniDeck [Route] (OD-204).
 *
 * External entry points — the `omnideck://` scheme, an app shortcut, and an https App
 * Link — all converge here so that the Router remains the single place that decides what
 * a route means. A module never learns which door the user came through, and no external
 * URI reaches [Route] without passing the normalisation here — lowercased scheme, no
 * fragment, a host that is actually there.
 *
 * **The https door is currently shut at the manifest** (OD-716). OmniDeck owns no web
 * origin, so there is no `<data android:scheme="https">` filter and the OS never routes a
 * web URL here. [fromWeb] is kept, documented and tested rather than deleted: the mapping
 * and the reserved prefix are decided (ADR-011), and restoring the door is a manifest
 * change plus a published `assetlinks.json`, not a redesign.
 *
 * Nothing else depends on that. The custom scheme needs no domain and carries every
 * internal link, notification tap and shortcut; what the missing origin costs is link
 * *shareability* — an `omnideck://` URL is not clickable in an inbox — not function.
 */
object ExternalRoutes {

    /**
     * The web origin that mirrors the app's URI space — **a placeholder, not a claim**.
     *
     * `omnideck.app` is registered to someone else. It stays here as the shape of the
     * thing rather than a live host: nothing in the manifest points at it, so [fromWeb]
     * is unreachable from the OS until OD-716 stands up an origin we control. Whoever
     * does that replaces this constant and restores the filter in the same change.
     */
    const val WEB_HOST = "omnideck.app"

    /**
     * The reserved path prefix that mirror lives under, e.g.
     * `https://omnideck.app/go/notes/note/42` (ADR-011, architecture.md §10.1).
     *
     * Everything outside it belongs to the website and has to keep opening in a
     * browser — the account-deletion page above all, which Play requires to be reachable
     * without the app installed (architecture.md §19.2). When the filter is restored it
     * must carry the same prefix, because a filter on the bare host stops the browser
     * being offered for those URLs at all once `autoVerify` is on.
     *
     * Changing it is not a local edit: it breaks every link already shared under the
     * old prefix, so it has to be settled before `assetlinks.json` is published
     * (OD-321). The two places that must agree are this constant and the manifest's
     * `android:pathPrefix` — the manifest decides what the app is offered, this
     * decides what it does with it.
     */
    const val WEB_PATH_PREFIX = "/go/"

    /**
     * Returns the route this URI addresses, or null if it addresses nothing we own.
     *
     * Null is the important case: an intent can carry anything, including a URI aimed
     * at another app entirely, and treating an unknown one as a route would hand
     * arbitrary external input to the Router as if the user had asked for it.
     */
    fun from(uri: Uri?): Route? {
        val parsed = uri?.normalizeScheme() ?: return null
        return when (parsed.scheme) {
            Route.SCHEME -> fromAppScheme(parsed)
            HTTPS -> fromWeb(parsed)
            else -> null
        }
    }

    /**
     * `omnideck://<module>/item/42` passes through, minus any fragment.
     *
     * [Uri.normalizeScheme] has lowercased the scheme before the prefix check, so a
     * sender that writes `OMNIDECK://` gets the same route as one that writes
     * `omnideck://` — the tolerance the https branch has always had, and the OS does
     * not promise to normalise intent data on our behalf.
     *
     * The fragment is cut here rather than understood downstream, which is the same
     * thing [fromWeb] does and for a sharper reason: [Route] knows one delimiter, so a
     * surviving `#` is not ignored, it is absorbed. `omnideck://n/note/42#c` binds a
     * `{noteId}` of `42#c`, and `?omnideck_result_to=abc#c` yields a correlation id of
     * `abc#c` that matches nothing the Router issued. Intent data is attacker-supplied,
     * so both doors normalise it in the one place that sees external input.
     *
     * The host check is the counterpart of [fromWeb]'s empty-path guard: `omnideck://`
     * alone satisfies [Route]'s prefix requirement while addressing nothing, and a
     * Route with an empty host resolves to no destination.
     */
    private fun fromAppScheme(uri: Uri): Route? {
        val text = uri.toString()
        if (!text.startsWith(Route.SCHEME_PREFIX)) return null
        return Route(text.substringBefore('#')).takeIf { it.host.isNotEmpty() }
    }

    /**
     * `https://omnideck.app/go/<module>/item/42?x=1` becomes `omnideck://<module>/item/42?x=1`.
     *
     * Below [WEB_PATH_PREFIX] the path maps one-to-one on purpose: a link that works in
     * a browser and a link that works in the app are then the same link, which is what
     * makes shared URLs and email deep links work without a per-module redirect table.
     * Above it there is no mapping at all, by design — see [WEB_PATH_PREFIX].
     *
     * The query rides along; a fragment does not — see [fromAppScheme] for what a
     * surviving `#` does to [Route]. A destination that needs to address part of a
     * screen takes a query parameter.
     */
    private fun fromWeb(uri: Uri): Route? {
        if (!uri.host.equals(WEB_HOST, ignoreCase = true)) return null
        val path = uri.path.orEmpty()
        if (!path.startsWith(WEB_PATH_PREFIX)) return null
        val target = path.removePrefix(WEB_PATH_PREFIX).trim('/')
        if (target.isEmpty()) return null
        val query = uri.query?.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()
        return Route("${Route.SCHEME_PREFIX}$target$query")
    }

    private const val HTTPS = "https"
}
