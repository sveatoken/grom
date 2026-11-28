plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * A compiled hosting example (D112).
 *
 * `docs/TWO-IN-ONE.md` describes how a host embeds the engine, and `GromEmbed`
 * is the API it describes — but until this module existed neither had a real
 * caller: the standalone app talks to the service directly, and the two test
 * apps only prove the signing gate. The guide could therefore describe a build
 * that does not work (it did: the agent path was resolved from `rootProject`),
 * and no gate noticed.
 *
 * It is a library, like `examples/connect`, so the example cannot quietly turn
 * into a second server: it depends on `:engine` exactly the way a host does and
 * its tests drive the pure mapping functions a host needs.
 */
android {
    namespace = "app.grom.examples.embedded"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":engine"))
    testImplementation("junit:junit:4.13.2")
}
