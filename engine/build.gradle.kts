plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * app.grom.engine — the server engine as an embeddable library (D55).
 *
 * Everything the server needs at runtime lives here: the supervisor, the
 * loopback control plane, the public data plane, the audit chain, the secret
 * store, the project registry and the SSH bridge. `app` is only the operator
 * surface plus the two role flavours; a host application can depend on this
 * module and get the whole server without a second installation.
 *
 * The module deliberately does not carry the Node runtime binaries. `jniLibs`
 * belongs to the host application, because the executable must land in that
 * app's nativeLibraryDir (D34).
 */
android {
    namespace = "app.grom.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // The engine ships no UI. Consumer rules are not needed while
        // minification is off; the file exists so a host can enable R8 safely.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
        aidl = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // MINA SSHD logs through slf4j. Tests replace the no-op binding with a
        // simple one at debug level; the production APK keeps slf4j-nop so no
        // session detail can ever reach logcat.
        unitTests.all {
            it.systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
            // A caller that narrows this task (tools/test-agent runs it with
            // `--tests`) writes its JUnit XML to its own directory when it asks
            // to. Gradle keeps one results directory per task and clears it at
            // the start of every run, so a filtered run otherwise erases the
            // full suite's evidence: five result files survived for 92 test
            // classes, and every later inspection agreed that 43 of 838 tests
            // had run. `tools/check-test-reports` reads the real one.
            //
            // This is configured here rather than through `tasks.withType<Test>`
            // because AGP assigns the reports directory after the task is
            // created; a `configureEach` block runs first and is overwritten.
            val isolated = providers.gradleProperty("gromTestReportsDir").orNull
            if (isolated != null) {
                it.reports.junitXml.outputLocation.set(layout.buildDirectory.dir(isolated))
                it.reports.html.outputLocation.set(
                    layout.buildDirectory.dir("reports/isolated-tests/${it.name}")
                )
            }
        }
    }

    lint {
        // The SSH transport (Apache MINA SSHD) reaches one desktop-only class
        // through an `instanceof` in its exception peeler. The engine provides
        // the missing class (see engine/src/main/java/javax/management) and the
        // lint suppression is narrowed to that exact reference below.
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = true
    }
}

/**
 * Copies the canonical agent script into a generated assets directory.
 *
 * It is registered through `variant.sources.assets.addGeneratedSourceDirectory`
 * rather than wired by task name, so every consumer (merge assets, packaging
 * and lint's model writer) gets a declared dependency automatically. Pattern
 * matching on task names is what produced an implicit-dependency failure.
 */
abstract class SyncAgentRuntime : DefaultTask() {
    @get:InputFile
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun sync() {
        // The directory is an assets root, so the agent lands at node/<name>.
        val target = outputDirectory.get().asFile.resolve("node/grom-agent.js")
        target.parentFile.mkdirs()
        sourceFile.get().asFile.copyTo(target, overwrite = true)
    }
}

val syncAgentRuntime = tasks.register<SyncAgentRuntime>("syncAgentRuntime") {
    sourceFile.set(rootProject.layout.projectDirectory.file("agent/grom-agent.js"))
    outputDirectory.set(layout.buildDirectory.dir("generated/agent-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncAgentRuntime) { it.outputDirectory }
    }
}

dependencies {
    // The IPC interface is part of the engine's public surface: an embedded host
    // talks to the service through exactly the same Binder contract.
    api(project(":sdk"))

    // Reverse SSH tunnel (D58). Apache-2.0; Ed25519 support comes from the
    // optional CC0 provider that MINA SSHD uses when it is on the classpath.
    implementation("org.apache.sshd:sshd-core:2.13.1")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    // MINA SSHD logs through slf4j; a no-op binding keeps logcat clean and
    // guarantees that a secret never reaches a log sink.
    implementation("org.slf4j:slf4j-nop:1.7.36")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.slf4j:slf4j-simple:1.7.36")
}

configurations.matching { it.name == "testRuntimeClasspath" }.configureEach {
    exclude(group = "org.slf4j", module = "slf4j-nop")
}
