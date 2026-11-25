plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * The Grom operator app (D55).
 *
 * This module is only the surface plus the two role flavours. All server code
 * lives in `:engine`, which is an ordinary Android library so a host
 * application can embed the same server. The Node runtime binaries stay here
 * because the executable must be extracted into *this* app's nativeLibraryDir
 * (D34, `jniLibs` packaging below).
 */
android {
    namespace = "app.grom.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.grom.server"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.17.0"
    }

    // Two role flavours (F1 step 1):
    //   server29     — the full Grom server role, minSdk 29.
    //   peripheral26 — client/peripheral role only, minSdk 26, never binds a socket.
    flavorDimensions += "role"
    productFlavors {
        create("server29") {
            dimension = "role"
            minSdk = 29
            buildConfigField("boolean", "GROMSERVER", "true")
            versionNameSuffix = "-server29"
        }
        create("peripheral26") {
            dimension = "role"
            minSdk = 26
            buildConfigField("boolean", "GROMSERVER", "false")
            versionNameSuffix = "-peripheral26"
        }
    }

    // TEST-ONLY signing material. Never use these keys for a distributed build.
    signingConfigs {
        create("gromTest") {
            storeFile = rootProject.file("keys/grom-test-same.jks")
            storePassword = "gromtest"
            keyAlias = "grom"
            keyPassword = "gromtest"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("gromTest")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gromTest")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // D34: the Node runtime is executed from the APK's native library dir, so
    // the libraries must be extracted to disk at install time. This is the AGP 8
    // way to set android:extractNativeLibs="true"; setting the manifest
    // attribute directly is deprecated (and lint warns about it).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // sshd-core and sshd-common both ship Maven metadata and the
            // Apache licence files. One copy of each is enough; the licence and
            // the notice are still packaged and are also listed in
            // third_party/THIRD_PARTY.md.
            pickFirsts += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST"
            )
        }
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
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

/**
 * D87: the peripheral role never starts the Node agent, so it must not carry
 * the ~91 MB arm64 runtime just because the files sit in `src/main/jniLibs`.
 * The exclusion is applied per variant rather than by moving the files, so
 * `scripts/build-node-android.sh --install` keeps its documented destination and
 * the server role keeps the runtime it executes.
 */
androidComponents {
    onVariants(selector().withFlavor("role" to "peripheral26")) { variant ->
        variant.packaging.jniLibs.excludes.add("**")
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":sdk"))
    testImplementation("junit:junit:4.13.2")
}
