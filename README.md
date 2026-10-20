# android-design-audit (skill)

Design-system, performance and hygiene checks for Android / Jetpack Compose, for any developer using Claude.

Two tasks:
- **REVIEW** — while developing: `audit.sh review <project> --git` (or `--files …`) checks only the changed files and
  returns Errors/Warnings with the exact fix. Exit code 1 = errors → don't merge.
- **AUDIT** — existing code: `audit.sh audit <project>`; running screens: `capture.sh` + `audit.sh hierarchy`.

Requirements: Java 17+ (Android Studio's bundled JDK works), adb for device capture. No Python, no Gradle.

```
ide-plugin/                  # Android Studio plugin source (built by GitHub Actions → Releases)
skills/android-design-audit/
├── SKILL.md                 # how Claude runs the two tasks
├── bin/audit-core.jar       # the rule engine (same code as the Android Studio plugin)
├── scripts/audit.sh         # wrapper: review | audit | hierarchy | rules
├── scripts/capture.sh       # adb: layout bounds + screenshot + hierarchy
├── scripts/install-hook.sh  # git pre-commit gate
├── scripts/install-ide-plugin.sh  # one-command Android Studio plugin install
└── references/rules.md      # every rule: severity, check, fix, plus manual checklist rows
```

## Install

**Claude Code (from GitHub)** — the repo is its own marketplace:
```
/plugin marketplace add KareemShehab23/android-design-audit
/plugin install android-design-audit@android-design-audit
```
Then `/android-design-audit review` (changed files) or `/android-design-audit audit` (whole project).

**Claude desktop / web** — download the latest `android-design-audit.plugin` from Releases, open it in a chat and press *Install*.

**CI** — copy `.github/workflows/design-audit.yml` into your app repo; every PR gets a design review in the job summary and fails on errors.

**Git pre-commit hook (automatic while developing)** — `skills/android-design-audit/scripts/install-hook.sh /path/to/your/app`; every commit reviews the staged files, errors block the commit.

**Android Studio plugin (as you type)** — `skills/android-design-audit/scripts/install-ide-plugin.sh` installs the latest build from Releases into every Android Studio on the machine (restart afterwards). The skill offers this on first use. Source in `ide-plugin/`; CI builds it on every push.

**Terminal only** — `java -jar skills/android-design-audit/bin/audit-core.jar review . --git`
