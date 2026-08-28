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
                moduleDescriptorFrom(props)
            }.getOrNull()
        }.sortedBy { it.id.value }
    }

    private companion object {
        const val DESCRIPTOR_DIR = "omnideck/modules"
    }
}

/**
 * Parses one descriptor.
 *
 * Separated from the asset reading so the part with decisions in it is reachable from
 * a plain unit test: getting the delivery kind wrong sends a module to the wrong
 * provider, which fails on a device as a missing class rather than as a wrong setting.
 */
internal fun moduleDescriptorFrom(props: Properties): ModuleDescriptor {
    val id = requireNotNull(props.getProperty("id")) { "descriptor has no id" }
    return ModuleDescriptor(
        id = ModuleId(id),
        entryPointClass = props.getProperty("entryPoint") ?: "$id.ModuleEntryPoint",
        delivery = props.getProperty("delivery").toDeliveryKind(),
    )
}

/**
 * How this module's code reaches the device, as written by the `omnideck.module`
 * convention plugin (OD-301).
 *
 * This is the one thing about a module the Shell must know *before* running any of
 * its code, because it decides which `ModuleProvider` handles it — and the bundled
 * provider reports every module installed, so choosing it for a split would report a
 * module ready and then fail to find a class the device has not downloaded.
 *
 * Absent means bundled: a descriptor generated before Phase 3 has no `delivery` line,
 * and a module in the base APK is exactly what it described. An unrecognised value
 * means a descriptor written by a newer build than this Shell — bundled is the only
 * safe reading, because it is the delivery kind that needs no capability this Shell
 * might lack.
 */
private fun String?.toDeliveryKind(): DeliveryKind =
    DeliveryKind.entries.firstOrNull { it.name == this } ?: DeliveryKind.BUNDLED
