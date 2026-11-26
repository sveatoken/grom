// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import java.io.File

/**
 * Persistent list of operator-configured SSH targets (D58).
 *
 * Same storage discipline as every other Grom store (D40): one small JSON file,
 * owner-only permissions, write-to-temp-then-rename and a hard size cap.
 *
 * Secrets are written only as [SecretBox] ciphertext. [upsert] is the single
 * write path and it re-validates the draft, so a malformed host, an over-long
 * label or a secret that is not a key cannot reach the disk.
 */
class SshTargetStore(
    private val file: File,
    private val secretBox: SecretBox,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * The only bound on how many servers may be saved (D64). Injectable so the
     * refusal path can be tested without writing half a megabyte.
     */
    private val maxFileBytes: Int = MAX_FILE_BYTES
) {

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun list(): List<SshTarget> = load()

    @Synchronized
    fun find(id: String): SshTarget? = load().firstOrNull { it.id == id }

    /**
     * The operator's current server (D107). It is one id for the whole file,
     * not a flag on each record: two records both claiming to be "active" is a
     * state the store must not be able to reach.
     *
     * A dangling id (the record was deleted elsewhere, or the file was edited
     * by hand) is reported as no active server rather than repaired silently.
     */
    @Synchronized
    fun activeId(): String? {
        val (records, id) = loadDocument()
        val chosen = id ?: return null
        return chosen.takeIf { candidate -> records.any { it.id == candidate } }
    }

    /**
     * Marks one saved server as the current one. An empty [id] clears the
     * selection; an unknown id is refused instead of written.
     */
    @Synchronized
    fun setActive(id: String?) {
        val records = load()
        val chosen = id?.trim()?.takeIf { it.isNotEmpty() }
        if (chosen != null && records.none { it.id == chosen }) {
            throw ApiException(404, "TARGET_NOT_FOUND", "no SSH target with that id")
        }
        save(records, chosen)
    }

    /** The secret to authenticate with, decrypted for the duration of a connect. */
    @Synchronized
    fun openSecret(target: SshTarget): String? = when (target.auth) {
        SshAuth.KEY -> target.privateKeySealed?.let { secretBox.open(it) }
        SshAuth.PASSWORD -> target.passwordSealed?.let { secretBox.open(it) }
    }

    @Synchronized
    fun upsert(draft: SshTargetDraft): SshTarget {
        SshTargetRules.validate(draft)?.let { throw ApiException(400, it.code, it.message) }

        val records = load().toMutableList()
        val existing = draft.id?.let { id -> records.firstOrNull { it.id == id } }

        val id = existing?.id ?: SshTarget.newId()
        var passwordSealed = existing?.passwordSealed
        var privateKeySealed = existing?.privateKeySealed
        var publicKeyLine = existing?.publicKeyLine

        if (draft.clearSecret) {
            passwordSealed = null
            privateKeySealed = null
            publicKeyLine = null
        }

        when (draft.auth) {
            SshAuth.PASSWORD -> {
                val typed = draft.password
                if (!typed.isNullOrEmpty()) {
                    passwordSealed = secretBox.seal(typed)
                    // Keeping a stale private key would let a later auth switch
                    // resurrect a credential the operator thought they removed.
                    privateKeySealed = null
                    publicKeyLine = null
                }
            }

            SshAuth.KEY -> {
                val typed = draft.privateKey
                if (!typed.isNullOrBlank()) {
                    val pair = SshKeys.keyPairFromPrivateText(typed)
                        ?: throw ApiException(
                            400,
                            "BAD_KEY",
                            "that private key could not be parsed (OpenSSH or PEM text is required)"
                        )
                    privateKeySealed = secretBox.seal(typed)
                    publicKeyLine = draft.publicKeyLine
                        ?: SshKeys.publicKeyLine(pair.public)
                    passwordSealed = null
                }
            }
        }

        // An explicit clear is allowed to leave the target unconfigured: the
        // operator asked for the credential to be gone, and `isConfigured`
        // reports the truth. Any other save must carry a usable secret.
        if (!draft.clearSecret) {
            if (draft.auth == SshAuth.KEY && privateKeySealed.isNullOrBlank()) {
                throw ApiException(
                    400,
                    "KEY_REQUIRED",
                    "key authentication needs a generated pair or a pasted private key"
                )
            }
            if (draft.auth == SshAuth.PASSWORD && passwordSealed.isNullOrBlank()) {
                throw ApiException(
                    400,
                    "PASSWORD_REQUIRED",
                    "password authentication needs a stored password"
                )
            }
        }

        val saved = SshTarget(
            id = id,
            label = draft.label.trim(),
            host = draft.host.trim(),
            port = draft.port,
            username = draft.username.trim(),
            auth = draft.auth,
            passwordSealed = passwordSealed,
            privateKeySealed = privateKeySealed,
            publicKeyLine = publicKeyLine,
            // A changed host must not inherit the old host's pin: that would
            // silently trust a different machine.
            hostKeySha256 = existing?.hostKeySha256?.takeIf {
                existing.host.equals(draft.host.trim(), ignoreCase = true) &&
                    existing.port == draft.port
            },
            remoteForwardPort = draft.remoteForwardPort,
            enabled = draft.enabled
        )

        // One remote forward port per host (D78): two tunnels asking for the
        // same port on the same VPS collide, and the second failure is a
        // confusing network error unless it is refused here.
        SshTargetRules.forwardPortConflict(
            records = records,
            host = saved.host,
            remoteForwardPort = saved.remoteForwardPort,
            id = saved.id
        )?.let { throw ApiException(409, it.code, it.message) }

        records.removeAll { it.id == id }
        records.add(saved)
        save(records)
        return saved
    }

    @Synchronized
    fun delete(id: String) {
        val records = load().toMutableList()
        val removed = records.removeAll { it.id == id }
        if (!removed) throw ApiException(404, "TARGET_NOT_FOUND", "no SSH target with that id")
        save(records)
    }

    /**
     * Records a TOFU pin. Only the fingerprint is stored, never the key itself.
     * A pin is never replaced while it exists (see [clearPin]).
     */
    @Synchronized
    fun pinHostKey(id: String, fingerprint: String) {
        if (!fingerprint.startsWith("SHA256:") || fingerprint.length > 128) {
            throw ApiException(400, "BAD_FINGERPRINT", "fingerprint is not a SHA256 pin")
        }
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(404, "TARGET_NOT_FOUND", "no SSH target with that id")
        records[index] = records[index].copy(hostKeySha256 = fingerprint)
        save(records)
    }

    /** Explicit operator action, used after the host legitimately changed. */
    @Synchronized
    fun clearPin(id: String) {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(404, "TARGET_NOT_FOUND", "no SSH target with that id")
        records[index] = records[index].copy(hostKeySha256 = null)
        save(records)
    }

    // ------------------------------------------------------------------ storage

    private fun load(): List<SshTarget> = loadDocument().first

    /** The parsed file: the targets in file order and the active id, if any. */
    private fun loadDocument(): Pair<List<SshTarget>, String?> {
        lastError = null
        if (!file.isFile) return emptyList<SshTarget>() to null
        return try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "ssh targets file is larger than $MAX_FILE_BYTES bytes"
                return emptyList<SshTarget>() to null
            }
            val root = JsonParser.parseObjectOrNull(text)
            if (root == null) {
                lastError = "ssh targets file is not a JSON object"
                return emptyList<SshTarget>() to null
            }
            val raw = root["targets"] as? List<*> ?: return emptyList<SshTarget>() to null
            val out = ArrayList<SshTarget>(raw.size)
            for (item in raw) {
                val map = item as? Map<*, *> ?: continue
                val target = fromJson(map) ?: continue
                val problem = SshTargetRules.validateStored(target)
                if (problem != null) {
                    lastError = "target ${target.id} rejected: ${problem.code}"
                    continue
                }
                out.add(target)
            }
            out to (root["activeId"] as? String)
        } catch (failure: Exception) {
            lastError = "ssh targets unreadable: ${failure.javaClass.simpleName}"
            emptyList<SshTarget>() to null
        }
    }

    private fun save(records: List<SshTarget>, activeId: String? = this.activeId()) {
        file.parentFile?.mkdirs()
        // A record can be deleted while a stale active id is still on disk; the
        // saved document never names a server that is not in the list.
        val chosen = activeId?.takeIf { id -> records.any { it.id == id } }
        val text = Json.encode(
            mapOf(
                "version" to 2,
                "activeId" to chosen,
                "targets" to records.map(::toJson)
            )
        )
        // There is no count cap (D64), so the file size is the real bound. It is
        // enforced *before* the write: refusing here keeps `load` able to read
        // every record, whereas an over-size write would make the whole list
        // unreadable on the next start.
        if (text.length > maxFileBytes) {
            throw ApiException(
                409,
                "SSH_STORE_FULL",
                "the saved-server file is full (limit $maxFileBytes bytes); remove a server first"
            )
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        harden(temp)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        harden(file)
    }

    private fun toJson(target: SshTarget): Map<String, Any?> = linkedMapOf(
        "id" to target.id,
        "label" to target.label,
        "host" to target.host,
        "port" to target.port,
        "username" to target.username,
        "auth" to target.auth.wireName,
        "passwordSealed" to target.passwordSealed,
        "privateKeySealed" to target.privateKeySealed,
        "publicKeyLine" to target.publicKeyLine,
        "hostKeySha256" to target.hostKeySha256,
        "remoteForwardPort" to target.remoteForwardPort,
        "enabled" to target.enabled,
        // Informational for the UI; liveness is always measured at connect time.
        "updatedAtMs" to clock()
    )

    private fun fromJson(raw: Map<*, *>): SshTarget? {
        val id = raw["id"] as? String ?: return null
        val label = raw["label"] as? String ?: return null
        val host = raw["host"] as? String ?: return null
        val username = raw["username"] as? String ?: return null
        val auth = SshAuth.fromWire(raw["auth"] as? String) ?: return null
        return SshTarget(
            id = id,
            label = label,
            host = host,
            port = (raw["port"] as? Number)?.toInt() ?: 22,
            username = username,
            auth = auth,
            passwordSealed = (raw["passwordSealed"] as? String)?.takeIf { it.isNotBlank() },
            privateKeySealed = (raw["privateKeySealed"] as? String)?.takeIf { it.isNotBlank() },
            publicKeyLine = (raw["publicKeyLine"] as? String)?.takeIf { it.isNotBlank() },
            hostKeySha256 = (raw["hostKeySha256"] as? String)?.takeIf { it.isNotBlank() },
            remoteForwardPort = (raw["remoteForwardPort"] as? Number)?.toInt()
                ?: SshTarget.DEFAULT_FORWARD_PORT,
            enabled = raw["enabled"] as? Boolean ?: true
        )
    }

    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    companion object {
        const val FILE_NAME: String = "ssh-targets.json"

        /**
         * 512 KiB of JSON, roughly a few hundred targets with a sealed Ed25519
         * key each. Large enough that the operator never meets it in practice,
         * small enough that one broken file cannot exhaust storage.
         */
        const val MAX_FILE_BYTES: Int = 512 * 1024
    }
}
