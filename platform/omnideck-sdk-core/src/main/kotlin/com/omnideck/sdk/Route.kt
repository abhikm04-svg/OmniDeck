package com.omnideck.sdk

import kotlinx.serialization.Serializable

/**
 * Every navigable surface in OmniDeck is a URI: `omnideck://<shortId>/<path>?<query>`.
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

    override fun toString(): String = uri

    companion object {
        const val SCHEME = "omnideck"
        const val SCHEME_PREFIX = "$SCHEME://"

        fun of(moduleId: ModuleId, path: String = ""): Route =
            Route("$SCHEME_PREFIX${moduleId.shortId}${if (path.isEmpty()) "" else "/${path.trimStart('/')}"}")
    }
}

/**
 * A route pattern with `{placeholder}` segments, e.g.
 * `omnideck://finance/account/{accountId}`.
 */
@Serializable
data class RoutePattern(val pattern: String) {

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
