plugins {
    id("com.android.application")
}

// Release signing is driven entirely by the environment so no keystore or
// password ever lands in the repo (CI decodes a base64 secret to a file).
// When PIMALAYA_KEYSTORE is unset (local debug, forks without secrets) the
// release APK is simply left unsigned. PIMALAYA_KEYSTORE_PASSWORD unlocks
// it, PIMALAYA_KEYSTORE_ALIAS names the key (pimalaya when unset).
val releaseKeystore = System.getenv("PIMALAYA_KEYSTORE")?.let { file(it) }

android {
    namespace = "org.pimalaya"
    compileSdk = 35
    // Pinned to the one build-tools the devshell installs (see flake.nix),
    // newer than AGP's default, so AGP finds it instead of a missing one.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "org.pimalaya"
        // Android 8.0, the first release whose trust store ships ISRG
        // Root X1: below it Let's Encrypt certificates (most self-hosted
        // servers) fail the platform TLS validation the transport relies
        // on.
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.1.0"
    }

    buildFeatures {
        // BuildConfig.DEBUG gates the adb-only hooks out of release.
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                // PKCS12 (the default keystore format) forces the key
                // password to equal the store password, so one suffices.
                val password = System.getenv("PIMALAYA_KEYSTORE_PASSWORD")
                storeFile = releaseKeystore
                storePassword = password
                keyAlias = System.getenv("PIMALAYA_KEYSTORE_ALIAS") ?: "pimalaya"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // One APK per ABI (smaller per-device downloads) plus a universal APK
    // that runs anywhere; the native libpimalaya.so is the only per-ABI part.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric needs the manifest and resources to build its
            // simulated Android environment for the store/engine tests.
            isIncludeAndroidResources = true
        }
    }
}

// The engine-level unit tests load the real Rust bridge: cargo builds
// libpimalaya.so for the host, and the test JVM's library path points at
// it, so the :client Native class binds against the same code the app
// ships (needs cargo on the PATH, which the nix devshell provides). The
// crate sources are declared inputs and the .so an output, so editing
// the bridge rebuilds it; that .so is then an input of the test task, so
// a bridge change re-runs the tests (a stale .so would let a drift the
// tests exist to catch slip through).
val hostLibrary = file("../../rust/target/debug/libpimalaya.so")
val cargoHostBuild = tasks.register<Exec>("cargoHostBuild") {
    workingDir = file("../../rust")
    commandLine("cargo", "build")
    inputs.dir("../../rust/src")
    inputs.file("../../rust/Cargo.toml")
    inputs.file("../../rust/Cargo.lock")
    outputs.file(hostLibrary)
}

// The bundled SQLite's .so is built for Android only, so the store tests
// load a host build of the same binding and amalgamation, which the nix
// devshell provides (PIMALAYA_SQLITE_HOST, see flake.nix). Without it they
// would fail on a missing libsqlite3x, so the test task refuses to start.
val hostSqlite = System.getenv("PIMALAYA_SQLITE_HOST")

tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    inputs.file(hostLibrary)
    hostSqlite?.let { inputs.dir(it) }
    doFirst {
        if (hostSqlite == null) {
            throw GradleException(
                "PIMALAYA_SQLITE_HOST is unset: run the tests through `nix develop`"
            )
        }
    }
    val libraryPath = listOfNotNull(file("../../rust/target/debug").absolutePath, hostSqlite)
    systemProperty("java.library.path", libraryPath.joinToString(File.pathSeparator))
}

dependencies {
    // The app's only door to the native bridge: sockets, JNI and Rust stay inside.
    implementation(project(":client"))

    // The addressbooks drawer. A tiny, standalone AndroidX ViewGroup (no
    // AppCompat/Material theme needed), the one Jetpack concession the
    // framework never offered a built-in for; the sibling himalaya-android
    // uses the same.
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // Pull-to-refresh over the contacts list (triggers an account sync).
    // Another small, standalone AndroidX ViewGroup, no theme needed.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // SQLite bundled for every device: the pimdir store needs 3.37+ (STRICT
    // tables, RETURNING, json_each) and the platform's is 3.18 to 3.32 below
    // Android 14. The AOSP android.database.sqlite code under
    // io.requery.android.database.sqlite, SQLite 3.49.0 with JSON1. Bump it
    // with the host build in flake.nix. It asks androidx.core 1.15, which
    // would pull Kotlin coroutines, lifecycle and an app-startup provider
    // into an app that has none, for androidx.core.os.CancellationSignal
    // alone: the core the drawer already resolves (1.3) carries it.
    implementation("com.github.requery:sqlite-android:3.49.0") {
        exclude(group = "androidx.core", module = "core")
    }

    // JVM-only test dependencies (nothing ships in the APK). The org.json
    // artifact stands in for the android.jar stubs so Mapping runs on the
    // host JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Robolectric backs the engine-level tests with a real Android
    // runtime on the host JVM: the SQLite store and the framework pieces
    // CardStore and OfflineEngine touch, combined with the real Rust
    // bridge loaded from the host cargo build (see cargoHostBuild).
    testImplementation("org.robolectric:robolectric:4.14.1")
}
