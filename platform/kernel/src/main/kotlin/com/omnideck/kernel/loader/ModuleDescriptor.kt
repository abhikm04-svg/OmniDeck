package com.omnideck.kernel.loader

import android.content.Context
import com.omnideck.sdk.DeliveryKind
import com.omnideck.sdk.ModuleId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * What the Shell knows about a module *before* any of its code has run.
 *
 * Produced by the `omnideck.module` convention plugin at build time into
 * `assets/omnideck/modules/<id>.properties`, so discovery needs no code generation,
 * no reflection over the classpath, and — crucially — no edit to any Shell file when
 * a module is added (goal G1).
 */
data class ModuleDescriptor(val id: ModuleId, val entryPointClass: String, val delivery: DeliveryKind)

/** Where descriptors come from. Swapped for a fixture in tests. */
fun interface ModuleDescriptorSource {
    suspend fun descriptors(): List<ModuleDescriptor>
}

/**
 * Reads every descriptor merged into the APK's assets.
 *
 * Asset merging across library modules is safe here because the plugin writes one
 * file per module id — collisions are impossible by construction.
 */
class AssetModuleDescriptorSource(private val context: Context, private val io: CoroutineDispatcher) :
    ModuleDescriptorSource {

    override suspend fun descriptors(): List<ModuleDescriptor> = withContext(io) {
        val assets = context.assets
        val files = runCatching { assets.list(DESCRIPTOR_DIR) }.getOrNull().orEmpty()

        files.mapNotNull { fileName ->
            runCatching {
                val props = Properties().apply {
                    assets.open("$DESCRIPTOR_DIR/$fileName").use(::load)
                }
                val id = requireNotNull(props.getProperty("id")) { "$fileName has no id" }
                ModuleDescriptor(
                    id = ModuleId(id),
                    entryPointClass = props.getProperty("entryPoint") ?: "$id.ModuleEntryPoint",
                    // Bundled until Phase 3 (OD-301) flips modules onto splits; the
                    // provider resolves the real delivery kind from the manifest once
                    // the module's code is available.
                    delivery = DeliveryKind.BUNDLED,
                )
            }.getOrNull()
        }.sortedBy { it.id.value }
    }

    private companion object {
        const val DESCRIPTOR_DIR = "omnideck/modules"
    }
}
