// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PeerScope
import app.grom.server.core.Token
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore

/**
 * Credential policy of the public data plane (D42, S5-R013…S5-R019).
 *
 * Two mechanisms exist and the configuration decides which are live:
 *   - password: verified against a PBKDF2 hash, then exchanged for a random,
 *     expiring session token. The password never travels on every request and
 *     is never stored;
 *   - Ed25519: the caller signs a canonical request string with a key the
 *     operator registered. The server holds only public keys.
 *
 * Failures are deliberately uniform: a wrong password, a missing header and an
 * unknown key id all produce the same `401 UNAUTHORIZED` body, so an
 * unauthenticated caller learns nothing about the configuration.
 */
class SuperAuth(
    private val config: SuperServerConfig,
    private val passwords: PasswordHash?,
    private val sessions: SessionStore,
    private val requestLimiter: RateLimiter,
    private val loginLimiter: LoginLimiter,
    private val signer: RequestSigner,
    /**
     * Maps a verified key id to what that key is allowed to do (D73). The
     * default keeps the previous behaviour exactly: every registered key is an
     * operator. A paired device is a [Principal.Peer] with explicit scopes.
     */
    private val principalOf: (String) -> Principal = { Principal.Operator }
) {

    /**
     * Who is calling. The session token and the operator's own keys are
     * [Principal.Operator]; a paired device gets only the scopes the operator
     * granted it during pairing.
     */
    sealed interface Principal {
        data object Operator : Principal

        /**
         * A paired server or connected app (D73, D101).
         *
         * [services] is the operator's per-peer allow-list by plugin id. Empty
         * keeps the pre-D101 meaning — every operation [scopes] already allow —
         * so an existing pairing is not silently narrowed. A non-empty set is a
         * hard filter applied by the router before the plugin is even asked.
         */
        data class Peer(
            val nodeId: String,
            val scopes: Set<PeerScope>,
            val services: Set<String> = emptySet()
        ) : Principal
    }

    sealed class Outcome {
        data class Authenticated(
            val actor: String,
            val principal: Principal = Principal.Operator
        ) : Outcome()

        data class Rejected(
            val status: Int,
            val code: String,
            val message: String,
            val retryAfterSeconds: Int = 0
        ) : Outcome()
    }

    data class LoginResult(
        val token: String?,
        val expiresAtMs: Long,
        val failure: Outcome.Rejected?
    )

    /** Coarse request budget; `null` means the request may proceed. */
    fun checkRequestRate(client: String): Outcome.Rejected? {
        val decision = requestLimiter.check(client)
        if (decision.allowed) return null
        return Outcome.Rejected(
            429,
            "TOO_MANY_REQUESTS",
            "request rate exceeded",
            decision.retryAfterSeconds.coerceAtLeast(1)
        )
    }

    fun passwordEnabled(): Boolean = config.auth != AuthMode.KEY && passwords != null

    fun login(client: String, password: String?): LoginResult {
        val gate = loginLimiter.check(client)
        if (gate.locked) {
            return LoginResult(
                null,
                0L,
                Outcome.Rejected(
                    429,
                    "TOO_MANY_REQUESTS",
                    "too many failed sign-in attempts",
                    gate.retryAfterSeconds.coerceAtLeast(1)
                )
            )
        }

        if (!passwordEnabled()) {
            return LoginResult(
                null,
                0L,
                Outcome.Rejected(
                    403,
                    "PASSWORD_AUTH_DISABLED",
                    "password sign-in is disabled; this server accepts Ed25519 signatures"
                )
            )
        }

        // Always run the KDF, even for a blank candidate, so the response time
        // does not depend on whether a password was supplied or configured.
        val hash = passwords ?: TIMING_DUMMY
        val candidate = password.orEmpty()
        val valid = hash.matches(candidate) && candidate.isNotEmpty() && passwords != null

        if (!valid) {
            loginLimiter.noteFailure(client)
            return LoginResult(
                null,
                0L,
                Outcome.Rejected(401, "INVALID_CREDENTIALS", "password is incorrect")
            )
        }

        loginLimiter.noteSuccess(client)
        val issued = sessions.issue()
        return LoginResult(issued.token, issued.expiresAtMs, null)
    }

    fun revoke(token: String?) {
        sessions.revoke(token)
    }

    /**
     * Verifies a request. The body passed here must be the exact body that was
     * hashed by the client; query strings are not covered by the signature, so
     * every state-changing parameter MUST travel in the JSON body.
     */
    fun authenticate(
        method: String,
        path: String,
        body: String,
        header: (String) -> String?
    ): Outcome {
        val bearer = Token.fromHeaders(header("authorization"), header("x-grom-token"))
        if (config.auth != AuthMode.KEY && bearer != null && sessions.validate(bearer)) {
            return Outcome.Authenticated("session", Principal.Operator)
        }

        if (config.auth != AuthMode.PASSWORD) {
            when (signer.verify(method, path, body, header)) {
                is RequestSigner.Result.Ok -> {
                    val keyId = header("x-grom-key")?.trim().orEmpty()
                    return Outcome.Authenticated("key:$keyId", principalOf(keyId))
                }
                is RequestSigner.Result.Rejected -> Unit
            }
        }

        return Outcome.Rejected(401, "UNAUTHORIZED", "valid credentials are required")
    }

    companion object {
        /**
         * Used only to make a login attempt cost the same when password auth is
         * configured but the candidate is empty. It is a fixed encoded hash; no
         * secret is involved.
         */
        private val TIMING_DUMMY: PasswordHash by lazy { PasswordHash.dummy() }
    }
}
