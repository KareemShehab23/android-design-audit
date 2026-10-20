#!/usr/bin/env bash
# install-ide-plugin.sh — install (or update) the Android Design Audit plugin into every Android Studio
# found on this machine. Downloads the latest build from GitHub Releases unless --zip is given.
#   install-ide-plugin.sh [--zip path/to/android-design-audit-ide.zip] [--version vX.Y.Z] [--list]
set -euo pipefail
REPO="KareemShehab23/android-design-audit"
ZIP=""; VERSION="latest"; LIST=0
while [[ $# -gt 0 ]]; do case "$1" in
  --zip) ZIP="$2"; shift 2;; --version) VERSION="$2"; shift 2;; --list) LIST=1; shift;;
  *) echo "unknown option $1" >&2; exit 2;; esac; done

# ---- 1. Find Android Studio config dirs (each holds a plugins/ folder) ---------------------
dirs=()
case "$(uname -s)" in
  Darwin) for d in "$HOME/Library/Application Support/Google"/AndroidStudio*; do [[ -d "$d" ]] && dirs+=("$d"); done ;;
  Linux)  for d in "$HOME/.local/share/Google"/AndroidStudio* "$HOME/.config/Google"/AndroidStudio*; do [[ -d "$d" ]] && dirs+=("$d"); done ;;
  MINGW*|MSYS*|CYGWIN*) for d in "${APPDATA:-}"/Google/AndroidStudio*; do [[ -d "$d" ]] && dirs+=("$d"); done ;;
esac
if [[ ${#dirs[@]} -eq 0 ]]; then
  echo "No Android Studio installation found (looked for Google/AndroidStudio* config folders). Open Android Studio once, then re-run." >&2; exit 3
fi
if [[ $LIST -eq 1 ]]; then
  for d in "${dirs[@]}"; do
    if [[ -d "$d/plugins/android-design-audit" ]]; then v=$(ls "$d/plugins/android-design-audit/lib" 2>/dev/null | grep -oE 'android-design-audit-[0-9.]+' | head -1); echo "$(basename "$d"): installed ${v:-?}"; else echo "$(basename "$d"): not installed"; fi
  done; exit 0
fi

# ---- 2. Get the plugin zip ----------------------------------------------------------------
TMP=$(mktemp -d)
if [[ -z "$ZIP" ]]; then
  if [[ "$VERSION" == "latest" ]]; then API="https://api.github.com/repos/$REPO/releases/latest"; else API="https://api.github.com/repos/$REPO/releases/tags/$VERSION"; fi
  URL=$(curl -fsSL "$API" | grep -oE '"browser_download_url": *"[^"]*android-design-audit-ide\.zip"' | head -1 | sed -E 's/.*"(https[^"]*)"/\1/')
  if [[ -z "$URL" ]]; then
    # fall back to the rolling pre-release built from main
    URL=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/tags/latest" | grep -oE '"browser_download_url": *"[^"]*android-design-audit-ide\.zip"' | head -1 | sed -E 's/.*"(https[^"]*)"/\1/')
  fi
  [[ -z "$URL" ]] && { echo "Could not find android-design-audit-ide.zip in GitHub Releases of $REPO." >&2; exit 4; }
  echo "Downloading $URL"
  ZIP="$TMP/plugin.zip"; curl -fsSL -o "$ZIP" "$URL"
fi

# ---- 3. Install into each Android Studio ---------------------------------------------------
for d in "${dirs[@]}"; do
  mkdir -p "$d/plugins"
  rm -rf "$d/plugins/android-design-audit"
  unzip -qo "$ZIP" -d "$d/plugins"
  echo "Installed into $(basename "$d")  →  $d/plugins/android-design-audit"
done
echo
echo "Done. Restart Android Studio to activate the plugin (violations will be underlined as you type; tool window: Design Audit)."
