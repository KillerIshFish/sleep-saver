# Sleep Saver — build plan

Working name **Sleep Saver**. Dark UI, one accent (tide teal `#3DD6C3`), big glanceable numbers.
Wellness tool, not medical advice. All data on-device. No network, no analytics.

## 1. Structure

```
build-logic/convention      convention plugins (one place for SDK levels, Compose, Hilt, Kotlin opts)
app                         MainActivity, NavHost, bottom nav, PrivacyPolicyActivity (HC rationale), Hilt app
core/domain                 PURE Kotlin/JVM. models, SleepConfig, all formulas. 100% unit tested
core/data                   Room, DataStore, Health Connect, FakeSleepDataSource, sync (Changes API + WorkManager),
                            repositories + "insights" use-cases that run the domain engine over stored data
core/ui                     theme, type, colors, shared components (chips, cards, empty states, time formatting)
core/notifications          channels, reminder scheduler (AlarmManager, inexact), morning-review notifier, boot/time receivers
feature/sleep               home: debt gauge, streak, chips, energy potential, phase cards, next-up habit
feature/energy              24h vertical timeline (Canvas), habits bottom sheet
feature/progress            14-day window: Sleep Times / Sleep Debt / Sleep Quality
feature/review              morning review flow, night detail, edit night, add nap / missing night, "how we measure"
feature/habits              habit picker/editor, evening-routine grid
feature/onboarding          onboarding steps + Health Connect fix-it / permission screens
feature/settings            settings, data source, export (CSV/JSON via SAF), reset
feature/learn               10 markdown articles from assets + tiny markdown renderer
feature/widget              Glance widget (debt + current phase)
```

**Why multi-module and not one module:** the pure-JVM `core:domain` is the boundary that pays for itself:
the compiler enforces "no Android in the engine", and its tests run in seconds without Robolectric.
Per-screen feature modules are mostly ceremony for a solo app, but with convention plugins each one is
a ~10-line build file, they build in parallel, and they keep features from reaching into each other.
If you'd rather collapse `feature/*` into `app` packages later, nothing in the design depends on them being modules.

Navigation: type-safe Navigation Compose routes (`@Serializable` objects). Each feature exposes
`NavGraphBuilder.xxxScreen(...)` + route; `app` wires them and owns the bottom bar.
Features never depend on other features; cross-feature navigation goes through lambdas from `app`.

Stack: Kotlin 2.4.20, AGP 9.4.1 (built-in Kotlin), Gradle 9.8, KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00,
Room 2.8.5, Health Connect 1.1.0, WorkManager 2.12.0, DataStore 1.2.1, Navigation 2.10.2, Glance 1.2.0.
minSdk 28, compile/target 37. JVM target 17.

## 2. Room entities (database `sleep_saver.db`)

| Entity | Key | Fields |
|---|---|---|
| `sleep_sessions` | `id` (HC record id / `demo:n`) | originPackage, startEpochMs, endEpochMs, startOffsetSec?, endOffsetSec?, ingestZoneId, title?, lastModifiedEpochMs, sourceKind (`HEALTH_CONNECT`/`DEMO`) |
| `sleep_stages` | (sessionId, startEpochMs) FK→sessions cascade | endEpochMs, stage (HC stage int) |
| `night_overrides` | `wakeDate` (ISO date) | fellAsleepEpochMs?, wokeUpEpochMs?, awakeMinutes?, napMinutes?, isManualNight, offsetSec, updatedAtEpochMs |
| `added_awake` | id auto | wakeDate, startEpochMs, endEpochMs, createdAtEpochMs (long-press "add awake time") |
| `manual_naps` | id auto | startEpochMs, endEpochMs, offsetSec, createdAtEpochMs |
| `vitals` | `id` | type (`RESTING_HR`/`HRV_RMSSD`/`SLEEP_HR_AVG`/`STEPS_DAY`), timeEpochMs, offsetSec?, value, originPackage, sourceKind |
| `morning_reviews` | `wakeDate` | status (`CONFIRMED`/`ADJUSTED`/`DISMISSED`), atEpochMs, debtBeforeMin, debtAfterMin |
| `habits` | `type` | enabled, reminderEnabled, offsetMinutes, sortOrder |
| `routine_activities` | id auto | label, emoji, isCustom, selected, sortOrder |

Raw Health Connect rows are never modified by edits; overrides live in their own tables, so **Revert** = delete the
override and added-awake rows. Nothing is ever written to Health Connect. Demo mode snapshots the four edit tables when it
turns on and restores them exactly when it turns off, so demo edits never touch real ones.

DataStore (`settings`): sleepNeedMin (480), typicalWakeMinOfDay (420), streakThresholdMin (300), lowDebtMaxMin (120),
highDebtMinMin (300), selectedSource (`com.garmin.android.apps.connectmobile` | `*all*`), demoMode, clockFormat (SYSTEM/H12/H24),
morningReviewEnabled (true), onboardingDone, needSuggestionDismissedValueMin/At. A separate private store (`sync_state`)
holds the Changes token, last sync time/error and the history-import flag.

## 3. Formulas and default constants (all in `SleepConfig`)

Times are `Instant` (UTC) + the offset they were recorded in. Local clock values come from the recorded offset
(HC gives `ZoneOffset`, not `ZoneId`; when null we use the device zone at ingest). Durations come from instants,
so DST nights are 7 h or 9 h in elapsed time, which is correct.

### 3.1 Night assembly
- Sessions shorter than **5 min** are ignored.
- **Source filter**: keep sessions from the selected origin. "All sources": when sessions from different origins
  overlap ≥ **50 %** of the shorter one, keep one: if one is less than half as long as the other, the longer wins;
  otherwise Garmin > has stages > longer > newest lastModified. Fragments from the same app are never de-duplicated.
- **Nap**: duration < **3 h** and local start in **[09:00, 20:00)**. Belongs to the sleep day of its local start date.
- Everything else is **night sleep**. Wake date of a session (local end time `e`, local start `s`):
  - `e.time < 14:00` → `e.date` (spec rule)
  - else if `s.date == e.date && s.time ≥ 14:00` → `e.date + 1` (evening sleep that hasn't crossed midnight → tonight)
  - else → `e.date` (very late wake after a long sleep)
- Per wake date, sort night sessions; merge neighbours whose gap is **< 60 min** into one cluster (gap kept as an
  interruption). **Main sleep** = cluster with most sleep; other clusters for the same wake date are "extra segments"
  (counted, drawn as separate blocks).
- **Sleep time** of a session = (end − start) − Σ awake-type stages (AWAKE, OUT_OF_BED, AWAKE_IN_BED), clipped to the
  session. Unknown/uncovered time counts as asleep. No stages → whole session is asleep.
- **Interruptions** = awake stages ≥ **5 min** + gaps between merged fragments.
- **Night total** = main + extra segments + naps of that day. The "Last night" chip shows main sleep only.
- **Overrides** (precedence over HC): window = [fellAsleep ?: main.start, wokeUp ?: main.end];
  awake = awakeMinutes ?: (awake stages + gaps clipped to window + added awake intervals);
  naps = napMinutes ?: (detected + manual naps). Main sleep = window − awake. Manual night = override with no raw data.
  Moving a time over an extra segment merges it into main sleep (the stretch between them counts as awake). Nap time
  that overlaps night sleep is counted once. Added awake time counts as an edit (`Night.isEdited`).

### 3.2 Sleep need
- Default **8 h 00 m**, editable.
- Estimate offered when ≥ **14** nights exist (looks at last **60**):
  - typical weekday wake = median local wake minute of nights whose wake date is Mon–Fri
  - free night = wake ≥ typical weekday wake + **45 min**
  - drop a free night if the previous night's total < need − **60 min** (first recovery night)
  - estimate = median total of remaining free nights, rounded to **5 min**, clamped **[6 h, 10 h 30 m]**
  - needs ≥ **4** free nights; confidence High (n ≥ 8 and IQR ≤ 45 m), Medium (n ≥ 5 and IQR ≤ 75 m), else Low
  - after "Ignore", re-offered only when the estimate moves ≥ 15 min and ≥ 7 days have passed

### 3.3 Sleep debt (as of wake date D)
```
for i = 1..14: night_i = night with wakeDate D-(i-1)      (i = 1 is last night)
  delta_i = need − total_i, floored at −2 h (surplus cap)
  w_i     = 0.85^(i−1)
debt(D)  = max(0, Σ_{present nights} w_i · delta_i)        missing nights skipped (no renormalisation) and listed
change   = debt(D) − debt(D−1)
```
Displayed debt: if last night isn't in yet, show debt(D−1) with a "waiting for last night" state.
Nights before your very first recorded night are not flagged as missing.

### 3.4 Status, streak, energy potential
- Status: **Low** < 2 h ≤ **Moderate** < 5 h ≤ **High**.
- Streak ("steady streak"): count back day by day while debt(d) < **5 h**. A day whose own night is missing is skipped
  (doesn't count, doesn't break) unless ≥ **3** missing days in a row, which breaks it.
- Energy potential = clamp(100 − **5**·debtHours, **50**, **100**), rounded.

### 3.5 Circadian anchor
- Up to **14** most recent main sleeps from the last **21** days (naps excluded), weight w_i = **0.85**^(i−1).
- θ_i = 2π·(local minute-of-day of midpoint)/1440, anchor = atan2(Σ w sinθ, Σ w cosθ) → minute-of-day.
- Fewer than **3** nights → anchor = onboarding typical wake − need/2.
- Typical wake **W** = anchor + need/2.

### 3.6 Energy curve (two-process model), t in hours
- S awake: `S = 1 − (1 − S0)·e^(−t/18.2)`; asleep: `S = S0·e^(−t/4.2)`. Seeded at 0.5 72 h back, integrated
  over real sleep (main, extra segments, naps; awake gaps count as awake), then projected awake until bedtime.
  A night missing from that history (including last night before Garmin syncs) is assumed to be a nominal
  [W − need, W] night, so the schedule doesn't jump when the data arrives.
- C (phase-locked to W): `C = 0.35·cos(2π(h − (W+9.5))/24) + 0.15·cos(2π(h − (W+2.0))/12)`
- Inertia after actual wake Wa: `I = −k·e^(−(t−Wa)/0.5)`, `k = min(0.15·(1 + debtHours/4), 0.45)`
- `E = C − S + I`, min-max normalised to 0..1 over [Wa, bedtime] at 5-min steps.
- Constants tuned numerically: with a steady 8 h night the raw curve peaks at W+3.3 h and W+11.6 h, dip at W+7.8 h.

### 3.7 Phases (W = typical wake today, Wa = actual wake or W if not in yet)
| Phase | Rule |
|---|---|
| Grogginess | [Wa, Wa + clamp(0.5 h·ln(k/0.02), 60 m, 120 m)] |
| Morning peak | nominal [W+2h, W+5h]; centre = argmax E in [W+1h, W+6.5h]; slide window by centre−(W+3.5h), clamped ±45 m; start ≥ grogginess end |
| Afternoon dip | nominal [W+6.5h, W+9h]; centre = argmin E in [W+5h, W+10h]; slide ±45 m vs W+7.75h |
| Evening peak | nominal [W+10h, W+13h]; centre = argmax E in [W+9h, W+14.5h]; slide ±45 m vs W+11.5h |
| Target bedtime B | tomorrow's W − need − 15 m |
| Melatonin window | [B − 30 m, B + 30 m] |
| Wind-down | [B − 90 m, B − 30 m] |
| Wake zone | [W − 30 m, W + 30 m] (today's and tomorrow's) |

Every phase lasts at least 30 min; the peaks and the dip never start before grogginess ends. Phases may overlap
(long sleep need, 23-hour DST days); the later one is "current".

Shift vs yesterday = start minute-of-day today − yesterday's (each schedule computed with the data available that day);
shown as "↑13m earlier" / "↓8m later" when |shift| ≥ 1 min.

**Schedule day.** Until yesterday's melatonin window has ended, the home screen, widget and reminders keep using
yesterday's schedule, so a night owl at 00:30 still sees tonight's wind-down and bedtime, not tomorrow's morning.

### 3.8 Habits (all offsets configurable)
| Habit | Default time | Default |
|---|---|---|
| Morning light | W + 15 m (typical wake: Garmin data lags) | on |
| Caffeine cutoff | B − 10 h | on |
| Large-meal cutoff | B − 3 h | off |
| Alcohol cutoff | B − 3 h | off |
| Intense-exercise cutoff | B − 2 h | off |
| Dim the lights | wind-down start | on |
| Evening routine | wind-down start, chosen activities | on |
| Bedtime | melatonin window start | on |
| Nap window | afternoon-dip start, 20–30 min max | off |

Reminders: one inexact `AlarmManager.setAndAllowWhileIdle` for the earliest pending reminder; on fire it posts every
reminder due within ±2 min and schedules the next. Re-planned on schedule change, boot, time/zone change.
No exact-alarm permission. Channels: Morning review, Morning light, Cutoffs, Wind-down, Bedtime, Naps, Sync.

### 3.9 Morning-review recalculation
`before = debt(D−1)`, `lastNight = delta_1`, `after = debt(D)`, `fade = after − before − lastNight` (older nights
weigh less each day). Shown as "8h need − 6h 53m slept = +1.1 h", "older nights fading −0.6 h", "new debt 3.2 h".

### 3.10 Sync
- Health Connect status via `getSdkStatus`; HC is read for **all origins** and filtered locally, so switching source is
  instant and the source picker can list every app that wrote sleep.
- Initial import: get a Changes token first, then page-read history (365 d with READ_HEALTH_DATA_HISTORY, else 30 d),
  then store the token. Incremental: Changes API for SleepSession, RestingHR, HRV; steps aggregated per day and sleeping HR
  averaged per session for touched days. Expired token → full re-read of the window.
- Triggers: app start/resume, periodic WorkManager (1 h, needs READ_HEALTH_DATA_IN_BACKGROUND), and a retry chain
  (15/30/60 min… until 14:00) while last night is missing. Without background permission, the morning review falls
  back to a time-based reminder at W + 45 m.
- Demo mode writes 60 synthetic nights (origin `com.sleepsaver.demo`) into Room and rolls them forward daily; turning it
  off deletes them and restores your real edits.
- Testing without a watch: `tools/hcseeder` is a debug-only app that writes sample sleep into Health Connect on an
  emulator (usage in its `SeedActivity` KDoc); pick it as the data source in Settings.

## 4. Things in the spec I think are wrong or unclear (and what I did)

1. **Sleep ending after 2 pm** wasn't defined. Rule above: evening-only sessions roll to tonight; otherwise the end date.
2. **Several night sessions on one wake date with ≥ 60 min gaps**: the longest is main sleep; the rest still count toward
   the total and are drawn as separate blocks.
3. **Exponential weighting means sleeping exactly your need still drops debt ~15 %/day**, so "need − slept = ±X → new debt"
   doesn't add up arithmetically. The recalculation screen shows an explicit "older nights fading" line.
4. **Weights sum to ≈ 6, not 14.** A steady 1 h/night shortfall reads as ≈ 6 h debt. That's fine, but expect smaller
   numbers than a plain 14-day sum.
5. **Skipping missing nights hides debt** from nights you didn't wear the watch. The UI lists them and asks you to add them.
6. **Sleep-need estimate is biased upward** by recovery sleep and weekend lie-ins; excluding only the first recovery
   night is a partial fix. I clamp to 6–10.5 h, require ≥ 4 free nights, and show confidence.
7. **The energy curve barely moves the phases.** After 0–1 normalisation, debt only acts through inertia; in practice the
   curve slides phases ±5–15 min inside the guardrails, and the anchor (W) drives the schedule. I read "clamp to
   guardrails" as: keep each phase's guardrail width, let the curve slide it by at most ±45 min.
8. **"Melatonin window" = bedtime ± 30 min** is a product convention. Physiological melatonin onset is ~2 h before habitual
   sleep, so the copy says "best window to fall asleep" rather than making a hormone claim.
9. **Morning light** uses typical wake, not actual wake, because Garmin data arrives late.
10. **Streak with missing days** wasn't defined: skipped up to 2 days in a row, 3 breaks it.
11. **Short evening dozes** (start ≥ 20:00, any length) are not naps by your rule; they become extra segments of tonight.
12. **Garmin may not write stages** to Health Connect for every night; totals then fall back to session length minus nothing,
    i.e. time in bed. The Quality tab shows "no stage data" for those nights.
13. **Inexact reminders**: `setAndAllowWhileIdle` is usually within a few minutes but Doze can push it ~10 min.
14. **Missing history looked like an all-nighter** to the energy model (spec: "awake otherwise"), so every morning before
    Garmin synced the phases slid ~50 min and snapped back. Missing nights are now assumed to be a nominal night.
15. **Night owls after midnight**: "today" flips at midnight, but a 2 a.m. bedtime belongs to the previous evening. The
    schedule day now rolls over when yesterday's melatonin window ends.
16. **All-sources de-duplication** could let a short Garmin fragment knock out another app's whole night. The longer
    session now wins when one is less than half as long as the other.
