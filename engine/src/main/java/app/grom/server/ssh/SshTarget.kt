// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import java.security.SecureRandom

/**
 * How a saved SSH target authenticates (D58).
 *
 * `KEY` is the preferred mode: the engine can generate an Ed25519 pair and seal
 * the private half, so the operator only copies a public line to the VPS.
 * `PASSWORD` exists because many small VPS setups still use it; it is stored
 * sealed exactly like the key and is never written to the JSON in clear text.
 */
enum class SshAuth(val wireName: String) {
    KEY("key"),
    PASSWORD("password");

    companion object {
        fun fromWire(value: String?): SshAuth? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/**
 * One operator-configured SSH target ("VPS booster").
 *
 * The secret fields hold [app.grom.server.store.SecretBox] ciphertext only. A
 * target with `auth = KEY` has [privateKeySealed]; one with `auth = PASSWORD`
 * has [passwordSealed]. Neither is ever serialised in clear text and neither is
 * part of any status document.
 *
 * [hostKeySha256] is the TOFU pin. Once set, a server presenting any other key
 * is refused with `HOST_KEY_MISMATCH`; there is no "accept anyway".
 */
data class SshTarget(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: SshAuth,
    val passwordSealed: String? = null,
    val privateKeySealed: String? = null,
    val publicKeyLine: String? = null,
    val hostKeySha256: String? = null,
    val remoteForwardPort: Int = DEFAULT_FORWARD_PORT,
    val enabled: Boolean = true
) {

    fun isConfigured(): Boolean = when (auth) {
        SshAuth.KEY -> !privateKeySealed.isNullOrBlank()
        SshAuth.PASSWORD -> !passwordSealed.isNullOrBlank()
    }

    /**
     * UI document. It includes the host and user because the operator is the
     * one looking at the phone; [serverSummary] is the version that may leave
     * the device.
     */
    fun toUiJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "label" to label,
        "host" to host,
        "port" to port,
        "username" to username,
        "auth" to auth.wireName,
        "configured" to isConfigured(),
        "hostKeySha256" to hostKeySha256,
        "forwardPort" to remoteForwardPort,
        "enabled" to enabled,
        "publicKeyLine" to publicKeyLine
    )

    companion object {
        const val DEFAULT_FORWARD_PORT: Int = 47002

        /**
         * How many tunnels may be *up at once*. This cap is about the phone, not
         * about storage: every live tunnel costs a thread, a socket, an SSH
         * session and a MINA transport. Saved targets are deliberately not
         * capped (D64) — the store's own size limit is the only bound, and
         * exceeding it is reported instead of silently dropping records.
         *
         * Four, not two (D107): an operator with several VPS hosts should not
         * have to forget one to use another, and four sessions is still a
         * bounded, stated cost rather than an open-ended one.
         */
        const val MAX_ACTIVE: Int = 4

        /**
         * Above this many saved servers the UI shows an advisory. It is not a
         * refusal: extra servers simply stay idle, and the reason is stated.
         */
        const val ADVISORY_TARGETS: Int = 4

        val ID_PATTERN: Regex = Regex("^[0-9a-f]{16}$")
        val LABEL_MAX: Int = 64
        val HOST_MAX: Int = 253
        val USERNAME_MAX: Int = 64

        /** Letters, digits, dots, dashes, underscores and IPv6 colons. */
        val HOST_PATTERN: Regex =
            Regex("^[A-Za-z0-9](?:[A-Za-z0-9._:\\-]{0,251}[A-Za-z0-9])?$")

        val USERNAME_PATTERN: Regex = Regex("^[A-Za-z0-9._\\-]{1,64}$")

        private val random = SecureRandom()

        fun newId(): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** A validated problem with a target draft. */
data class SshProblem(val code: String, val message: String)

/**
 * What the UI submits. Plaintext secrets appear here for the duration of the
 * call and are sealed by [SshTargetStore] before the file is written. `null`
 * means "keep the existing secret"; [clearSecret] removes it.
 */
data class SshTargetDraft(
    val id: String? = null,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuth,
    val password: String? = null,
    val privateKey: String? = null,
    val publicKeyLine: String? = null,
    val clearSecret: Boolean = false,
    val remoteForwardPort: Int = SshTarget.DEFAULT_FORWARD_PORT,
    val enabled: Boolean = true
)

/**
 * Pure validation of a draft or a stored target (D58).
 *
 * It runs before a socket exists, so an operator typo is a form error, not a
 * failed connection. A host is never resolved here and never accepted with a
 * scheme, userinfo or path: this is a hostname or an IP literal, nothing else.
 */
object SshTargetRules {

    fun validate(draft: SshTargetDraft): SshProblem? {
        val label = draft.label.trim()
        if (label.isEmpty() || label.length > SshTarget.LABEL_MAX) {
            return SshProblem("BAD_LABEL", "label must be 1..${SshTarget.LABEL_MAX} characters")
        }
        if (label.any { it.isISOControl() }) {
            return SshProblem("BAD_LABEL", "label must not contain control characters")
        }

        val host = draft.host.trim()
        if (host.isEmpty() || host.length > SshTarget.HOST_MAX) {
            return SshProblem("BAD_HOST", "host must be 1..${SshTarget.HOST_MAX} characters")
        }
        if (!SshTarget.HOST_PATTERN.matches(host)) {
            return SshProblem(
                "BAD_HOST",
                "host must be a hostname or IP address (no scheme, path or user@)"
            )
        }
        if (host.contains("..")) return SshProblem("BAD_HOST", "host contains an empty label")

        if (draft.port !in 1..65535) return SshProblem("BAD_PORT", "port must be 1..65535")
        if (draft.remoteForwardPort !in 1..65535) {
            return SshProblem("BAD_FORWARD_PORT", "forward port must be 1..65535")
        }

        val user = draft.username.trim()
        if (!SshTarget.USERNAME_PATTERN.matches(user)) {
            return SshProblem(
                "BAD_USERNAME",
                "username must be 1..${SshTarget.USERNAME_MAX} letters, digits, dot, dash or underscore"
            )
        }

        if (draft.clearSecret) {
            // Removing a secret is always expressible; whether the target is
            // still usable is reported by `isConfigured` and by connect.
            return null
        }
        when (draft.auth) {
            SshAuth.KEY -> {
                val supplied = draft.privateKey
                if (supplied != null) {
                    if (supplied.length > MAX_KEY_CHARS) {
                        return SshProblem("BAD_KEY", "private key text is too large")
                    }
                    if (!supplied.contains("PRIVATE KEY")) {
                        return SshProblem("BAD_KEY", "private key must be OpenSSH or PEM text")
                    }
                }
            }
            SshAuth.PASSWORD -> {
                val supplied = draft.password
                if (supplied != null && (supplied.isEmpty() || supplied.length > MAX_PASSWORD_CHARS)) {
                    return SshProblem(
                        "BAD_PASSWORD",
                        "password must be 1..$MAX_PASSWORD_CHARS characters"
                    )
                }
                // A pasted password usually arrives with a trailing newline.
                // Accepting it stores a secret that can never authenticate, and
                // the server answers with a plain "rejected" that looks exactly
                // like a wrong password (D105). The password itself is never
                // trimmed or echoed — it is refused with the reason.
                if (supplied != null && supplied.any { it.isISOControl() }) {
                    return SshProblem(
                        "BAD_PASSWORD",
                        "the password contains a control character " +
                            "(a pasted line break?); remove it and try again"
                    )
                }
            }
        }
        return null
    }

    /**
     * The remote port forward is one address per server. Two saved targets on
     * the same host with the same forward port would collide at connect time,
     * where the failure would look like a network problem instead of a typo.
     * This is checked across the saved list, which a single draft cannot see
     * (D78).
     */
    fun forwardPortConflict(
        records: List<SshTarget>,
        host: String,
        remoteForwardPort: Int,
        id: String?
    ): SshProblem? {
        val clash = records.firstOrNull {
            it.id != id &&
                it.host.equals(host.trim(), ignoreCase = true) &&
                it.remoteForwardPort == remoteForwardPort
        } ?: return null
        return SshProblem(
            "BAD_FORWARD_PORT_CONFLICT",
            "${clash.label} already uses forward port $remoteForwardPort on that host; " +
                "give this server its own port"
        )
    }

    /**
     * Serialised targets can be edited across releases, so stored values are
     * re-validated on load rather than trusted because they were once written.
     */
    fun validateStored(target: SshTarget): SshProblem? {
        if (!SshTarget.ID_PATTERN.matches(target.id)) {
            return SshProblem("BAD_ID", "target id is not valid")
        }
        return validate(
            SshTargetDraft(
                id = target.id,
                label = target.label,
                host = target.host,
                port = target.port,
                username = target.username,
                auth = target.auth,
                privateKey = target.privateKeySealed?.let { "PRIVATE KEY" },
                remoteForwardPort = target.remoteForwardPort,
                enabled = target.enabled
            )
        ).let { problem -> problem?.takeUnless { it.code == "BAD_PASSWORD" } }
    }

    private const val MAX_KEY_CHARS: Int = 16 * 1024
    private const val MAX_PASSWORD_CHARS: Int = 512
}
