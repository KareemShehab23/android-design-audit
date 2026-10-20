# Rule catalogue — Android / Jetpack Compose design system

Two severities only. **Error** = ship-blocking (accessibility, platform behaviour, crashes, broken fundamentals).
**Warning** = must be fixed or explicitly justified in review. Every rule names the API that fixes it.

`[code]` = found by the static scanner · `[device]` = measured from a running screen · `[manual]` = judged by reading code/screenshots (the scanner can't see it — review it yourself when the skill runs).

## 1. Spacing & layout
| Rule | Sev | Check | Fix |
|---|---|---|---|
| `spacing-literal` [code] | W | `padding/spacedBy/Spacer/size` with a dp literal while the project has spacing tokens | Use `Spacing.md` etc. from the tokens object |
| `no-spacing-tokens` [code] | W | No tokens object (≥3 `val x = N.dp` in an object named Spacing/Dimens/…) | Create `ui/theme/Dimens.kt` with xs 4 · sm 8 · md 16 · lg 24 · xl 32 |
| `dp-off-grid` [code] | W | Any dp value not a multiple of 4 (borders/strokes/elevation exempt) | 4 · 8 · 12 · 16 · 24 · 32 |
| `spacing-off-grid` [device] | W | Measured gap between siblings not on the 4dp grid | `Arrangement.spacedBy(8.dp)` |
| `edge-margin` [device] | W | Outermost content not at the screen margin (16dp; 24dp large screens; 2× for card content) — per element + screen-level summary | One `Modifier.padding(horizontal = 16.dp)` at the screen root |
| `edge-margin-inconsistent` [device] | W | ≥3 different margin values on one side | One root padding, no per-element side padding |
| `misaligned` [device] | W | Stacked siblings whose start edges differ by 1–8dp | Let the parent carry the padding |
| `container-padding` [device] | W | Card/box inner padding < 8dp or off-grid | `Modifier.padding(16.dp)` on the card content |
| `container-padding-inconsistent` [device] | W | Cards of the same width with different inner padding | One card padding token |
| `overlap` [device] | W | Two sibling elements partially colliding (containment is nesting, not overlap) | Column/Row instead of Box; maxLines/overflow |
| `overflow-horizontal` [device] | W | Element extends past the screen | `fillMaxWidth`, `weight(1f)`, wrap/ellipsize |
| `fixed-width` [code] | W | `.width(≥300.dp)` | `fillMaxWidth` / `widthIn(max=)` |
| Vertical rhythm [manual] | — | Section title → content 8dp; section → section 24dp; related items 8, groups 16–24 | |

## 2. Touch targets
| `touch-target-size` [device] / `touch-target-small` [code] | **E** | Interactive element < 48×48dp (icon may stay 24dp) | `IconButton`, `Modifier.minimumInteractiveComponentSize()` |
| `touch-target-gap` [device] | W | < 8dp between small tappable elements | `spacedBy(8.dp)` |

## 3. Native Material 3 components
| `bottom-nav-count` [code/device] | W | NavigationBar with < 3 or > 5 destinations | 3–5; "More" / drawer / tabs |
| `material2-import` [code] | W | `androidx.compose.material` mixed with material3 | Migrate to M3 components |
| `toast-usage` [code] | W | `Toast.makeText` | `SnackbarHostState.showSnackbar` |
| `card-clickable` [code] | W | `Card(Modifier.clickable)` | `Card(onClick = …)` |
| `custom-dialog` [code] | W | raw `Dialog(onDismissRequest…)` | `AlertDialog` / `BasicAlertDialog` + `Surface` |
| `button-height` [code] | W | `.height()` on a Button | Remove; buttons are 40dp, FAB 56dp |
| `icon-size` [code] | W | Icon size not in 16/18/20/24/32/36/40/48/56/64 | 24dp default |
| `deprecated-api` [code] | W | `Divider`, `rememberRipple`, `window.statusBarColor`, Accompanist, `SwipeToDismiss`, `ModalBottomSheetLayout`, `onBackPressed`… | Named replacement in the finding |
| `empty-click` [code] | W | `onClick = {}` / `.clickable {}` | Wire the action or remove the clickable |
| `todo-left` [code] | W | TODO/FIXME in UI code | Resolve or ticket |
| Component choice [manual] | — | TopAppBar variants, FAB bottom-end, ModalBottomSheet for secondary actions, Chips/ListItem/SegmentedButton, no iOS patterns (back button inside content, iOS tab bar, action sheet) | |

## 4. Navigation & back
| `predictive-back` [code] | W | manifest lacks `enableOnBackInvokedCallback="true"` | Add it; use `BackHandler`, never override `onBackPressed` |
| Up vs Back [manual] | — | Up arrow only when there is a parent; mirrors in RTL; no gesture conflicts with system edges; ≤3 taps to any screen | |

## 5. Typography
| `font-size-dp` [code] | **E** | `fontSize = N.dp` | `.sp` |
| `font-size-small` [code] | W | < 12sp (labelSmall 11sp exempt) | ≥12sp; body ≥14sp |
| `font-size-hardcoded` [code] | W | `fontSize = N.sp` outside Type.kt | `MaterialTheme.typography.*` |
| `text-no-style` [code] | W | `Text()` without `style =` (Material slots exempt) | `style = MaterialTheme.typography.bodyMedium` |
| `hardcoded-textstyle` [code] | W | `TextStyle(…)` inline | Add to Typography / `.copy()` |
| `font-family-inline` [code] | W | `fontFamily =` on a Text | Define once in Type.kt |
| `font-family-missing` [code] | W | No FontFamily anywhere | `res/font` + `FontFamily` in Typography (Arabic-capable: Noto Sans Arabic, IBM Plex Arabic, Tajawal) |
| `typography-scale` [code] | W | Typography not defined or never used | Type.kt + `MaterialTheme.typography.*` |
| `text-overflow` [code] | W | `maxLines` without `overflow` | `TextOverflow.Ellipsis` |
| `string-via-context` [code] | W | `context.getString` in a composable | `stringResource()` |
| Readability [manual] | — | 40–60 chars per line, 200% font scale survives, Arabic line-height slightly larger, no all-caps Arabic | |

## 6. Color & theme
| `hardcoded-color` [code] | W | `Color(0x…)` or `Color.White/Black/Gray…` in a composable | `MaterialTheme.colorScheme.*` roles |
| `dark-theme` [code] | W | No `darkColorScheme()` or `isSystemInDarkTheme()` never read | Theme.kt switch |
| `dynamic-color` [code] | W | No dynamic color on API 31+ | `dynamicDark/LightColorScheme(context)` |
| `hardcoded-elevation` [code] | W | Elevation not in 0/1/3/6/8/12 | `CardDefaults.cardElevation()` or a tonal level |
| `hardcoded-shape` / `no-shape-tokens` [code] | W | `RoundedCornerShape(N.dp)` inline / no `Shapes()` | `MaterialTheme.shapes.*` |
| Contrast [manual] | — | ≥4.5:1 body text, ≥3:1 large text & icons; color never the only carrier of meaning; system bar icons readable in both themes | |

## 7. System UI
| `edge-to-edge` [code] | W | No `enableEdgeToEdge()` | Call before `setContent`; Scaffold innerPadding / `safeDrawingPadding` |
| `window-insets` [code] | W | Edge-to-edge without inset handling | `WindowInsets.safeDrawing`, `imePadding` |
| `splash-screen` [code] | W | SplashScreen API unused | `installSplashScreen()` + `Theme.SplashScreen` |
| `adaptive-icon` [code] | W | No `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon with monochrome layer |

## 8. RTL & localization (Arabic)
| `supports-rtl` [code] | **E** | manifest lacks `android:supportsRtl="true"` | Add it |
| `rtl-unsafe` [code] | W | `absolutePadding`, `TextAlign.Left/Right`, `Alignment.*Left/Right`, `Arrangement.Absolute`, `LayoutDirection.Ltr` | start/end variants |
| `rtl-icon` [code] | W | Directional icon without AutoMirrored | `Icons.AutoMirrored.Filled.*` |
| `hardcoded-string` [code] | W | String literal in `Text`/`contentDescription`/labels | `stringResource(R.string.x)` |
| `localization` [code] | W | Only one `values*/strings.xml` | Add `values-ar` / `values-en` |
| RTL screenshots [manual] | — | Capture once in Arabic, once in English; numbers/dates via locale formatters | |

## 9. Adaptive layouts
| `adaptive-layout` [code] | W | No window-size-class handling | `currentWindowAdaptiveInfo()`; NavigationRail on medium, two-pane on expanded |

## 10. States
| `loading-state` [code] | W | No progress indicator / pull-to-refresh anywhere | Loading state per data screen |
| `empty-error-state` [code] | W | No retry / empty-state wording | Empty state with action; error state with Retry |
| Interactive states [manual] | — | pressed (ripple), disabled, focused, selected; offline handling | |

## 11. Accessibility
| `a11y-unlabeled` [device] / `a11y-icon-unlabeled` [code] | **E** | Tappable element with no text/contentDescription | Meaningful `contentDescription`, or `null` + move the click to a labelled parent |
| `a11y-duplicate-label` [device] | W | Icon inside a labelled card repeats the same label | Icon `contentDescription = null` |
| Focus order & TalkBack [manual] | — | Logical order, 200% font scale, "Remove animations" | |

## 12. Performance (recomposition & frames)
| `perf-state-no-remember` [code] | **E** | `mutableStateOf`/`derivedStateOf` without `remember` | `remember { }` / `rememberSaveable` |
| `perf-blocking-ui` [code] | **E** | `runBlocking`, `Thread.sleep`, blocking I/O in a composable | ViewModel coroutine on `Dispatchers.IO` |
| `perf-lazy-in-scroll` [code] | **E** | LazyColumn inside a verticalScroll Column | Outer LazyColumn with `item { }` blocks |
| `perf-lazy-no-key` [code] | W | `items(list)` without `key =` | `items(list, key = { it.id })` |
| `perf-column-foreach` [code] | W | `Column(verticalScroll)` + `forEach` | `LazyColumn` |
| `perf-state-in-modifier` [code] | W | State/animation read in `offset/alpha/scale/rotate/graphicsLayer/background(…)` | Lambda versions: `offset { }`, `graphicsLayer { }`, `drawBehind { }` |
| `perf-alloc-in-composition` [code] | W | `SimpleDateFormat`, `Regex`, `Gson`, `Paint`… created in composition | `remember { }` / hoist |
| `perf-compute-in-composition` [code] | W | `sortedBy/filter/groupBy` in composition | ViewModel or `remember(input)` / `derivedStateOf` |
| `perf-bitmap-decode` [code] | W | `BitmapFactory.decode*` in composition | Coil/Glide `AsyncImage` |
| `perf-collect-lifecycle` [code] | W | `collectAsState()` | `collectAsStateWithLifecycle()` |
| `perf-effect-unstable-key` [code] | W | `LaunchedEffect` keyed on a lambda / new object | Stable key; `rememberUpdatedState` |
| `perf-global-scope` [code] | W | `GlobalScope` | `rememberCoroutineScope` / `viewModelScope` |
| `perf-unstable-param` [code] | W | `List/Set/Map` parameter on a composable | `ImmutableList` / `@Immutable` wrapper; strong skipping |
| `perf-modifier-composed` [code] | W | `Modifier.composed` | `Modifier.Node` |
| `perf-jank` [device] | **E** ≥25% / W ≥10% | Janky frame % from `dumpsys gfxinfo` | Layout Inspector recomposition counts, System Trace |
| `perf-frame-time` [device] | W | p95 > 33ms or p90 > 20ms; slow bitmap uploads | Profile the screen |
| `perf-slow-ui-thread` / `perf-missed-vsync` [device] | W | Composition/measure/layout too slow | Reduce work in composition |
| `perf-memory` [device] | W | > 400MB PSS | Bitmaps, leaks, large lists |

## 13. Code & resource hygiene
| `unused-import` [code] | W | Import whose symbol never appears in the file | Optimize imports |
| `unused-code` [code] | W | Private member never used in its file; top-level fun/class/object never referenced in the project (framework annotations & overrides exempt) | Delete or wire in; `@Keep` for reflection entry points |
| `unused-resource` [code] | W | Drawable / string never referenced (`R.x.name`, `@x/name`) | Remove Unused Resources |
| `duplicate-string` [code] | W | Same text under different keys in one locale | One key named by meaning |
| `duplicate-image` [code] | W | Byte-identical images (or identical vectors) under different names | Keep one |
| `large-image` [code] | W | PNG/JPG > 300KB | WebP at display size |
| `large-vector` [code] | W | Vector > 200dp or > 50KB | WebP for illustrations |

## Scope
Test sources are never audited: `src/test`, `src/androidTest`, `testFixtures`, `screenshotTest`, `*Test.kt`, `*Tests.kt`, `*Spec.kt`. `@Preview` functions and `*Preview*.kt` files are exempt from string/style rules. Token files (Theme.kt, Color.kt, Type.kt, Dimens.kt, Shapes.kt, any Spacing/Dimens object) may contain literals.
