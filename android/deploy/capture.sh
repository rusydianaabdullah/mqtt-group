#!/usr/bin/env bash
#
# Captures the Play Store screenshots from a running emulator.
#
#   ./deploy/capture.sh phone      1080x2160 @ 420dpi  (exactly 2:1, Play's tallest phone ratio)
#   ./deploy/capture.sh tablet-7   1200x1920 @ 240dpi
#   ./deploy/capture.sh tablet-10  1600x2560 @ 280dpi
#   ./deploy/capture.sh all
#
# UNLIKE adzan-time's version, this one CANNOT produce a usable set from a fresh install. That
# app draws everything it needs from a latitude and a longitude; this one has nothing to show
# until there is a broker, a group, other members and some messages — and none of that can be
# conjured locally, because a second member means a second phone.
#
# So the emulator has to be prepared by hand first, once:
#
#   1. ./build.sh install
#   2. Set a profile name and avatar.
#   3. Add a broker (test.mosquitto.org:8886, TLS on, is enough).
#   4. Create a group, and join it from a second device or emulator so the member list and the
#      map have more than one person on them. A group of one photographs badly. One emulator
#      cannot scan another's QR code; use the "paste an invite code" field instead, decoding the
#      code out of a screenshot of the QR screen if you have no other way to move the text.
#   5. Send a few messages both ways, including a photo, and let the map collect some positions
#      with location sharing on. `adb emu geo fix` will NOT reach the app — it asks for
#      LocationManager.FUSED_PROVIDER, which on a Google-APIs image is GMS's and ignores the
#      emulated GPS. Use the test-provider API instead; see deploy/README.md.
#
# After that this script drives the navigation and the resizing. It restores the display on exit.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"

PKG=id.larus.mqttgroup
ACTIVITY=$PKG/id.larus.mqttgroup.ui.MainActivity
OUT=deploy/screenshots

adb get-state >/dev/null || { echo "no device — start an emulator first" >&2; exit 1; }

# A believable, stable frame: fixed clock, full battery, no notification clutter.
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
freeze_status_bar() {
    adb shell settings put global sysui_demo_allowed 1 >/dev/null
    # Leave first: re-entering demo mode without exiting stacks a second copy of every icon onto
    # the status bar, and it is cumulative across runs.
    demo exit
    demo enter
    demo clock -e hhmm 1030
    demo battery -e level 100 -e plugged false
    demo network -e wifi show -e level 4 -e fully true -e mobile hide -e sims 1 -e nosim hide
    demo notifications -e visible false
}

shoot() { sleep 2; adb exec-out screencap -p > "$1"; }
tap()   { adb shell input tap "$1" "$2"; sleep 2; }
back()  { adb shell input keyevent 4; sleep 2; }

capture_set() {
    local dir="$OUT/$1" w=$2 h=$3 d=$4
    mkdir -p "$dir"
    adb shell wm size "${w}x${h}" >/dev/null
    adb shell wm density "$d" >/dev/null
    sleep 2
    freeze_status_bar
    adb shell am force-stop $PKG
    adb shell am start -n $ACTIVITY >/dev/null
    sleep 6

    # Coordinates as fractions of the display, so the same script works at all three sizes.
    local mid_x=$((w / 2))
    local first_group_y=$((h * 22 / 100))   # the first row of the groups list
    local chat_tab_x=$((w * 30 / 100))      # Chat/Map switcher, left half
    local map_tab_x=$((w * 70 / 100))       #                    right half
    local tabs_y=$((h * 15 / 100))
    local settings_x=$((w * 90 / 100))      # gear in the groups screen's app bar
    local settings_y=$((h * 7 / 100))

    shoot "$dir/01-groups.png"

    tap $mid_x $first_group_y
    shoot "$dir/02-chat.png"

    tap $map_tab_x $tabs_y
    sleep 4                                  # tiles have to arrive before this is worth a photo
    shoot "$dir/03-map.png"

    # History mode: the button column sits above the people strip on the right.
    tap $((w * 86 / 100)) $((h * 62 / 100))
    sleep 2
    shoot "$dir/04-history.png"

    tap $chat_tab_x $tabs_y
    back
    tap $settings_x $settings_y
    shoot "$dir/05-settings.png"

    # About sits below Map in Settings; the row is a fixed distance down a scrolling column, so
    # scroll to the bottom first rather than guessing at a y that shifts with the broker count.
    adb shell input swipe $mid_x $((h * 70 / 100)) $mid_x $((h * 20 / 100)) 400
    sleep 1
    adb shell input swipe $mid_x $((h * 70 / 100)) $mid_x $((h * 20 / 100)) 400
    sleep 2
    shoot "$dir/06-about.png"
    back

    echo "==> $dir  (${w}x${h} @ ${d}dpi)"
}

reset_display() { adb shell wm size reset >/dev/null; adb shell wm density reset >/dev/null; }
trap reset_display EXIT

case "${1:-all}" in
    phone)     capture_set phone     1080 2160 420 ;;
    tablet-7)  capture_set tablet-7  1200 1920 240 ;;
    tablet-10) capture_set tablet-10 1600 2560 280 ;;
    all)
        capture_set phone     1080 2160 420
        capture_set tablet-7  1200 1920 240
        capture_set tablet-10 1600 2560 280
        ;;
    *) echo "usage: $0 [phone|tablet-7|tablet-10|all]" >&2; exit 1 ;;
esac

cat <<'NOTE'

Check every shot before uploading. The taps above are fractions of the display, not view
lookups, so a layout change moves them — and a mistimed tap produces a screenshot of the right
screen mid-transition rather than an obvious failure.

Still to take by hand: the invite QR screen (it needs a real group, and photographing a QR code
that resolves to a working invite would publish that group's key — make a throwaway group for
it).
NOTE
