# Sleep Saver

Android app (Kotlin, Compose) that turns Garmin sleep data from Health Connect into sleep debt and a daily
circadian energy schedule, and (v2) wakes you with the Surfacing alarm, guides Night steps and plans the week.
Spec, formulas and constants: `docs/PLAN.md` (§3.11–3.16 and §5 for v2). Research and the v2 feature decision:
`docs/COMPETITIVE_ANALYSIS.md`. Bottom bar: Sleep, Energy, Tonight, Progress, Learn.

## Build
- `./gradlew assembleDebug`, `./gradlew :core:domain:test`, `./gradlew testDebugUnitTest`
- SDK lives at `C:/Users/carco/AppData/Local/Android/Sdk` (`local.properties` is gitignored).
- AGP 9 with built-in Kotlin: never apply `org.jetbrains.kotlin.android`; Kotlin compiler options go through
  `tasks.withType<KotlinJvmCompile>`. Shared module config lives in `build-logic/convention`.
- Room is at version 2 (`AutoMigration(1 → 2)` + `Migration1To2`; schemas in `core/data/schemas`, exercised by
  `MigrationTest`). A schema change needs version 3 and a migration; never a destructive fallback.
- Run module test tasks one module at a time on this machine (little free RAM); a full `testDebugUnitTest` forks many
  1.5 GB test JVMs at once.

## Module rules
- `core:domain` is pure Kotlin/JVM (no Android imports). Every formula lives there; every tunable number is a field of
  `SleepConfig`. Domain tests use JUnit 5 + Truth and `testing/Fx` builders.
- `core:data`: Room, DataStore, Health Connect, sync, repositories (interfaces in `repository/Repositories.kt`).
- `core:ui`: theme (`SleepSaverTheme`, `SleepSaverTheme.colors`), shared components, time/duration formatting.
- `core:alarm`: exact alarms, the ring and night foreground services, tone/noise synthesis, the device-protected mirror,
  `AlarmCoordinator` (the only writer of AlarmManager + mirror) and `MirrorReconciler`. No Compose. Features reach it only
  through the hooks in `core.data.hooks` (`AlarmScheduler`, `NightController`, `AlarmIntents`).
- `feature:*` depend only on `core:*`, never on each other. Cross-feature navigation goes through lambdas wired in `app`
  (`navigation/SleepSaverApp.kt`, `appGraph`). New tabs: `feature:tonight` (alarm, Week plan, Night screen, Alarm check,
  ringing Activity), `feature:winddown` (Night steps, routine editor, guided tools, Night reset).
- Android module tests: JUnit 4 + Robolectric + Truth.

## Invariants
- `java.time` only. Store instants (UTC) plus the offset they were recorded in; derive local times from that offset.
- Never write to Health Connect. No network calls, no analytics. All data stays on device.
- User edits are separate rows (overrides) so raw data can always be restored.
- Original name, copy and visuals: don't reuse another sleep app's names or wording (e.g. no "Super" streak).
- Dark-only UI, one accent colour (tide teal), big glanceable numbers. One line in-app: wellness tool, not medical advice.
  The ringing screen's warm glow (`ringGlow`) is the only non-grey surface and stays dark.
- Alarms (v2): honest copy (a phone can't measure stages: "a restless moment", never "light sleep detected"); no current
  time or countdown at night; the wake time always rings. Direct boot: anything reachable before the first unlock
  (alarm receivers/services, the ringing Activity, `AlarmBootstrap`) injects repositories only as `dagger.Lazy`/`Provider`
  and calls `.get()` only when the user is unlocked. Boot receivers and re-plans never start a service. Never add
  INTERNET, RECORD_AUDIO or ACTIVITY_RECOGNITION (`ManifestPermissionsTest`).
- Banned competitor terms for any copy: see `docs/COMPETITIVE_ANALYSIS.md` (Naming) and SPEC list (smart wake, wake-up
  window/phase, sleep notes, wake-up mood, sunrise alarm, soundscapes, missions, readiness, Lights out, ...).

## UI kit (`core:ui`)
- Components (`com.sleepsaver.core.ui.components`): `DebtGauge`, `StreakBadge`, `ChangeChip`, `InfoChip`, `PhaseCard`
  (`PhaseCardDefaults.CompactWidth` in rows), `SleepSaverCard`, `SectionCard`, `SectionHeader`, `IconListItem`,
  `EmptyState`, `SegmentedControl`, `TimeScrubber`, `MinuteStepper`, `PrimaryButton`/`SecondaryButton`/`TextLinkButton`,
  `LoadingOrb`, `ScreenHeader`, `WellnessDisclaimer`; v2: `DayChipRow`/`DayTabs`, `LocalTimeScrubber`, `SelectableChip`,
  `SleepSaverSwitch`/`ToggleRow`, `NoticeCard`, `CustomActivityForm`, `MorningFeelScale`, `EvidenceBadge`, `MarkChips`,
  `HushControls`, `WarmGlow`. Visuals: `PhaseType.icon/title()/tint/descriptionRes`,
  `HabitType.icon/title()/description()`, `DebtStatus.label()/color`, and labels/icons for every v2 enum
  (`Mark`, `MorningFeel`, `Technique`, `EvidenceLevel`, `WakeStyle`, `AlarmTone`, `AlarmMode`, `SnoozeStyle`, `HushColor`,
  `KeelBand`, `AlarmBlocker`). Platform: `rememberNotificationAccess`, `rememberAlarmAccess` (Alarm check),
  `rememberMediaVolumeHigh`, `DimScreen`. Spacing scale: `theme/Spacing`.
- Formatting (`com.sleepsaver.core.ui.format`, pure Kotlin): `formatDuration`, `formatHours1`, `formatDebtChange`,
  `formatClockTime`, `formatClockAxis`, `formatTimeRange`, `formatShift`, `formatNightLabel`, `formatShortDate`,
  `formatWindowHeader`, `formatDayShort/Narrow/Long`, `formatDays`, `formatAboutMinutes`, `formatSignedHours1`,
  `String.keepTimesTogether()`. In composables use `clockTime(...)`/`timeRange(...)`/`clockAxis(...)`, which read
  `LocalUse24HourClock` (provided by `app`; the ringing Activity provides its own).
- `core:ui` string resources are prefixed `ss_` so feature modules can't override them by accident.

## Screenshot tests
Every Compose library module (`core:ui`, `feature:*`) gets Roborazzi on Robolectric native graphics from the
`sleepsaver.android.library.compose` convention, plus the helpers in `core:ui`'s test fixtures
(`com.sleepsaver.core.ui.testing`, already on each module's test classpath).
```kotlin
class HomeScreenshotTest : ScreenshotTest() {   // Robolectric SDK 36, native graphics, Pixel 7 portrait, night
    @Test fun lowDebt() = captureScreen("home_low_debt") { HomeContent(state = lowDebtState, onSync = {}) }
    @Test fun gauge() = captureComponent("home_gauge") { DebtGauge(Duration.ofMinutes(222), DebtStatus.MODERATE) }
}
```
- `captureScreen` renders the full 411 × 914 dp phone; `captureComponent` renders the content's own size plus a 16 dp
  margin. Both wrap `SleepSaverTheme` and a background `Surface`. Content taller than 914 dp is clipped: split it.
- Capture stateless composables (state + lambdas in, no ViewModel/Hilt), so give each screen a `XxxContent(state, ...)`.
- Run: `./gradlew :feature:sleep:testDebugUnitTest` (add `--tests '*HomeScreenshotTest*'` to narrow it). Every debug
  unit-test run records, so there is no separate record task and nothing to verify against.
- PNGs land in `<module>/build/outputs/roborazzi/<name>.png` (a screen is 1078 × 2399 px). Open them with the Read tool
  and check spacing, alignment, contrast and clipping. Use unique snake_case names per module.
- `ScreenshotTest` hosts content in a Compose test rule (`composeRule`): infinite animations (spinners, the orb) render
  their first frame instead of hanging the capture, `captureScreen` includes dialogs/sheets, and a test may capture
  several times. The top-level `captureScreen`/`captureComponent` are only for classes that can't extend it. For 24-hour variants wrap content in
  `CompositionLocalProvider(LocalUse24HourClock provides true)`; for large system fonts provide
  `LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)` (see `LargeFontScreenshotTest`). Reference images of the whole kit come from
  `./gradlew :core:ui:testDebugUnitTest` (`core/ui/build/outputs/roborazzi/`, e.g. `kit_home.png`).
- Other Robolectric tests in these modules can reuse `@Config(sdk = [SCREENSHOT_SDK])`: Robolectric doesn't know SDK 37.
