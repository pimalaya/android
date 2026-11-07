plugins {
    id("com.android.application")
}

// Release signing is driven entirely by the environment so no keystore or
// password ever lands in the repo (CI decodes a base64 secret to a file).
// When PIMALAYA_KEYSTORE is unset (local debug, forks without secrets) the
// release APK is simply left unsigned. PIMALAYA_KEYSTORE_PASSWORD unlocks
// it, PIMALAYA_KEYSTORE_ALIAS names the key (pimalaya when unset).
val releaseKeystore = System.getenv("PIMALAYA_KEYSTORE")?.let { file(it) }

// Google's OAuth client per build type, each named by the part before
// .apps.googleusercontent.com: release builds (every channel) sign in through
// the verified "Pimalaya" project, debug builds through "Pimalaya Dev". Each
// account keeps the client it signed in with, so a switch strands no grant.
val googleReleaseClient = "803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco"
val googleDebugClient = "991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1"

// The client ID for Oauth.java, and its reversed form as the redirect scheme,
// for Oauth.java and the manifest's intent-filter.
fun com.android.build.api.dsl.ApplicationBuildType.googleClient(name: String) {
    val scheme = "com.googleusercontent.apps.$name"
    buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$name.apps.googleusercontent.com\"")
    buildConfigField("String", "GOOGLE_REDIRECT_SCHEME", "\"$scheme\"")
    manifestPlaceholders["googleRedirectScheme"] = scheme
}

android {
    namespace = "org.pimalaya"
    compileSdk = 36
    // Pinned to the one build-tools the devshell installs (see flake.nix),
    // newer than AGP's default, so AGP finds it instead of a missing one.
    buildToolsVersion = "36.1.0"
    // The devshell NDK (see flake.nix), so AGP can strip the Rust .so.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.pimalaya"
        // Android 8.0, the first release whose trust store ships ISRG
        // Root X1: below it Let's Encrypt certificates (most self-hosted
        // servers) fail the platform TLS validation the transport relies
        // on.
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.0"
        // The app's own languages: drops the other locales libraries
        // (AndroidX, Material) bring, so the app is not half-translated
        // into them.
        resourceConfigurations += listOf("en", "fr")
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
        debug {
            googleClient(googleDebugClient)
        }
        release {
            googleClient(googleReleaseClient)
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
    // Off for a bundle, which AGP refuses to build beside split APKs
    // (issuetracker.google.com/402800800): Play splits it per device itself.
    val bundling = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    splits {
        abi {
            isEnable = !bundling
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
    inputs.dir("../../rust/pimdir")
    inputs.file("../../rust/build.rs")
    inputs.file("../../rust/Cargo.toml")
    inputs.file("../../rust/Cargo.lock")
    outputs.file(hostLibrary)
}

// The store opens its databases through the platform's android.database.sqlite,
// whose natives the tests hand to a host build of the platform's JNI over
// Android 8.0's SQLite 3.18.2, the oldest the app runs on (flake.nix
// sqliteHost, robolectric.properties). The nix devshell provides it as
// PIMALAYA_SQLITE_HOST; without it every store test would fail on a missing
// libsqlitehost, so the test task refuses to start.
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
    if (name != "e2eTest") {
        exclude("org/pimalaya/**/E2e*")
    }
}

// The end-to-end suite (cairn/changes/e2e-suite): the engines driven
// headlessly against the Pimalaya test accounts. Its classes compile with
// the unit tests, being package-private neighbours of the engines, and run
// only here, never under testDebugUnitTest or CI. Targets and credentials
// come from -Pe2e.targets and the environment; with none, every test skips.
android.sourceSets["test"].java.srcDir("src/e2eTest/java")

tasks.register<Test>("e2eTest") {
    description = "Runs the end-to-end suite against the targets in -Pe2e.targets."
    group = "verification"
    val unit = tasks.named<Test>("testDebugUnitTest")
    dependsOn(unit.map { it.dependsOn })
    testClassesDirs = files(unit.map { it.testClassesDirs })
    classpath = files(unit.map { it.classpath })
    include("org/pimalaya/**/E2e*")
    for (option in listOf("e2e.targets", "e2e.only", "e2e.patience", "e2e.log", "e2e.prefix")) {
        providers.gradleProperty(option).orNull?.let { systemProperty(option, it) }
    }
    systemProperty("e2e.report", layout.buildDirectory.dir("reports/e2e").get().asFile.path)
    // NOTE: the servers are the state, so a run is never up to date.
    outputs.upToDateWhen { false }
    // NOTE: one test at a time: the targets are shared accounts, and
    // Gmail's per-user quota is the binding limit.
    maxParallelForks = 1
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
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

    // JVM-only test dependencies (nothing ships in the APK). The org.json
    // artifact stands in for the android.jar stubs so Mapping runs on the
    // host JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Robolectric backs the engine-level tests with a real Android
    // runtime on the host JVM: the SQLite store and the framework pieces
    // CardStore and ContactEngine touch, combined with the real Rust
    // bridge loaded from the host cargo build (see cargoHostBuild).
    testImplementation("org.robolectric:robolectric:4.16.1")
}
