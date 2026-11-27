// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import java.io.File

/**
 * The operator's server-network switches (D99).
 *
 * Discovery is off by default and every part of it is separate: a device may
 * advertise without scanning, or scan without answering. Nothing here is a
 * timing knob — the beacon interval is a constant — and nothing runs while the
 * server is stopped.
 */
data class MeshSettings(
    val enabled: Boolean = false,
    /** Send a signed LAN beacon while the server runs. */
    val advertise: Boolean = true,
    /** Listen for beacons and run the platform radios. */
    val scan: Boolean = true,
    /**
     * Whether the Bluetooth radios (LE and Classic) take part (D109).
     *
     * This is **not** the system Bluetooth switch: Android 13+ forbids an app
     * from calling `BluetoothAdapter.enable()`/`disable()`, and Grom never asks
     * for it. It only decides whether *Grom* uses Bluetooth at all, so an
     * operator who wants discovery over the network alone can leave the radio
     * out without turning discovery off. The default is `true` because turning
     * discovery on has always started these radios — an upgrade must not remove
     * a transport silently.
     */
    val bluetooth: Boolean = true
) {

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "advertise" to advertise,
        "scan" to scan,
        "bluetooth" to bluetooth
    )

    /**
     * Applies a partial document. Absent fields keep their value; anything that
     * is not a boolean is refused rather than coerced, so a typo cannot quietly
     * switch discovery on.
     */
    fun apply(raw: Map<*, *>): MeshSettings? {
        fun bool(name: String, fallback: Boolean): Boolean? =
            if (!raw.containsKey(name)) fallback else raw[name] as? Boolean

        return MeshSettings(
            enabled = bool("enabled", enabled) ?: return null,
            advertise = bool("advertise", advertise) ?: return null,
            scan = bool("scan", scan) ?: return null,
            bluetooth = bool("bluetooth", bluetooth) ?: return null
        )
    }

    companion object {
        const val FILE_NAME: String = "mesh.json"

        fun fromJson(raw: Map<*, *>): MeshSettings? =
            MeshSettings().apply(raw)
    }
}

/**
 * Persists [MeshSettings] with the project's storage discipline (D40): one
 * small JSON file, write-to-temp-then-rename, size cap before the write. A file
 * that cannot be parsed is reported and treated as "disabled" — discovery is
 * always opt-in, so a corrupt file must fail closed.
 */
class MeshSettingsStore(
    private val file: File,
    private val maxFileBytes: Int = MAX_FILE_BYTES
) {

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun load(): MeshSettings {
        lastError = null
        if (!file.isFile) return MeshSettings()
        return try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "mesh settings file is too large"
                return MeshSettings()
            }
            val root = JsonParser.parseObjectOrNull(text) ?: run {
                lastError = "mesh settings are not a JSON object"
                return MeshSettings()
            }
            MeshSettings.fromJson(root) ?: run {
                lastError = "mesh settings were rejected"
                MeshSettings()
            }
        } catch (failure: Exception) {
            lastError = "mesh settings unreadable: ${failure.javaClass.simpleName}"
            MeshSettings()
        }
    }

    @Synchronized
    fun save(settings: MeshSettings) {
        file.parentFile?.mkdirs()
        val text = Json.encode(settings.toJson())
        if (text.length > maxFileBytes) {
            throw IllegalStateException("mesh settings exceed $maxFileBytes bytes")
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        const val MAX_FILE_BYTES: Int = 4 * 1024
    }
}
