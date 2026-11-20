#!/usr/bin/env bash
#
# Build helper for family-group.
#
# Finds a JDK and the Android SDK the same way Android Studio does, so this works whether you
# run it from a terminal, from Studio's Terminal tab, or from CI. Usage:
#
#   ./build.sh                 debug APK (default)
#   ./build.sh release         release APK, signed when keystore.properties exists
#   ./build.sh bundle          release AAB for Google Play
#   ./build.sh test            unit tests only
#   ./build.sh install         debug APK, then adb install to the connected device
#   ./build.sh clean           delete build output
#   ./build.sh all             test + debug + release
#
# Anything after the command is passed straight to Gradle, e.g.
#   ./build.sh debug --offline
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; OFF=$'\033[0m'
info()  { printf '%s==>%s %s\n' "$GREEN" "$OFF" "$*"; }
warn()  { printf '%s==>%s %s\n' "$YELLOW" "$OFF" "$*" >&2; }
fail()  { printf '%serror:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

# The comment block at the top of this file is the usage text; keep the two in sync by
# printing it rather than repeating it.
usage() { awk 'NR>2 && /^#/ { sub(/^# ?/, ""); print; next } NR>2 { exit }' "${BASH_SOURCE[0]}"; }

case "${1:-}" in
    -h|--help|help) usage; exit 0 ;;
esac

# ---- JDK -----------------------------------------------------------------------------------
# Android Studio ships its own JetBrains Runtime; prefer whatever is already exported, then
# Studio's, then a system JDK. Gradle 8.14 with AGP 8.13 needs JDK 17 or newer.
find_java_home() {
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        printf '%s' "$JAVA_HOME"; return
    fi
    local candidates=(
        "$HOME/toolchain"/jdk-17*
        /opt/android-studio/jbr
        /usr/local/android-studio/jbr
        "$HOME/android-studio/jbr"
        "$HOME/.local/share/JetBrains/Toolbox/apps"/*/jbr
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home
        "${LOCALAPPDATA:-/nonexistent}/Programs/Android Studio/jbr"
        /usr/lib/jvm/java-2[1-9]-openjdk*
        /usr/lib/jvm/java-17-openjdk*
        /usr/lib/jvm/default-java
    )
    local candidate
    for candidate in "${candidates[@]}"; do
        [[ -x "$candidate/bin/java" ]] && { printf '%s' "$candidate"; return; }
    done
    # Last resort: whatever `java` resolves to on PATH.
    if command -v java >/dev/null 2>&1; then
        printf '%s' "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    fi
}

JAVA_HOME="$(find_java_home)"
[[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/java" ]] || fail \
    "no JDK found. Install JDK 17+, or point JAVA_HOME at Android Studio's bundled runtime."
export JAVA_HOME

java_major="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
[[ "$java_major" -ge 17 ]] 2>/dev/null || fail \
    "JDK 17 or newer required, found $java_major at $JAVA_HOME"

# ---- Android SDK ---------------------------------------------------------------------------
find_sdk_dir() {
    if [[ -f local.properties ]]; then
        local from_props
        from_props="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
        [[ -n "$from_props" && -d "$from_props" ]] && { printf '%s' "$from_props"; return; }
    fi
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
        "$HOME/android-sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
        "${LOCALAPPDATA:-}/Android/Sdk"; do
        [[ -n "$candidate" && -d "$candidate/platforms" ]] && { printf '%s' "$candidate"; return; }
    done
}

SDK_DIR="$(find_sdk_dir)"
[[ -n "$SDK_DIR" ]] || fail \
    "no Android SDK found. Open this folder in Android Studio once, or set ANDROID_HOME."
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

# Gradle reads sdk.dir from here; keep it in sync but never commit it (it is gitignored).
if ! grep -qs "^sdk.dir=${SDK_DIR}$" local.properties 2>/dev/null; then
    printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
    info "wrote local.properties → $SDK_DIR"
fi

printf '%sJDK %s  %s%s\n' "$DIM" "$java_major" "$JAVA_HOME" "$OFF"
printf '%sSDK    %s%s\n' "$DIM" "$SDK_DIR" "$OFF"

# ---- run -----------------------------------------------------------------------------------
COMMAND="${1:-debug}"
[[ $# -gt 0 ]] && shift

gradle() {
    info "gradlew $*"
    ./gradlew "$@"
}

# Reports where the APK landed and how big it is, so you are not hunting through build/.
report() {
    local apk
    for apk in "app/build/outputs/apk/$1"/*.apk; do
        [[ -f "$apk" ]] && info "APK  $apk  ($(du -h "$apk" | cut -f1))"
    done
}

case "$COMMAND" in
    debug)
        gradle assembleDebug "$@"
        report debug
        ;;
    release)
        gradle assembleRelease "$@"
        report release
        # Signed when keystore.properties exists; otherwise AGP emits *-unsigned.apk.
        if compgen -G "app/build/outputs/apk/release/*-unsigned.apk" > /dev/null; then
            warn "this APK is unsigned; create keystore.properties (see keystore.properties.example)"
        fi
        ;;
    bundle)
        # What Play actually takes. An APK is only for checking the signed build on a device
        # before uploading — Play has not accepted APKs for new apps since 2021.
        gradle bundleRelease "$@"
        if compgen -G "app/build/outputs/bundle/release/*.aab" > /dev/null; then
            for f in app/build/outputs/bundle/release/*.aab; do
                info "bundle  $f  ($(du -h "$f" | cut -f1))"
            done
        fi
        if [ ! -f keystore.properties ]; then
            warn "no keystore.properties — this bundle is unsigned and Play will reject it"
        fi
        ;;
    test)
        gradle testDebugUnitTest "$@"
        info "report  app/build/reports/tests/testDebugUnitTest/index.html"
        ;;
    install)
        gradle installDebug "$@"
        info "installed on the connected device"
        ;;
    clean)
        gradle clean "$@"
        ;;
    all)
        gradle testDebugUnitTest assembleDebug assembleRelease "$@"
        report debug
        report release
        ;;
    lint)
        gradle lintDebug "$@"
        info "report  app/build/reports/lint-results-debug.html"
        ;;
    *)
        fail "unknown command '$COMMAND' — try: debug, release, test, install, clean, lint, all"
        ;;
esac
