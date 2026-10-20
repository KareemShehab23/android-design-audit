#!/usr/bin/env bash
# install-hook.sh — add a git pre-commit hook that reviews staged Kotlin/resource files with audit-core.
# Usage: install-hook.sh <android-project-root>      (run again to update; --uninstall to remove)
set -euo pipefail
PROJECT="${1:?usage: install-hook.sh <project-root> [--uninstall]}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK_DIR="$(git -C "$PROJECT" rev-parse --git-path hooks)"
HOOK="$HOOK_DIR/pre-commit"
if [[ "${2:-}" == "--uninstall" ]]; then
  [[ -f "$HOOK" ]] && grep -q "android-design-audit" "$HOOK" && rm -f "$HOOK" && echo "Removed $HOOK"; exit 0
fi
mkdir -p "$HOOK_DIR"
cat > "$HOOK" <<HOOKEOF
#!/usr/bin/env bash
# android-design-audit pre-commit hook — blocks commits that add design-system ERRORS.
# Bypass once with:  git commit --no-verify
set -u
AUDIT="$HERE/audit.sh"
FILES=\$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.kt$|/res/|AndroidManifest\.xml' || true)
[[ -z "\$FILES" ]] && exit 0
ROOT=\$(git rev-parse --show-toplevel)
OUT=\$("\$AUDIT" review "\$ROOT" --files \$FILES 2>&1); CODE=\$?
echo "\$OUT" | head -60
if [[ \$CODE -eq 1 ]]; then
  echo; echo "✖ Design audit: errors above must be fixed before committing (git commit --no-verify to bypass)."; exit 1
elif [[ \$CODE -gt 1 ]]; then
  echo "⚠ Design audit could not run (\$CODE) — commit allowed."; exit 0
fi
exit 0
HOOKEOF
chmod +x "$HOOK"
echo "Installed $HOOK — every commit now reviews staged .kt/res files; errors block the commit, warnings are printed."
