package com.omnideck.sdk

import kotlinx.serialization.Serializable

/**
 * Every navigable surface in OmniDeck is a URI: `omnideck://<shortId>/<path>?<query>`.
 *
 * That grammar is the whole grammar — there is no fragment, and the `init` block
 * rejects one rather than let it through to be misread.
 *
 * This is the mechanism behind the "one-click shop" (architecture.md §10.1): a route
 * can be emitted by a module, a notification, an App Link or an app shortcut, and the
 * Router will install and initialise the owning module on the way to the destination
 * if it is not present yet.
 */
@Serializable
data class Route(val uri: String) {
    init {
        require(uri.startsWith(SCHEME_PREFIX)) {
            "Route must start with '$SCHEME_PREFIX', was '$uri'"
        }
        // `?` is this class's only delimiter, so a `#` is not ignored, it is absorbed:
        // it lands inside [path], inside a [query] value, or inside a placeholder that
        // RoutePattern binds — `omnideck://n/note/42#c` yields a `noteId` of `42#c`,
        // and `?omnideck_result_to=abc#c` a correlation id matching nothing the Router
        // issued. Teaching six hand-rolled parse sites a second delimiter is the more
        // expensive invariant; refusing the character is the cheap one.
        //
        // External URIs are stripped at the Shell's boundary (`ExternalRoutes`), which
        // is where attacker-supplied input arrives. This catches the in-process callers
        // that boundary never sees. Percent-encoded `%23` is a literal, and stays legal.
        require('#' !in uri) {
            "Route must not carry a fragment, was '$uri'"
        }
    }

    /** `omnideck://finance/account/42` -> `finance`. */
    val host: String
        get() = uri.removePrefix(SCHEME_PREFIX).substringBefore('/').substringBefore('?')

    /** `omnideck://finance/account/42?x=1` -> `account/42`. */
    val path: String
        get() = uri.removePrefix(SCHEME_PREFIX)
            .substringAfter('/', missingDelimiterValue = "")
            .substringBefore('?')

    val query: Map<String, String>
        get() = uri.substringAfter('?', missingDelimiterValue = "")
            .takeIf(String::isNotEmpty)
            ?.split('&')
            ?.mapNotNull { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', missingDelimiterValue = "")
                if (k.isBlank()) null else k to v
            }
            ?.toMap()
            .orEmpty()

    /**
     * The result correlation id carried by this route, if any.
     *
     * A destination reached through `navigateForResult` reads this and passes it back
     * to `Router.setResult`. It rides in the query string rather than in memory
     * precisely so it survives process death: the route is held in the navigation
     * back stack, which Android saves and restores (§10.2).
     */
    val correlationId: CorrelationId?
        get() = query[CORRELATION_KEY]?.takeIf(String::isNotBlank)?.let(::CorrelationId)

    /** Returns this route with [id] attached, replacing any correlation already present. */
    fun withCorrelationId(id: CorrelationId): Route {
        val base = uri.substringBefore('?')
        val existing = query.filterKeys { it != CORRELATION_KEY }
        val params = existing + (CORRELATION_KEY to id.value)
        return Route(base + "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" })
    }

    override fun toString(): String = uri

    companion object {
        const val SCHEME = "omnideck"
        const val SCHEME_PREFIX = "$SCHEME://"

        /** Query key carrying a `navigateForResult` correlation id. */
        const val CORRELATION_KEY = "omnideck_result_to"

        fun of(moduleId: ModuleId, path: String = ""): Route =
            Route("$SCHEME_PREFIX${moduleId.shortId}${if (path.isEmpty()) "" else "/${path.trimStart('/')}"}")
    }
}

/**
 * A route pattern with `{placeholder}` segments, e.g.
 * `omnideck://finance/account/{accountId}`.
 *
 * Everything the `init` block rejects shares one failure mode: the pattern is accepted,
 * registered, and then matches nothing, ever. A dead deep link raises no error anywhere
 * — it is simply a destination no one can reach, discovered by a user report.
 */
@Serializable
data class RoutePattern(val pattern: String) {
    init {
        // [segments] strips the scheme prefix and splits on '/'. Without the prefix the
        // scheme rides into the first segment, and every [Route] carries one — its own
        // init requires it — so the two can never line up.
        require(pattern.startsWith(Route.SCHEME_PREFIX)) {
            "RoutePattern must start with '${Route.SCHEME_PREFIX}', was '$pattern'"
        }
        // A Route cannot hold a fragment at all, so a pattern carrying one matches
        // nothing by construction.
        require('#' !in pattern) {
            "RoutePattern must not carry a fragment, was '$pattern'"
        }
        // [extract] compares against `route.uri.substringBefore('?')`, because a pattern
        // addresses a path and the query reaches the destination as [RouteArgs] instead.
        // A '?' left here is compared against a string that never contains one.
        require('?' !in pattern) {
            "RoutePattern must not carry a query, was '$pattern'"
        }
    }

    private val segments: List<String> get() = pattern.removePrefix(Route.SCHEME_PREFIX).split('/')

    fun matches(route: Route): Boolean = extract(route) != null

    /** Returns the placeholder bindings if [route] matches, else null. */
    fun extract(route: Route): Map<String, String>? {
        val actual = route.uri.substringBefore('?').removePrefix(Route.SCHEME_PREFIX).split('/')
        val expected = segments
        if (actual.size != expected.size) return null

        val args = mutableMapOf<String, String>()
        expected.forEachIndexed { index, segment ->
            val value = actual[index]
            when {
                segment.startsWith('{') && segment.endsWith('}') ->
                    args[segment.substring(1, segment.length - 1)] = value
                segment != value -> return null
            }
        }
        return args
    }

    /** Specificity: literal segments beat placeholders when two patterns both match. */
    val specificity: Int get() = segments.count { !it.startsWith('{') }

    override fun toString(): String = pattern
}

/** Arguments handed to a destination, from path placeholders merged with the query. */
class RouteArgs(private val values: Map<String, String>) {
    fun stringOrNull(key: String): String? = values[key]
    fun string(key: String): String = requireNotNull(values[key]) { "Missing route arg '$key'" }
    fun int(key: String): Int = string(key).toInt()
    fun intOrNull(key: String): Int? = values[key]?.toIntOrNull()
    fun boolean(key: String): Boolean = string(key).toBooleanStrict()
    fun asMap(): Map<String, String> = values.toMap()

    companion object {
        val EMPTY = RouteArgs(emptyMap())
    }
}
