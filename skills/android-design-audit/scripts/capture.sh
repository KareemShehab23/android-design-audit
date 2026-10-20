#!/usr/bin/env bash
# capture.sh — Capture a screen from a connected Android device/emulator for design auditing.
#
# What it produces (in OUT_DIR, default ./audit-captures/<name>/):
#   screen_bounds.png   screenshot with "Show layout bounds" ON  (red outlines = view bounds,
#                       blue = margins/padding, pink = clip bounds) — for visual review
#   screen_clean.png    the same screen with layout bounds OFF — for visual review
#   hierarchy.xml       uiautomator view hierarchy with pixel bounds of every node
#   device.json         density (dpi), screen size, SDK level, locale, dark mode, font scale
#
# Usage:
#   capture.sh --name home [--out DIR] [--device SERIAL] [--keep-bounds] [--no-clean]
#
# Requirements: adb on PATH, one device/emulator connected (USB debugging enabled).
# "Show layout bounds" is toggled via the debug.layout system property — the same switch the
# Developer options toggle flips — so the device does not need the toggle enabled beforehand.

set -euo pipefail

NAME="screen"
OUT_DIR=""
SERIAL=""
KEEP_BOUNDS=0
NO_CLEAN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)        NAME="$2"; shift 2 ;;
    --out)         OUT_DIR="$2"; shift 2 ;;
    --device|-s)   SERIAL="$2"; shift 2 ;;
    --keep-bounds) KEEP_BOUNDS=1; shift ;;
    --no-clean)    NO_CLEAN=1; shift ;;
    -h|--help)     sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH. Install Android platform-tools or add <sdk>/platform-tools to PATH." >&2
  exit 1
fi

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

# Pick a device
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$DEVICES" ]]; then
  echo "ERROR: no device/emulator in 'device' state. Run 'adb devices' and check USB debugging / authorization." >&2
  exit 1
fi
if [[ -z "$SERIAL" && $(echo "$DEVICES" | wc -l) -gt 1 ]]; then
  echo "ERROR: multiple devices connected; pass --device <serial>:" >&2
  echo "$DEVICES" >&2
  exit 1
fi

[[ -z "$OUT_DIR" ]] && OUT_DIR="./audit-captures/$NAME"
mkdir -p "$OUT_DIR"

sh() { "${ADB[@]}" shell "$@"; }

# ---- Device facts ---------------------------------------------------------------------------
DENSITY=$(sh wm density | grep -oE '[0-9]+' | tail -1)
SIZE=$(sh wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
SDK=$(sh getprop ro.build.version.sdk | tr -d '\r')
RELEASE=$(sh getprop ro.build.version.release | tr -d '\r')
LOCALE=$(sh getprop persist.sys.locale | tr -d '\r')
[[ -z "$LOCALE" ]] && LOCALE=$(sh getprop ro.product.locale | tr -d '\r')
FONT_SCALE=$(sh settings get system font_scale | tr -d '\r')
NIGHT=$(sh cmd uimode night 2>/dev/null | tr -d '\r' || true)
PKG=$(sh dumpsys window 2>/dev/null | grep -oE 'mCurrentFocus=Window\{[^}]*\}' | head -1 | sed -E 's/.* ([^ /]+)\/[^ ]*\}/\1/' || true)
ACTIVITY=$(sh dumpsys window 2>/dev/null | grep -oE 'mCurrentFocus=Window\{[^}]*\}' | head -1 | sed -E 's/.* ([^ ]+)\}/\1/' || true)

echo "${SIZE}px · ${DENSITY}dpi · Android ${RELEASE} (API ${SDK}) · ${LOCALE} · ${NIGHT} · font ${FONT_SCALE}" > "$OUT_DIR/device.txt"
cat > "$OUT_DIR/device.json" <<EOF
{
  "name": "$NAME",
  "density_dpi": ${DENSITY:-0},
  "screen_px": "${SIZE:-unknown}",
  "sdk": ${SDK:-0},
  "android_version": "${RELEASE:-unknown}",
  "locale": "${LOCALE:-unknown}",
  "font_scale": "${FONT_SCALE:-null}",
  "night_mode": "${NIGHT:-unknown}",
  "package": "${PKG:-unknown}",
  "activity": "${ACTIVITY:-unknown}",
  "captured_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

# ---- Layout bounds ON + screenshot ---------------------------------------------------------
apply_layout_flag() {
  sh setprop debug.layout "$1" >/dev/null
  # SYSPROPS_TRANSACTION: tells every running process to re-read debug.* properties.
  sh service call activity 1599295570 >/dev/null 2>&1 || true
  sleep 0.8
}

echo "→ Enabling 'Show layout bounds' and capturing $NAME ..."
apply_layout_flag true
"${ADB[@]}" exec-out screencap -p > "$OUT_DIR/screen_bounds.png"

# ---- Hierarchy dump (pixel bounds of every accessibility node) -----------------------------
echo "→ Dumping view hierarchy ..."
sh uiautomator dump /sdcard/_audit_hierarchy.xml >/dev/null 2>&1 || {
  echo "WARN: uiautomator dump failed (maybe a dialog/animation was running). Retrying once..." >&2
  sleep 1
  sh uiautomator dump /sdcard/_audit_hierarchy.xml >/dev/null
}
"${ADB[@]}" pull /sdcard/_audit_hierarchy.xml "$OUT_DIR/hierarchy.xml" >/dev/null
sh rm -f /sdcard/_audit_hierarchy.xml >/dev/null 2>&1 || true

# ---- Layout bounds OFF + clean screenshot --------------------------------------------------
if [[ $KEEP_BOUNDS -eq 0 ]]; then
  apply_layout_flag false
fi
if [[ $NO_CLEAN -eq 0 ]]; then
  if [[ $KEEP_BOUNDS -eq 1 ]]; then
    # temporarily switch off just for the clean shot, then restore
    apply_layout_flag false
    "${ADB[@]}" exec-out screencap -p > "$OUT_DIR/screen_clean.png"
    apply_layout_flag true
  else
    "${ADB[@]}" exec-out screencap -p > "$OUT_DIR/screen_clean.png"
  fi
fi

echo "✓ Captured to $OUT_DIR"
echo "  density=${DENSITY}dpi size=${SIZE} sdk=${SDK} locale=${LOCALE} night=${NIGHT} font_scale=${FONT_SCALE}"
echo "  package=${PKG} activity=${ACTIVITY}"
echo
echo "Next: $(dirname "$0")/audit.sh hierarchy \"$OUT_DIR\" --density ${DENSITY} --size ${SIZE} --name ${NAME}"
