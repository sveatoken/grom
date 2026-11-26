// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import app.grom.server.core.AgentConfig
import java.io.File

/**
 * Builds the exact process launch for the agent (D11, D24, D34, S3-R004…S3-R009).
 *
 * Invariants enforced here and asserted by the JVM tests:
 *  - argv contains no secret and no user data;
 *  - the environment is constructed from scratch, never inherited;
 *  - the token travels only in [LaunchSpec.stdin].
 *
 * Why the binary does not live beside the script (D34): Android refuses
 * `execve()` of files in app-private storage for apps targeting API 29+, so a
 * runtime copied into `files/` could never start. The binary is packaged as an
 * APK native library and executed from `ApplicationInfo.nativeLibraryDir`,
 * while the JavaScript entry point stays a data file that Node reads.
 */
object AgentLauncher {

    const val BINARY_NAME: String = "libnode.so"
    const val SCRIPT_NAME: String = "grom-agent.js"
    const val LOG_LEVEL_DEFAULT: String = "info"

    /**
     * @param directory        where [SCRIPT_NAME] lives (app-private storage)
     * @param binaryDirectory  where [BINARY_NAME] lives (native library dir)
     * @param libraryDirectory shared objects beside the binary, when the build
     *                         is dynamically linked to bionic libraries
     */
    data class RuntimeFiles(
        val directory: File,
        val binaryDirectory: File = directory,
        val libraryDirectory: File? = null
    ) {
        val binary: File get() = File(binaryDirectory, BINARY_NAME)
        val script: File get() = File(directory, SCRIPT_NAME)

        val present: Boolean get() = missingFile() == null

        /** The first missing prerequisite, or null when the runtime is complete. */
        fun missingFile(): String? = when {
            !directory.isDirectory -> directory.name
            !binary.isFile -> BINARY_NAME
            !binary.canExecute() -> "$BINARY_NAME (not executable)"
            !script.isFile -> SCRIPT_NAME
            else -> null
        }
    }

    data class LaunchSpec(
        val argv: List<String>,
        val environment: Map<String, String>,
        val stdin: String,
        val workingDirectory: String
    )

    fun build(
        runtime: RuntimeFiles,
        port: Int,
        token: String,
        logLevel: String,
        parentPid: Long,
        homeDir: File,
        tmpDir: File
    ): LaunchSpec {
        val missing = runtime.missingFile()
        require(missing == null) { "agent runtime incomplete: missing $missing" }

        // No engine switch here on purpose (D98). The agent is CommonJS, and
        // every Node LTS this project has pinned loads it that way with no
        // switch. `--experimental-default-type` was accepted by an older runtime
        // and rejected by the pinned one; a rejected argv exits before
        // GROM_AGENT_READY is printed, so the server looks installed and dead.
        // Only `--verify` may add a flag, after proving that the packaged
        // binary accepts it (scripts/build-node-android.sh).
        val argv = listOf(
            runtime.binary.absolutePath,
            runtime.script.absolutePath,
            "--port", port.toString(),
            "--parent-pid", parentPid.toString()
        )

        // Constructed from scratch (S3-R006): nothing is inherited.
        val environment = linkedMapOf(
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "/system/bin",
            "GROM_LOG_LEVEL" to logLevel,
            "LANG" to "C"
        )

        // A dynamically linked runtime resolves libssl/libicu/… from the APK's
        // native library dir. Only add the variable when there is something to
        // add: a static build must not carry a meaningless search path.
        runtime.libraryDirectory?.let { libraries ->
            environment["LD_LIBRARY_PATH"] = libraries.absolutePath
        }

        val stdin = AgentConfig.encode(token = token, port = port, logLevel = logLevel)

        return LaunchSpec(
            argv = argv,
            environment = environment,
            stdin = stdin,
            workingDirectory = runtime.directory.absolutePath
        )
    }

    /** Log-safe rendering of a launch: argv only, and argv never holds a secret. */
    fun describe(spec: LaunchSpec): String =
        "argv=${spec.argv.joinToString(" ")} envKeys=${spec.environment.keys.sorted()}"
}
