{
  description = "Pimalaya for Android: manage your contacts (Rust + Kotlin)";

  inputs = {
    nixpkgs = {
      url = "github:nixos/nixpkgs/nixos-25.11";
    };
    fenix = {
      url = "github:nix-community/fenix/monthly";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    flake-utils = {
      url = "github:numtide/flake-utils";
    };
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
  };

  outputs =
    {
      nixpkgs,
      fenix,
      flake-utils,
      ...
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        rust = fenix.packages.${system}.combine [
          (fenix.packages.${system}.stable.withComponents [
            "cargo"
            "clippy"
            "rust-analyzer"
            "rust-src"
            "rustc"
            "rustfmt"
          ])
          fenix.packages.${system}.targets.aarch64-linux-android.stable.rust-std
          fenix.packages.${system}.targets.armv7-linux-androideabi.stable.rust-std
          fenix.packages.${system}.targets.x86_64-linux-android.stable.rust-std
          fenix.packages.${system}.targets.i686-linux-android.stable.rust-std
        ];

        # Bump these together; the NDK version must exist in the pinned
        # nixpkgs androidenv. NDK r27+ aligns native libraries to 16 KB
        # pages by default, which Play requires for apps targeting API 35.
        buildToolsVersion = "36.1.0";
        ndkVersion = "29.0.14206865";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" ];
          buildToolsVersions = [ buildToolsVersion ];
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          cmakeVersions = [ "3.22.1" ];
          includeEmulator = false;
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";

        # The app bundles SQLite through com.github.requery:sqlite-android
        # (android/app/build.gradle.kts), whose .so is built for Android
        # only. The unit tests run on the host JVM (Robolectric), so this
        # builds the same binding JNI over the same amalgamation, with the
        # flags of its Android.mk, as a host libsqlite3x.so the test JVM
        # loads. Bump both sources together with the Gradle dependency.
        sqliteAndroidVersion = "3.49.0";
        sqliteAndroidSrc = pkgs.fetchFromGitHub {
          owner = "requery";
          repo = "sqlite-android";
          rev = sqliteAndroidVersion;
          sha256 = "0x0763yxaz5xfjsq6pyvnpm1ir9k538n0scqg5y8crhg8zz2y6yd";
        };
        sqliteAmalgamation = pkgs.fetchurl {
          url = "https://www.sqlite.org/2025/sqlite-amalgamation-3490000.zip";
          sha256 = "0fzijga1rvjl79c80xvlqi93b26ppd1gc82c05r3d4blmpmm2s6b";
        };
        sqliteHost = pkgs.stdenv.mkDerivation {
          pname = "sqlite-android-host";
          version = sqliteAndroidVersion;
          src = sqliteAndroidSrc;
          nativeBuildInputs = [ pkgs.unzip ];
          buildPhase = ''
            jni=sqlite-android/src/main/jni/sqlite
            unzip -j ${sqliteAmalgamation} '*/sqlite3.c' '*/sqlite3.h' -d $jni

            # The binding logs through liblog; on the host it goes to stderr.
            mkdir -p stub/android
            cat > stub/android/log.h <<'EOF'
            #pragma once
            #include <stdarg.h>
            #include <stdio.h>
            enum { ANDROID_LOG_VERBOSE = 2, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO,
                   ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL };
            static inline int __android_log_print(int prio, const char *tag,
                                                  const char *fmt, ...) {
              va_list args;
              va_start(args, fmt);
              fprintf(stderr, "%s: ", tag);
              int n = vfprintf(stderr, fmt, args);
              fputc('\n', stderr);
              va_end(args);
              return n;
            }
            EOF
            # The NDK's jni.h names the C view of JNIEnv, OpenJDK's does not.
            cat > stub/c_jnienv.h <<'EOF'
            #pragma once
            #include <jni.h>
            typedef const struct JNINativeInterface_ *C_JNIEnv;
            EOF

            flags="-DNDEBUG=1 -DHAVE_USLEEP=1 -DSQLITE_HAVE_ISNAN \
              -DSQLITE_DEFAULT_JOURNAL_SIZE_LIMIT=1048576 -DSQLITE_THREADSAFE=2 \
              -DSQLITE_TEMP_STORE=3 -DSQLITE_POWERSAFE_OVERWRITE=1 \
              -DSQLITE_DEFAULT_FILE_FORMAT=4 -DSQLITE_DEFAULT_AUTOVACUUM=1 \
              -DSQLITE_ENABLE_MEMORY_MANAGEMENT=1 -DSQLITE_ENABLE_FTS3 \
              -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS4 \
              -DSQLITE_ENABLE_FTS4_PARENTHESIS -DSQLITE_ENABLE_FTS5 \
              -DSQLITE_ENABLE_FTS5_PARENTHESIS -DSQLITE_ENABLE_JSON1 \
              -DSQLITE_ENABLE_RTREE=1 -DSQLITE_UNTESTABLE \
              -DSQLITE_OMIT_COMPILEOPTION_DIAGS \
              -DSQLITE_DEFAULT_FILE_PERMISSIONS=0600 -DSQLITE_DEFAULT_MEMSTATUS=0 \
              -DSQLITE_MAX_EXPR_DEPTH=0 -DSQLITE_USE_ALLOCA \
              -DSQLITE_ENABLE_BATCH_ATOMIC_WRITE -DPACKED= -O2 -fPIC \
              -I$jni -Istub -I${pkgs.jdk17.home}/include \
              -I${pkgs.jdk17.home}/include/linux"

            $CC $flags -c $jni/sqlite3.c -o sqlite3.o
            for source in $jni/*.cpp; do
              $CXX $flags -include stub/c_jnienv.h -Wno-conversion-null -c "$source" -o "$(basename "$source" .cpp).o"
            done
            # NOTE: -Bsymbolic binds the binding to its own sqlite3_*, never
            # to another SQLite the test JVM may have loaded first.
            $CXX -shared -Wl,-Bsymbolic -o libsqlite3x.so *.o -ldl -lpthread
          '';
          installPhase = ''
            install -Dm644 libsqlite3x.so $out/lib/libsqlite3x.so
          '';
        };
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            rust
            pkgs.cargo-deny
            pkgs.cargo-ndk
            pkgs.jdk17
            pkgs.gradle
            androidSdk
            pkgs.jdt-language-server
          ];

          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${ndkVersion}";
          ANDROID_NDK_HOME = "${sdkRoot}/ndk/${ndkVersion}";
          JAVA_HOME = pkgs.jdk17.home;
          PIMALAYA_SQLITE_HOST = "${sqliteHost}/lib";

          # AGP ships a Maven aapt2 dynamically linked for generic Linux,
          # which cannot run on NixOS. The override must reach the Gradle
          # daemon, so it goes through gradle.properties (GRADLE_OPTS only
          # configures the client JVM). An isolated, gitignored
          # GRADLE_USER_HOME keeps the store-specific path out of the
          # tracked config and out of other projects' ~/.gradle.
          shellHook = ''
            # Anchor to the repo root, not $PWD: a relative home would
            # spawn a stray android/.gradle-home under whatever directory
            # nix develop was entered from.
            root="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
            export GRADLE_USER_HOME="$root/android/.gradle-home"
            mkdir -p "$GRADLE_USER_HOME"
            echo "android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2" \
              > "$GRADLE_USER_HOME/gradle.properties"
          '';
        };
      }
    );
}
