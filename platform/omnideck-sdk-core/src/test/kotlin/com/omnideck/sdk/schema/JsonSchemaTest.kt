package com.omnideck.sdk.schema

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * OD-104. The schema exists so client and server cannot disagree about what a
 * manifest is, which means the generator has to be faithful about the things the
 * Registry will reject an upload over: which fields are mandatory, what an enum may
 * contain, and whether unknown fields are tolerated.
 */
class JsonSchemaTest {

    @Serializable
    private data class Simple(val required: String, val optional: Int = 0, val nullable: String?)

    @Serializable
    private enum class Colour { RED, GREEN }

    @Serializable
    private data class WithCollections(
        val names: List<String>,
        val tags: Set<String>,
        val attributes: Map<String, Int>,
        val nested: Simple,
    )

    private fun schemaOf(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = JsonSchema.of(descriptor)

    // -- required vs optional ----------------------------------------------

    @Test
    fun `only fields without defaults are required`() {
        // Kotlin's "has a default" is exactly the set a producer may omit, so it maps
        // straight onto JSON Schema's `required`.
        val schema = schemaOf(Simple.serializer().descriptor)

        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(required).containsExactly("required", "nullable")
        assertThat(required).doesNotContain("optional")
    }

    @Test
    fun `a nullable field is a union with null`() {
        // JSON Schema has no nullable flag; conflating the two would let the server
        // reject a legitimately absent value.
        val schema = schemaOf(Simple.serializer().descriptor)
        val nullable = schema["properties"]!!.jsonObject["nullable"]!!.jsonObject

        val types = nullable["oneOf"]!!.jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertThat(types).containsExactly("string", "null")
    }

    @Test
    fun `unknown fields are rejected`() {
        // A manifest carrying something the schema does not know about means client
        // and server have drifted, which is the failure this schema exists to catch.
        val schema = schemaOf(Simple.serializer().descriptor)

        assertThat(schema["additionalProperties"]).isEqualTo(JsonPrimitive(false))
    }

    // -- type mapping -------------------------------------------------------

    @Test
    fun `primitives map to their JSON types`() {
        val properties = schemaOf(Simple.serializer().descriptor)["properties"]!!.jsonObject

        assertThat(properties["required"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("string")
        assertThat(properties["optional"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("integer")
    }

    @Test
    fun `an enum is a string constrained to its constants`() {
        val schema = schemaOf(Colour.serializer().descriptor)

        assertThat(schema["type"]!!.jsonPrimitive.content).isEqualTo("string")
        assertThat(schema["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("RED", "GREEN")
    }

    @Test
    fun `a list becomes an array and a set additionally forbids duplicates`() {
        val properties = schemaOf(WithCollections.serializer().descriptor)["properties"]!!.jsonObject

        val names = properties["names"]!!.jsonObject
        assertThat(names["type"]!!.jsonPrimitive.content).isEqualTo("array")
        assertThat(names["uniqueItems"]).isNull()

        assertThat(properties["tags"]!!.jsonObject["uniqueItems"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `a map becomes an object with typed values`() {
        val attributes = schemaOf(WithCollections.serializer().descriptor)["properties"]!!
            .jsonObject["attributes"]!!.jsonObject

        assertThat(attributes["type"]!!.jsonPrimitive.content).isEqualTo("object")
        assertThat(attributes["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            .isEqualTo("integer")
    }

    @Test
    fun `a nested class is emitted once into defs and referenced`() {
        // Keeps the document small and is what makes a self-referencing type
        // expressible at all rather than recursing forever.
        val schema = schemaOf(WithCollections.serializer().descriptor)

        val ref = schema["properties"]!!.jsonObject["nested"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content
        assertThat(ref).isEqualTo("#/\$defs/Simple")
        assertThat(schema["\$defs"]!!.jsonObject.keys).contains("Simple")
    }

    @Test
    fun `the root object is inlined rather than referenced`() {
        val schema = schemaOf(WithCollections.serializer().descriptor)

        assertThat(schema["type"]!!.jsonPrimitive.content).isEqualTo("object")
        assertThat(schema["\$ref"]).isNull()
    }

    // -- the manifest schema itself ----------------------------------------

    @Test
    fun `the generated manifest schema is valid JSON and declares its dialect`() {
        val schema = Json.parseToJsonElement(ManifestSchema.render()).jsonObject

        assertThat(schema["\$schema"]!!.jsonPrimitive.content).contains("json-schema.org")
        assertThat(schema["\$id"]!!.jsonPrimitive.content).isEqualTo(ManifestSchema.ID)
    }

    @Test
    fun `every manifest field without a Kotlin default is required by the schema`() {
        // The check that keeps the two definitions honest: if someone adds a field
        // here without a default, the Registry starts requiring it too.
        val schema = Json.parseToJsonElement(ManifestSchema.render()).jsonObject
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        val descriptor = ModuleManifest.serializer().descriptor
        val expected = (0 until descriptor.elementsCount)
            .filterNot(descriptor::isElementOptional)
            .map(descriptor::getElementName)

        assertThat(required).containsExactlyElementsIn(expected)
    }

    @Test
    fun `optional manifest fields are present but not required`() {
        // Defaults exist so a simple module writes a short manifest; the schema must
        // not undo that by demanding them.
        val schema = Json.parseToJsonElement(ManifestSchema.render()).jsonObject
        val properties = schema["properties"]!!.jsonObject.keys
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertThat(properties).containsAtLeast("optionalCapabilities", "androidPermissions", "entitlement")
        assertThat(required).doesNotContain("optionalCapabilities")
        assertThat(required).doesNotContain("androidPermissions")
    }

    @Test
    fun `manifest enums carry their full constant set`() {
        val schema = Json.parseToJsonElement(ManifestSchema.render()).jsonObject
        val delivery = schema["properties"]!!.jsonObject["delivery"]!!.jsonObject

        assertThat((delivery["enum"] as JsonArray).map { it.jsonPrimitive.content })
            .containsExactly("BUNDLED", "FEATURE_SPLIT", "SATELLITE", "WEB")
    }

    @Test
    fun `shared value types appear once in defs`() {
        val defs: JsonObject = Json.parseToJsonElement(ManifestSchema.render())
            .jsonObject["\$defs"]!!.jsonObject

        assertThat(defs.keys).containsAtLeast("ModuleId", "CapabilityId", "LocalizedString")
    }

    @Test
    fun `the committed schema matches what the generator produces`() {
        // Guards the whole point of OD-104: a manifest change that is not re-exported
        // leaves the Registry validating against a stale definition.
        val committed = java.io.File("schema/module-manifest.schema.json")

        assertThat(committed.exists()).isTrue()
        assertThat(committed.readText().replace("\r\n", "\n").trim())
            .isEqualTo(ManifestSchema.render().replace("\r\n", "\n").trim())
    }
}
