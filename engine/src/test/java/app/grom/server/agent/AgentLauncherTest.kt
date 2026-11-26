// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import app.grom.server.core.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentLauncherTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val token = Token.generate()

    private fun runtimeDir(executable: Boolean = true): File {
        val dir = temporary.newFolder("node")
        File(dir, AgentLauncher.BINARY_NAME).apply {
            writeText("#!/bin/sh\n")
            setExecutable(executable)
        }
        File(dir, AgentLauncher.SCRIPT_NAME).writeText("// agent\n")
        return dir
    }

    private fun launch(dir: File, port: Int = 47001) = AgentLauncher.build(
        runtime = AgentLauncher.RuntimeFiles(dir),
        port = port,
        token = token,
        logLevel = "info",
        parentPid = 1234L,
        homeDir = temporary.newFolder("home"),
        tmpDir = temporary.newFolder("tmp")
    )

    @Test
    fun `runtime is detected when both files exist`() {
        assertTrue(AgentLauncher.RuntimeFiles(runtimeDir()).present)
    }

    @Test
    fun `missing binary is named`() {
        val dir = temporary.newFolder("empty")
        assertEquals(AgentLauncher.BINARY_NAME, AgentLauncher.RuntimeFiles(dir).missingFile())
    }

    @Test
    fun `non executable binary is reported`() {
        val missing = AgentLauncher.RuntimeFiles(runtimeDir(executable = false)).missingFile()
        assertTrue(missing!!.contains("not executable"))
    }

    @Test
    fun `missing script is named`() {
        val dir = temporary.newFolder("node")
        File(dir, AgentLauncher.BINARY_NAME).apply {
            writeText("")
            setExecutable(true)
        }
        assertEquals(AgentLauncher.SCRIPT_NAME, AgentLauncher.RuntimeFiles(dir).missingFile())
    }

    @Test
    fun `argv follows the documented contract`() {
        val spec = launch(runtimeDir())
        assertEquals(6, spec.argv.size)
        assertTrue(spec.argv[0].endsWith(AgentLauncher.BINARY_NAME))
        assertTrue(spec.argv[1].endsWith(AgentLauncher.SCRIPT_NAME))
        assertEquals("--port", spec.argv[2])
        assertEquals("47001", spec.argv[3])
        assertEquals("--parent-pid", spec.argv[4])
        assertEquals("1234", spec.argv[5])
        // No engine switch (D98): the pinned Node rejected
        // --experimental-default-type, and a rejected argv kills the agent
        // before it announces readiness.
        assertFalse(spec.argv.any { it.startsWith("--experimental-") })
    }

    @Test
    fun `token never appears in argv`() {
        val spec = launch(runtimeDir())
        assertFalse(spec.argv.any { it.contains(token) })
    }

    @Test
    fun `token never appears in the environment`() {
        val spec = launch(runtimeDir())
        assertFalse(spec.environment.values.any { it.contains(token) })
    }

    @Test
    fun `environment is constructed from scratch`() {
        val spec = launch(runtimeDir())
        assertEquals(
            setOf("HOME", "TMPDIR", "PATH", "GROM_LOG_LEVEL", "LANG"),
            spec.environment.keys
        )
        assertEquals("/system/bin", spec.environment["PATH"])
    }

    @Test
    fun `token travels over stdin`() {
        val spec = launch(runtimeDir())
        assertTrue(spec.stdin.contains(token))
        assertTrue(spec.stdin.endsWith("\n"))
    }

    @Test
    fun `working directory is the runtime directory`() {
        val dir = runtimeDir()
        assertEquals(dir.absolutePath, launch(dir).workingDirectory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build refuses an incomplete runtime`() {
        launch(temporary.newFolder("nothing"))
    }

    @Test
    fun `describe contains no secret`() {
        assertFalse(AgentLauncher.describe(launch(runtimeDir())).contains(token))
    }

    // ---------------------------------------------------------------- D34 split
    //
    // Android denies execve() of files in app-private storage, so the binary is
    // launched from the native library dir while the script stays behind in the
    // data dir. These tests pin that layout, because getting it wrong means the
    // agent can never start on a real device.

    private fun nativeRuntime(): AgentLauncher.RuntimeFiles {
        val scriptDir = temporary.newFolder("data")
        File(scriptDir, AgentLauncher.SCRIPT_NAME).writeText("// agent\n")
        val libDir = temporary.newFolder("lib")
        File(libDir, AgentLauncher.BINARY_NAME).apply {
            writeText("")
            setExecutable(true)
        }
        return AgentLauncher.RuntimeFiles(scriptDir, libDir, libDir)
    }

    private fun launchRuntime(runtime: AgentLauncher.RuntimeFiles) = AgentLauncher.build(
        runtime = runtime,
        port = 47001,
        token = token,
        logLevel = "info",
        parentPid = 1234L,
        homeDir = temporary.newFolder("home"),
        tmpDir = temporary.newFolder("tmp")
    )

    @Test
    fun `binary runs from the native library dir and script from data`() {
        val runtime = nativeRuntime()
        val spec = launchRuntime(runtime)
        assertEquals(runtime.binaryDirectory.absolutePath + "/" + AgentLauncher.BINARY_NAME, spec.argv[0])
        assertEquals(runtime.script.absolutePath, spec.argv[1])
        assertTrue(runtime.present)
    }

    @Test
    fun `library directory becomes LD_LIBRARY_PATH`() {
        val runtime = nativeRuntime()
        assertEquals(
            runtime.libraryDirectory!!.absolutePath,
            launchRuntime(runtime).environment["LD_LIBRARY_PATH"]
        )
    }

    @Test
    fun `a static runtime exports no LD_LIBRARY_PATH`() {
        assertFalse(launch(runtimeDir()).environment.containsKey("LD_LIBRARY_PATH"))
    }

    @Test
    fun `missing binary in the native dir is reported even if the script dir has one`() {
        val scriptDir = runtimeDir()
        val libDir = temporary.newFolder("emptylib")
        val runtime = AgentLauncher.RuntimeFiles(scriptDir, libDir, libDir)
        assertEquals(AgentLauncher.BINARY_NAME, runtime.missingFile())
    }
}
