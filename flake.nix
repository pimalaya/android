{
  description = "Pimalaya for Android: manage your contacts (Rust + Kotlin)";

  inputs = {
    nixpkgs = {
      url = "github:nixos/nixpkgs/nixos-26.05";
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
          platformVersions = [ "36" ];
          buildToolsVersions = [ buildToolsVersion ];
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          cmakeVersions = [ "3.22.1" ];
          includeEmulator = false;
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";

        # The oldest platform SQLite the app runs on: Android 8.0 (API 26)
        # ships 3.18.2, built with the flags of AOSP external/sqlite
        # dist/Android.mk on oreo-release (Android.bp came later):
        # https://android.googlesource.com/platform/external/sqlite/+/refs/heads/oreo-release/dist/Android.mk
        # FTS3 and FTS4 with the standard query syntax (no
        # SQLITE_ENABLE_FTS3_PARENTHESIS), no FTS5, no JSON1; 3.18 predates
        # SQLITE_DEFAULT_LEGACY_ALTER_TABLE. The device flags are taken
        # whole, ICU included over nixpkgs' ICU4C. Dropped, being Android
        # only: liblog, and libsqlite3_android, the functions and collators
        # (PHONE_NUMBERS_EQUAL, _TOKENIZE, LOCALIZED, UNICODE) the framework
        # registers on each connection, which no pimdir statement calls.
        # AOSP's sqlite3.c is upstream 3.18.2 with an FTS3 pointer fix and
        # the Android.patch hunks (fstat logging, fts1 and fts2 aliases
        # under SQLITE_ENABLE_FTS3_BACKWARDS, fts3_tokenizer off), none of
        # which changes what parses. sqlite.org has no 3.18.2 amalgamation
        # zip, so the sources come from the autoconf tarball.
        #
        # bin/pimalaya-sqlite-floor-check (nix/sqlite-floor-check.c) runs
        # the migrations and prepares the statements the bridge serves on
        # it, for rust/src/ffi/offline.rs's floor test; bin/sqlite3 is the
        # shell of the same build, lib/libsqlite3.so the library.
        sqliteFloorVersion = "3.18.2";
        sqliteFloorFlags = builtins.concatStringsSep " " [
          "-DNDEBUG=1 -DHAVE_USLEEP=1 -DSQLITE_HAVE_ISNAN"
          "-DSQLITE_DEFAULT_JOURNAL_SIZE_LIMIT=1048576 -DSQLITE_THREADSAFE=2"
          "-DSQLITE_TEMP_STORE=3 -DSQLITE_POWERSAFE_OVERWRITE=1"
          "-DSQLITE_DEFAULT_FILE_FORMAT=4 -DSQLITE_DEFAULT_AUTOVACUUM=1"
          "-DSQLITE_ENABLE_MEMORY_MANAGEMENT=1 -DSQLITE_ENABLE_FTS3"
          "-DSQLITE_ENABLE_FTS3_BACKWARDS -DSQLITE_ENABLE_FTS4"
          "-DSQLITE_OMIT_BUILTIN_TEST -DSQLITE_OMIT_COMPILEOPTION_DIAGS"
          "-DSQLITE_OMIT_LOAD_EXTENSION"
          "-DSQLITE_DEFAULT_FILE_PERMISSIONS=0600 -DSQLITE_SECURE_DELETE"
          "-DSQLITE_ENABLE_ICU -DUSE_PREAD64 -Dfdatasync=fdatasync"
          "-DHAVE_MALLOC_H=1 -DHAVE_MALLOC_USABLE_SIZE"
          "-DHAVE_POSIX_FALLOCATE=1 -O2 -fPIC"
        ];
        sqliteFloor = pkgs.stdenv.mkDerivation {
          pname = "sqlite-floor";
          version = sqliteFloorVersion;
          src = pkgs.fetchurl {
            url = "https://www.sqlite.org/2017/sqlite-autoconf-3180200.tar.gz";
            sha256 = "1fn188cpk8rl5n6f1jslyz2zbvvhap44m6ai1icmk98qm0p24h0d";
          };
          buildInputs = [ pkgs.icu ];
          dontConfigure = true;
          buildPhase = ''
            flags="${sqliteFloorFlags} -I."
            libs="-licui18n -licuuc -ldl -lpthread -lm"

            $CC $flags -c sqlite3.c -o sqlite3.o
            $CC -shared -o libsqlite3.so sqlite3.o $libs
            $CC $flags shell.c sqlite3.o -o sqlite3 $libs
            $CC -O2 -Wall -Werror -I. ${./nix/sqlite-floor-check.c} sqlite3.o \
              -o pimalaya-sqlite-floor-check $libs
          '';
          installPhase = ''
            install -Dm644 libsqlite3.so $out/lib/libsqlite3.so
            install -Dm644 sqlite3.h $out/include/sqlite3.h
            install -Dm755 sqlite3 $out/bin/sqlite3
            install -Dm755 pimalaya-sqlite-floor-check \
              $out/bin/pimalaya-sqlite-floor-check
          '';
          # NOTE: the flags are proven, not trusted: the version, FTS4 and
          # the standard query syntax (AND a term, not an operator) in, FTS5
          # and JSON out.
          doInstallCheck = true;
          installCheckPhase = ''
            sql=$out/bin/sqlite3
            test "$($sql :memory: 'SELECT sqlite_version();')" = ${sqliteFloorVersion}
            test "$($sql :memory: "CREATE VIRTUAL TABLE t USING fts4(x);
              INSERT INTO t VALUES('a b');
              SELECT count(*) FROM t WHERE t MATCH 'a AND b';")" = 0
            for absent in 'CREATE VIRTUAL TABLE t USING fts5(x);' \
              "SELECT json('{}');"; do
              if $sql :memory: "$absent" 2>/dev/null; then
                echo "the floor build accepts: $absent" >&2
                exit 1
              fi
            done
          '';
        };

        # The platform's SQLite for the JVM tests. The app opens its
        # databases through android.database.sqlite, whose natives
        # Robolectric shadows; the tests' own shadows
        # (android/app/src/test/java/org/pimalaya/sqlite/) hand them to this
        # library, so the platform's helper, pool and cursors run every
        # statement on the floor's SQLite, built with sqliteFloor's flags
        # (PIMALAYA_SQLITE_HOST, android/app/build.gradle.kts). The natives
        # are the AOSP JNI as requery's binding carries it, built for the
        # host, with a stderr stub standing for liblog and a C_JNIEnv shim
        # for OpenJDK's jni.h. requery's Java is not used, and nothing of it
        # reaches the app.
        sqliteJniVersion = "3.49.0";
        sqliteJni = pkgs.fetchFromGitHub {
          owner = "requery";
          repo = "sqlite-android";
          rev = sqliteJniVersion;
          sha256 = "0x0763yxaz5xfjsq6pyvnpm1ir9k538n0scqg5y8crhg8zz2y6yd";
        };
        # The JNI of sqliteHost: requery's SQLiteConnection, CursorWindow and
        # SQLiteGlobal natives, unchanged, registered on the tests' class
        # org.pimalaya.sqlite.HostSqlite. Only the natives the platform's
        # classes declare are registered: requery's own (custom functions,
        # the update hook, extensions) are left out, and the three the
        # platform added from API 35 (nativeLastInsertRowId, nativeChanges,
        # nativeTotalChanges) are written here.
        sqliteHostJni = pkgs.writeText "sqlite-host-jni.cpp" ''
          #include "sqlite3.h"

          // NOTE: Android builds SQLite without extension loading, and
          // requery's nativeLoadExtension, never registered, names these.
          extern "C" int sqlite3_enable_load_extension(sqlite3*, int) {
              return SQLITE_ERROR;
          }

          extern "C" int sqlite3_load_extension(sqlite3*, const char*,
                                                const char*, char**) {
              return SQLITE_ERROR;
          }

          #define JNI_OnLoad requery_JNI_OnLoad
          #define sMethods sConnectionMethods
          #include "android_database_SQLiteConnection.cpp"
          #undef sMethods
          #undef JNI_OnLoad
          #undef LOG_TAG

          #define sMethods sWindowMethods
          #include "android_database_CursorWindow.cpp"
          #undef sMethods
          #undef LOG_TAG

          #define sMethods sGlobalMethods
          #include "android_database_SQLiteGlobal.cpp"
          #undef sMethods

          #include <cstring>
          #include <vector>

          namespace android {

          static jlong nativeLastInsertRowId(JNIEnv*, jclass, jlong pointer) {
              SQLiteConnection* connection =
                  reinterpret_cast<SQLiteConnection*>(pointer);
              return sqlite3_last_insert_rowid(connection->db);
          }

          static jlong nativeChanges(JNIEnv*, jclass, jlong pointer) {
              SQLiteConnection* connection =
                  reinterpret_cast<SQLiteConnection*>(pointer);
              return sqlite3_changes(connection->db);
          }

          static jlong nativeTotalChanges(JNIEnv*, jclass, jlong pointer) {
              SQLiteConnection* connection =
                  reinterpret_cast<SQLiteConnection*>(pointer);
              return sqlite3_total_changes(connection->db);
          }

          static const char* const sLeftOut[] = {
              "nativeRegisterCustomFunction",
              "nativeRegisterFunction",
              "nativeRegisterUpdateHook",
              "nativeHasCodec",
              "nativeLoadExtension",
          };

          static bool leftOut(const char* name) {
              for (const char* out : sLeftOut) {
                  if (strcmp(name, out) == 0) {
                      return true;
                  }
              }
              return false;
          }

          static JNINativeMethod native(const char* name, void* function) {
              return {const_cast<char*>(name), const_cast<char*>("(J)J"),
                      function};
          }

          } // namespace android

          extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
              JNIEnv* env = nullptr;
              if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)
                      != JNI_OK) {
                  return JNI_ERR;
              }
              android::gpJavaVM = vm;
              android::sqliteInitialize();

              // NOTE: what register_android_database_CursorWindow sets up,
              // but for the class it registers on.
              jclass buffer = env->FindClass("android/database/CharArrayBuffer");
              if (buffer == nullptr) {
                  return JNI_ERR;
              }
              android::gCharArrayBufferClassInfo.data =
                  env->GetFieldID(buffer, "data", "[C");
              android::gCharArrayBufferClassInfo.sizeCopied =
                  env->GetFieldID(buffer, "sizeCopied", "I");
              android::gEmptyString = static_cast<jstring>(
                  env->NewGlobalRef(env->NewStringUTF("")));

              std::vector<JNINativeMethod> methods;
              for (const JNINativeMethod& method : android::sConnectionMethods) {
                  if (!android::leftOut(method.name)) {
                      methods.push_back(method);
                  }
              }
              for (const JNINativeMethod& method : android::sWindowMethods) {
                  methods.push_back(method);
              }
              methods.push_back(android::native("nativeLastInsertRowId",
                  reinterpret_cast<void*>(android::nativeLastInsertRowId)));
              methods.push_back(android::native("nativeChanges",
                  reinterpret_cast<void*>(android::nativeChanges)));
              methods.push_back(android::native("nativeTotalChanges",
                  reinterpret_cast<void*>(android::nativeTotalChanges)));

              jclass host = env->FindClass("org/pimalaya/sqlite/HostSqlite");
              if (host == nullptr
                      || env->RegisterNatives(host, methods.data(),
                             static_cast<jint>(methods.size())) < 0) {
                  return JNI_ERR;
              }
              return JNI_VERSION_1_6;
          }
        '';
        sqliteHost = pkgs.stdenv.mkDerivation {
          pname = "sqlite-platform-host";
          version = sqliteFloorVersion;
          src = sqliteJni;
          nativeBuildInputs = [ pkgs.gnutar ];
          buildInputs = [ pkgs.icu ];
          buildPhase = ''
            jni=sqlite-android/src/main/jni/sqlite
            tar -xzf ${sqliteFloor.src} --strip-components=1 -C $jni \
              sqlite-autoconf-3180200/sqlite3.c sqlite-autoconf-3180200/sqlite3.h
            # NOTE: the platform throws its own cancellation, requery
            # AndroidX's.
            substituteInPlace $jni/android_database_SQLiteCommon.cpp \
              --replace-fail androidx/core/os/OperationCanceledException \
                android/os/OperationCanceledException

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

            flags="${sqliteFloorFlags} -DPACKED= -I$jni -Istub \
              -I${pkgs.jdk17.home}/include -I${pkgs.jdk17.home}/include/linux"
            cxxflags="$flags -include stub/c_jnienv.h -Wno-conversion-null \
              -Wno-write-strings"

            $CC $flags -c $jni/sqlite3.c -o sqlite3.o
            for source in android_database_SQLiteCommon \
              android_database_SQLiteDebug android_database_SQLiteFunction \
              CursorWindow JNIHelp JNIString; do
              $CXX $cxxflags -c $jni/$source.cpp -o $source.o
            done
            $CXX $cxxflags -c -x c++ ${sqliteHostJni} -o sqlite-host-jni.o
            # NOTE: -Bsymbolic binds the natives to their own sqlite3_*,
            # never to another SQLite the test JVM may have loaded first
            # (Robolectric's runtime carries one).
            $CXX -shared -Wl,-Bsymbolic -Wl,--no-undefined \
              -o libsqlitehost.so *.o -licui18n -licuuc -ldl -lpthread -lm
          '';
          installPhase = ''
            install -Dm644 libsqlitehost.so $out/lib/libsqlitehost.so
          '';
          doInstallCheck = true;
          installCheckPhase = ''
            grep -qaF '${sqliteFloorVersion}' $out/lib/libsqlitehost.so
            ! grep -qa 'json_each' $out/lib/libsqlitehost.so
            ! grep -qa 'fts5' $out/lib/libsqlitehost.so
          '';
        };

      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            rust
            pkgs.cargo-deny
            pkgs.cargo-ndk
            pkgs.jdk21
            pkgs.gradle
            androidSdk
            pkgs.jdt-language-server
          ];

          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${ndkVersion}";
          ANDROID_NDK_HOME = "${sdkRoot}/ndk/${ndkVersion}";
          # NOTE: Robolectric runs SDK 36 on Java 21 only; the bytecode still
          # targets 17 (build.gradle.kts compileOptions).
          JAVA_HOME = pkgs.jdk21.home;
          PIMALAYA_SQLITE_HOST = "${sqliteHost}/lib";
          # NOTE: an env var rather than a build input, so 3.18's sqlite3.h
          # and libsqlite3.so never reach the compiler's search paths.
          PIMALAYA_SQLITE_FLOOR = "${sqliteFloor}";

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
