// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

/**
 * One platform discovery mechanism (D99).
 *
 * The engine never touches `android.bluetooth.*` or `android.net.wifi.p2p.*`:
 * each radio is a thin adapter that publishes [MeshCandidate]s and nothing else.
 * That keeps the discovery *policy* (dedupe, expiry, bounds, trust) in pure
 * Kotlin with JVM tests, exactly like the Doctor's probe (D30, D80).
 *
 * A radio that cannot run reports why — a missing permission, Bluetooth off,
 * no Wi-Fi Direct hardware — instead of silently returning an empty list. The UI
 * must be able to say "scanning is not possible on this phone" rather than
 * leave the operator waiting for a device that can never appear.
 */
interface MeshRadio {

    val transport: MeshTransport

    fun isAvailable(): Boolean

    /**
     * Whether the platform switch this radio depends on is on, measured now.
     *
     * `null` means the radio has no such switch (the LAN carrier) or it cannot
     * be read. Bluetooth reports `adapter.isEnabled`; Wi-Fi Direct reports
     * whether Wi-Fi is on. A `false` here is a **fact to show**, not something
     * Grom may change: Android 13+ forbids an app from calling
     * `BluetoothAdapter.enable()`/`disable()`, so the screen offers the system
     * settings instead of flipping the radio behind the operator's back.
     */
    fun platformEnabled(): Boolean? = null

    /** Platform permission names this radio still needs; empty when ready. */
    fun missingPermissions(): List<String>

    /**
     * Starts discovery. [sink] may be called from any thread and must be
     * thread-safe (the engine's book is). Returns false when the radio is not
     * usable; [lastError] explains why.
     */
    fun start(sink: (MeshCandidate) -> Unit): Boolean

    fun stop()

    /** Asks for one bounded scan; no argument means "whatever the adapter does". */
    fun scanNow()

    /** Why the last start/scan did nothing, or null. Never contains an address. */
    val lastError: String?
}
