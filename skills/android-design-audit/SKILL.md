---
name: android-design-audit
description: Enforces an Android / Jetpack Compose design system and performance rules, with two tasks — REVIEW code the developer is writing right now (changed files, before commit/PR) and AUDIT an existing project or a running screen. Reports only Errors and Warnings, each with the exact Compose API to fix it. Covers Material 3 spacing tokens and 8dp grid, 48dp touch targets, native components, typography scale and fonts, colors/theme/dark mode, edge-to-edge, RTL/Arabic, adaptive layouts, states, accessibility, recomposition/frame performance (dumpsys gfxinfo), plus hygiene (unused imports/code/resources, duplicate strings/images, large assets). Use whenever an Android developer writes or changes Compose UI, asks to review/check/audit/lint a screen, composable, PR or project, mentions Material guidelines, design system, spacing, padding, fonts, RTL, recomposition, jank, unused code or duplicate resources — even if they don't say "audit". Test sources are always excluded.
---

# Android design audit

One rule engine (`bin/audit-core.jar`, Java 17+), three ways to run it. Test sources (`src/test`, `src/androidTest`, `*Test.kt`…) are never audited. Only two severities exist: **Error** (ship-blocking) and **Warning** (fix or justify). Every finding carries *why it matters* and *how to fix*.

```
S=${CLAUDE_PLUGIN_ROOT}/skills/android-design-audit/scripts
```

## Task 1 — REVIEW: while the developer is developing

Trigger: the developer just wrote/changed Compose code, asks "is this ok?", "check my screen", "review before I commit", or the conversation contains a diff / new composable.

1. Find what changed. Prefer git (`--git` picks up unstaged, staged and untracked files; add `--base origin/main` for a PR); otherwise list the files explicitly:
   ```bash
   $S/audit.sh review <project-root> --git --format json
   $S/audit.sh review <project-root> --files app/src/main/java/…/HomeScreen.kt --format json
   ```
   The scanner still reads the whole project (for cross-file rules like unused code and tokens) but reports only on the changed files. Add `--project-rules` to include project-level findings (manifest, theme, resources) when the change touches them.
2. If the developer pasted code instead of pointing at files, write it to a scratch file inside a copy of the project layout (keep the package path) and review that file; say that project-wide rules were skipped.
3. Read `references/rules.md` and do the **manual** rows for the changed code yourself: component choice, vertical rhythm, up/back behaviour, readability, contrast, interactive states, focus order. The scanner cannot judge these; you can.
4. Reply in this shape — short, so it fits a dev loop:
   ```
   ✅/❌ <file> — N errors, M warnings
   1. [ERROR] rule — line: problem
      Fix: <exact code change, show the corrected snippet>
   …
   Manual review: <2–4 bullets from the checklist, only if something is off>
   ```
   Exit code 1 from the CLI means errors exist; say plainly that it should not be merged until they're fixed. If everything is clean, say so in one line — don't invent findings.
5. When the developer asks you to fix, apply the fixes directly in the files (the fix text names the API), then re-run the review on the same files to confirm 0 errors.

## Task 2 — AUDIT: existing code and running screens

Trigger: "audit the app", "check the whole project", "review screen X on the device", new team member inheriting a codebase, release readiness.

**Code (no device needed)**
```bash
$S/audit.sh audit <project-root> --format json --report audit-report.md
```
Options for the team's design system: `--margin 16 --touch 48 --grid 4 --inner-padding 8`. Group the result by area (the JSON has `area`), lead with errors, then the rules with the most occurrences; point at `audit-report.md` for the complete list and checklist.

**Running screen (device/emulator + adb)**
```bash
bash $S/capture.sh --name home                       # layout bounds on → screenshot + hierarchy + clean shot, restores the setting
$S/audit.sh hierarchy audit-captures/home --name home  # dp measurements: margins, touch targets, inner padding, alignment…
```
Open `audit-captures/home/annotated.png` and `screen_bounds.png` with Read and look: nested paddings stacking up (blue bands), tiny red boxes around tappable icons, edges that almost line up. Repeat per screen; capture one screen in Arabic too for RTL. For frame performance, run `adb shell dumpsys gfxinfo <package> reset`, use the screen for ~10 s, then `adb shell dumpsys gfxinfo <package>` and read janky %, p90/p95/p99 (budget 16.7 ms); report ≥10% janky as a warning, ≥25% as an error.

**Report** (audit mode): score line → top fixes ranked by impact (errors first, then most frequent) with file:line and the API → by area → what is done well → artifacts. Write in the user's language, keep rule ids and code in Latin script.

## Judgement calls

- A finding you can see is wrong (a 1dp divider flagged off-grid, a decorative icon inside a labelled card) — drop it and say why in one line. Precision beats volume.
- Heuristic rules (`unused-code`, `unused-resource`, `text-no-style`) deserve a second look before the developer deletes anything: reflection/DI entry points, resources used from XML layouts or code generated at build time.
- Don't promote style preferences into findings. If it isn't in `references/rules.md`, it's a suggestion, labelled as such.
- Java missing → the wrapper says so; Android Studio's bundled JDK (`…/jbr/Contents/Home`) works via `JAVA_HOME`.

## Files

- `bin/audit-core.jar` — the rule engine (same code as the Android Studio plugin)
- `scripts/audit.sh` — wrapper that finds Java; `scripts/capture.sh` — adb capture
- `references/rules.md` — every rule with severity, check, fix, and the manual checklist rows
- `$S/audit.sh rules` — prints the rule ids by area
