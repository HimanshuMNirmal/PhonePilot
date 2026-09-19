#!/usr/bin/env bash

set -e

APP_ID="com.phonepilot"
ACTIVITY="$APP_ID/.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"

clear

show_header() {
    clear
    echo "╔══════════════════════════════════════════╗"
    echo "║          PhonePilot Dev Server           ║"
    echo "╠══════════════════════════════════════════╣"
    echo "║                                          ║"
    echo "║  [r] Reload       Build + Install + Run  ║"
    echo "║  [l] Logcat       Live Android logs      ║"
    echo "║  [b] Build        Build APK only         ║"
    echo "║  [i] Install      Install + Launch       ║"
    echo "║  [c] Clear        Clear app data         ║"
    echo "║  [d] Device       Check connected phone  ║"
    echo "║  [q] Quit         Exit dev server        ║"
    echo "║                                          ║"
    echo "╚══════════════════════════════════════════╝"
    echo ""
}

check_device() {
    if ! adb get-state >/dev/null 2>&1; then
        echo "❌ No Android device connected."
        echo ""
        echo "Run:"
        echo "  adb devices"
        echo ""
        read -rp "Press Enter to continue..."
        return 1
    fi

    echo "✓ Android device connected"
}

build() {
    echo ""
    echo "→ Building PhonePilot..."
    echo ""

    ./gradlew assembleDebug

    echo ""
    echo "✓ Build successful"
}

install_and_launch() {
    check_device || return

    echo ""
    echo "→ Installing APK..."
    adb install -r "$APK"

    echo ""
    echo "→ Restarting PhonePilot..."
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$ACTIVITY"

    echo ""
    echo "✓ PhonePilot is running"
}

reload() {
    build
    install_and_launch

    echo ""
    read -rp "Press Enter to return to menu..."
}

build_only() {
    build

    echo ""
    read -rp "Press Enter to return to menu..."
}

install_only() {
    install_and_launch

    echo ""
    read -rp "Press Enter to return to menu..."
}

show_logs() {
    check_device || return

    echo ""
    echo "╔══════════════════════════════════════════╗"
    echo "║              PhonePilot Logs             ║"
    echo "║                                          ║"
    echo "║  Press Ctrl+C to return to Dev Server    ║"
    echo "╚══════════════════════════════════════════╝"
    echo ""

    adb logcat -c
    adb logcat --pid="$(adb shell pidof "$APP_ID" | tr -d '\r')" 2>/dev/null || adb logcat

    echo ""
    read -rp "Press Enter to return to menu..."
}

clear_data() {
    check_device || return

    echo ""
    echo "→ Clearing PhonePilot app data..."
    adb shell pm clear "$APP_ID"

    echo ""
    echo "✓ App data cleared"
    read -rp "Press Enter to return to menu..."
}

device_info() {
    echo ""
    echo "→ Device:"
    adb devices -l

    echo ""
    echo "→ Android version:"
    adb shell getprop ro.build.version.release

    echo ""
    echo "→ Device model:"
    adb shell getprop ro.product.model

    echo ""
    read -rp "Press Enter to return to menu..."
}

while true; do
    show_header

    printf "phonepilot > "
    read -r command

    case "$command" in
        r|R)
            reload
            ;;

        l|L)
            show_logs
            ;;

        b|B)
            build_only
            ;;

        i|I)
            install_only
            ;;

        c|C)
            clear_data
            ;;

        d|D)
            device_info
            ;;

        q|Q)
            echo ""
            echo "Stopping PhonePilot Dev Server."
            exit 0
            ;;

        "")
            ;;

        *)
            echo ""
            echo "Unknown command: $command"
            sleep 1
            ;;
    esac
done