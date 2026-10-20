#!/usr/bin/env bash
# audit.sh — thin wrapper around audit-core.jar (needs Java 17+; Android Studio's JBR works).
# Usage:  audit.sh audit <project> [opts]   |   audit.sh review <project> --git|--files …   |   audit.sh hierarchy <dir> …   |   audit.sh rules
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$HERE/../bin/audit-core.jar"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [[ -z "$JAVA_BIN" || ! -x "$JAVA_BIN" ]]; then
  if command -v java >/dev/null 2>&1; then JAVA_BIN=java
  elif [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]]; then JAVA_BIN="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
  elif [[ -x "$HOME/Library/Application Support/JetBrains/Toolbox/apps/android-studio/jbr/Contents/Home/bin/java" ]]; then JAVA_BIN="$HOME/Library/Application Support/JetBrains/Toolbox/apps/android-studio/jbr/Contents/Home/bin/java"
  elif [[ -x "/opt/android-studio/jbr/bin/java" ]]; then JAVA_BIN=/opt/android-studio/jbr/bin/java
  else echo "ERROR: java not found. Install JDK 17+ or set JAVA_HOME (Android Studio's jbr folder works)." >&2; exit 2; fi
fi
exec "$JAVA_BIN" -jar "$JAR" "$@"
