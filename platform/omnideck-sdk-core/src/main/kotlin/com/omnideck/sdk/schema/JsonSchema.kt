package com.omnideck.sdk.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Derives a JSON Schema from a `kotlinx.serialization` descriptor (OD-104).
 *
 * The point is that client and server share **one** definition of a manifest. The
 * Kotlin declaration is the source of truth; the backend Registry validates uploads
 * against a schema generated from it, so a field added on the device cannot silently
 * disagree with what the server accepts.
 *
 * Written by hand rather than pulled in as a dependency because
 * `:platform:omnideck-sdk-core` must stay pure Kotlin — the layering fitness function
 * rejects anything else, and that purity is what lets the backend and a future KMP
 * target share these types.
 *
 * Deliberately a *structural* schema: it captures shape, types, required fields and
 * enum values. Semantic rules that live in `init` blocks — that an entry route's host
 * matches the module id, that at least one capability is declared — cannot be
 * expressed here and are enforced on both sides by constructing the type.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchema {

    private const val SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"

    /**
     * Renders [descriptor] as a JSON Schema document.
     *
     * Nested structures are emitted once into `$defs` and referenced, which keeps the
     * output small and makes recursive types expressible at all.
     */
    fun of(descriptor: SerialDescriptor, id: String? = null): JsonObject {
        val definitions = LinkedHashMap<String, JsonObject>()
        val root = schemaFor(descriptor, definitions, isRoot = true)

        return buildJsonObject {
            put("\$schema", SCHEMA_DIALECT)
            id?.let { put("\$id", it) }
            root.forEach { (key, value) -> put(key, value) }
            if (definitions.isNotEmpty()) {
                put("\$defs", JsonObject(definitions))
            }
        }
    }

    /** Pretty-prints [of] as text, suitable for checking in or publishing. */
    fun render(descriptor: SerialDescriptor, id: String? = null): String =
        SchemaJson.encodeToString(JsonObject.serializer(), of(descriptor, id))

    private val SchemaJson = kotlinx.serialization.json.Json { prettyPrint = true }

    private fun schemaFor(
        descriptor: SerialDescriptor,
        definitions: MutableMap<String, JsonObject>,
        isRoot: Boolean = false,
    ): JsonObject {
        val base = when (descriptor.kind) {
            PrimitiveKind.STRING -> primitive("string")
            PrimitiveKind.BOOLEAN -> primitive("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                primitive("integer")

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> primitive("number")
            PrimitiveKind.CHAR -> buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", 1)
            }

            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put(
                    "enum",
                    buildJsonArray {
                        repeat(descriptor.elementsCount) { add(JsonPrimitive(descriptor.getElementName(it))) }
                    },
                )
            }

            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schemaFor(descriptor.getElementDescriptor(0), definitions))
                // A Set serialises as an array but may not repeat.
                if (descriptor.serialName.contains("Set")) put("uniqueItems", true)
            }

            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", schemaFor(descriptor.getElementDescriptor(1), definitions))
            }

            StructureKind.CLASS, StructureKind.OBJECT -> return objectSchema(descriptor, definitions, isRoot)

            // A sealed hierarchy is encoded with a discriminator; the concrete shape
            // depends on the "type" field, which oneOf cannot express without
            // enumerating subclasses the descriptor does not expose here.
            is PolymorphicKind -> buildJsonObject { put("type", "object") }

            // Resolved at runtime against a serializers module, so nothing is known
            // about its shape statically. An empty schema accepts anything, which is
            // honest: the alternative would be to invent constraints.
            SerialKind.CONTEXTUAL -> buildJsonObject { }
        }

        return if (descriptor.isNullable) nullable(base) else base
    }

    /**
     * Emits a class into `$defs` and returns a `$ref` to it, so a type used in several
     * places appears once. The root is inlined instead, so the document reads as the
     * thing it describes.
     */
    private fun objectSchema(
        descriptor: SerialDescriptor,
        definitions: MutableMap<String, JsonObject>,
        isRoot: Boolean,
    ): JsonObject {
        val name = descriptor.serialName.substringAfterLast('.').removeSuffix("?")

        if (!isRoot) {
            // Reserve the slot before recursing so a self-referencing type terminates.
            if (name !in definitions) {
                definitions[name] = JsonObject(emptyMap())
                definitions[name] = buildObjectBody(descriptor, definitions)
            }
            val ref = buildJsonObject { put("\$ref", "#/\$defs/$name") }
            return if (descriptor.isNullable) nullable(ref) else ref
        }

        return buildObjectBody(descriptor, definitions)
    }

    private fun buildObjectBody(
        descriptor: SerialDescriptor,
        definitions: MutableMap<String, JsonObject>,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("title", descriptor.serialName.substringAfterLast('.'))

        val properties = LinkedHashMap<String, JsonObject>()
        val required = mutableListOf<String>()

        repeat(descriptor.elementsCount) { index ->
            val elementName = descriptor.getElementName(index)
            properties[elementName] = schemaFor(descriptor.getElementDescriptor(index), definitions)
            // Optional means "has a default in Kotlin", which is exactly the set a
            // producer may omit — so it maps onto JSON Schema's `required` directly.
            if (!descriptor.isElementOptional(index)) required += elementName
        }

        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
        // Unknown fields are rejected: a manifest carrying something this schema does
        // not know about means client and server have drifted, which is the exact
        // failure this schema exists to catch.
        put("additionalProperties", false)
    }

    private fun primitive(type: String) = buildJsonObject { put("type", type) }

    /** JSON Schema has no nullable flag; a nullable value is a union with null. */
    private fun nullable(schema: JsonObject): JsonObject = buildJsonObject {
        put("oneOf", JsonArray(listOf(schema, buildJsonObject { put("type", "null") })))
    }
}
