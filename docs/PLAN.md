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
core/notifications          channels, reminder scheduler (AlarmManager, exact when allowed), morning-review notifier, boot/time receivers
core/alarm                  v2: the alarm machinery. Exact alarms, ring and night foreground services, tone + noise synthesis,
                            device-protected mirror (rings before first unlock), coordinator/reconciler, NightController. No Compose.
feature/sleep               home: debt gauge, streak, chips, energy potential, phase cards, next-up habit, Tonight row
feature/energy              24h vertical timeline (Canvas), habits bottom sheet
feature/tonight             v2 tab: Surfacing alarm card, Week plan + Day plan editor, one-offs, Drop anchor, Hush, Catnap,
                            Night screen (no clock), Alarm check, the full-screen ringing Activity
feature/winddown            v2: Night steps (tonight's checklist), the routine editor, step library + suggestions,
                            guided tools (Slow tide, Body scan, Muscle release, Tomorrow list), Night reset
feature/progress            14-day window: Sleep Times (+ Keel) / Sleep Debt / Sleep Quality (+ marks patterns)
feature/review              morning review flow (+ Morning feel, marks, odd-night prompt, Wake landing), night detail,
                            edit night, add nap / missing night, "how we measure"
feature/habits              habit picker/editor ("Edit Night steps" opens the winddown routine editor)
feature/onboarding          onboarding steps + Health Connect fix-it / permission screens
feature/settings            settings, data source, Week plan row, Alarm readiness, export (CSV/JSON via SAF), reset
feature/learn               13 markdown articles from assets + tiny markdown renderer
feature/widget              Glance widget (debt + current phase)
```
Bottom bar: Sleep, Energy, **Tonight**, Progress, Learn.

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

**v2 (database version 2, `AutoMigration(1 → 2)` with `Migration1To2`).** `routine_activities` gains `techniqueKey`,
`leadMin`, `durationMin` (the migration maps the seven v1 default activities by emoji, which is locale-proof). New tables:

| Entity | Key | What |
|---|---|---|
| `day_plans` | `dayOfWeek` (ISO 1–7) | morning (wake time or usual, alarm mode, window, wake-up style, tone, volume, snooze style/length/count, overrun) and evening (bedtime or rule, Night steps on/off, wind-down length, own routine?) |
| `day_routine_items` | (dayOfWeek, activityId) FK→activities cascade | a day's own Night steps list |
| `plan_overrides` | `date` | one-offs: DAY_OFF, JUST_ONCE (time + mode). Deleting the row reverts to the week plan |
| `plan_pauses` | `id = 1` | "Pause alarms until …" |
| `night_step_logs` | (nightOf, activityId) | checked Night steps (no FK: history survives deleting a custom step) |
| `tomorrow_lists` | `nightOf` | the Tomorrow list text |
| `night_marks` | (wakeDate, mark) | stored marks (derived marks are computed) |
| `morning_feel` | `wakeDate` | 1–5, source RING / REVIEW |
| `anchors` | id auto | Drop anchor taps (instant + offset, wake date, source, whole-night log?) |
| `movement_logs`, `movement_epochs` | id / (logId, startEpochMs) | phone movement streams (window run or whole night) and their 30-s epochs |
| `alarm_events` | id auto | one row per alarm occurrence (kind, reason, first ring, snoozes, outcome, sensing start, max z) |
| `alarm_tuning` | `id = 1` | the restless-trigger tuning row (RAISED / LOWERED / RESET) |
| `hush_plays`, `prompt_log` | id / (wakeDate, kind) | Hush plays (for the derived mark); prompts shown once per day |

Every instant is stored with the offset it was recorded in. Demo mode snapshots and restores the journal tables
(marks, Morning feel, step logs, Tomorrow lists) like the edit tables and fills them with synthetic content; the week
plan, anchors, movement and alarm history are configuration and device logs and stay untouched.

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

### 3.5 Wake time and bedtime (learned from your data, adjustable)
- **Learning** (`SleepTimingEstimator`): for a target wake date T, main sleeps with wake dates in [T − **28** d, T)
  (strictly before T, so a schedule doesn't move when that night arrives), naps excluded, edits applied. Weekdays and
  weekends apart: if ≥ **2** nights share T's kind (Sat/Sun vs Mon–Fri) only those count, else all nights (≥ **3**),
  else nothing is learned. Most recent **14**, weights **0.85**^(i−1), circular mean of the local time of day (each
  night in its recorded offset), so 23:50 and 00:10 average to midnight.
- **Typical wake W** (Settings → Wake time): *From my sleep data* (default) = learned wake time for that day, or the
  user's own wake time until there's enough data; *A fixed time* = the user's own wake time.
- **Target bedtime B** (Settings → Bedtime): *From my sleep data* (default) = learned fall-asleep time for tonight's
  kind of night − 15 min latency (the user's own bedtime until there's enough data); *To meet my sleep need* =
  tomorrow's W − need − 15 min; *A fixed time* = the user's own bedtime. A time before noon means after midnight.
- The sleep midpoint (circular mean of main-sleep midpoints) is still computed and kept in the schedule for reference.

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
| Target bedtime B | per Settings → Bedtime (§3.5) |
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

Reminders: one `AlarmManager` alarm for the earliest pending reminder; on fire it posts every reminder due within
±2 min and schedules the next. Re-planned on schedule change, boot, time/zone change, and Drop anchor. Since v2 the app
holds `USE_EXACT_ALARM` (it is an alarm clock), so the alarm is `setExactAndAllowWhileIdle` when exact alarms are
allowed (inexact `setAndAllowWhileIdle` drifted wind-down cues by up to an hour), else the old inexact call.
After Drop anchor the night's remaining wind-down, bedtime and nap reminders are marked posted without posting (no
notifications overnight except the alarm). The evening-routine reminder is "Night steps start now" and lists tonight's
unchecked steps. Channels: Morning review, Morning light, Cutoffs, Wind-down, Bedtime, Naps, Sync (+ core:alarm's
Alarm, Alarm status, Night).

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

### 3.11 Week plan and one-offs (v2)
- Seven `DayPlan`s, Monday first. A day's **morning** (wake time, alarm) belongs to its wake date; its **evening**
  (bedtime, Night steps) to that evening, i.e. the night before day + 1 ("Monday evening (before Tuesday)").
- `resolve(date)`: JUST_ONCE (time + mode) beats a pause; a pause or DAY_OFF turns the alarm off and the day uses the
  usual wake time; otherwise the day's own. An alarm needs a set time: switching one on fills it with today's usual
  wake time rounded to 5 min, so an alarm never follows the learned time on its own (the smart alarm would otherwise
  pull its own learned time earlier).
- Energy schedule: W = the resolved day's own time (`TimingSource.WEEK_PLAN`), else the Settings rule (§3.5). B = the
  evening's set bedtime, else the Settings rule; SLEEP_NEED uses tomorrow's plan-aware W. The wind-down phase length
  is the evening's, 30–150 min (never below `minPhaseLength`). Missing nights use each day's own W (§3.6).
  `EnergySchedule.nightStepsOn` = the evening's switch; with it off the evening-routine reminder isn't posted.
- Settings rows show the **usual** values (plan ignored) with "Used on days without their own time."
- Copy-to: MORNING and/or EVENING to picked days. Presets "Work week + free weekend", "Same every day".
- One-off rows from today − 3 days still feed the schedule (night owls, Process S); rows older than 7 days are pruned.
  Pause at most 60 days ahead.
- **Planned keel / plan check** (§3.15) and **Debt preview** (§3.16) are shown in the editor.

### 3.12 Surfacing alarm (v2)
**Planning.** `AlarmPlanner.upcoming`: dates today .. today + 7 with an alarm on; target = `ZonedDateTime.of(date,
time, zone)` (DST gap → forward, overlap → earlier offset); id = `"<date>T<HH:mm>"`; occurrences already handled
(stopped, timed out, missed, in progress) are excluded so a stopped early ring never rings again at the wake time.

**Window.** SURFACING: base window 10–45 min (default 30). A *debt-aware* night (planned sleep opportunity from the
anchor or planned bedtime + latency to the wake time < 6.5 h, or debt status MODERATE/HIGH) widens it by 10 min (cap
45) and lowers the trigger. FIXED: zero window.

**Sensing.** From 15 min before the window to the wake time a `health` foreground service (started by the exact
window alarm) reads the accelerometer at 25 Hz (wake-up sensor with 30-s batching when the phone has one, else a
bounded partial wake lock). Whole-night logging only after Drop anchor, opt-in. `MovementMeter`: per-axis EMA baseline
(τ 1 s), deviation above a 0.03 m/s² floor, time-weighted mean per 30-s epoch × 1000 (rate-independent); epochs
covered < 50 % are missing.

**Trigger** (`SurfacingDecider`). Calibration = the 15 min before the window (≥ 20 epochs): median and MAD,
σ = max(1.4826·MAD, 5). z = (activity − median)/σ. Not before window start + 3 min. Fire when 2 of the last 3
contiguous epochs reach zT (2.5) or one epoch reaches 5. zT + 1 where the Garmin prior says deep sleep is likely
(pDeep > 0.5); zT − 0.5 on debt-aware nights. Stillness never fires; nothing fires at or after the wake time (the
wake-time alarm is the ring). A run that never moved above 10 is logged PLACEMENT_FALLBACK; too few epochs or a refused
service is SENSING_UNAVAILABLE. **No movement → it rings at the wake time** (no prediction-only early ring).

**Garmin prior** (`DeepPriorEstimator`). Up to 60 nights with stages in the last 90 days (≥ 7, else none; never demo
nights): pDeep per 10-min bin since onset and before the end of the last scored stage (so the final awakening doesn't
bias it), Laplace `(deep + 1)/(covered + 2)`, blended 50/50.

**Ringing.** One path for every ring: exact alarm → `AlarmRingService` (`mediaPlayback` FGS, `USAGE_ALARM`) →
full-screen notification → ringing Activity. Wake-up styles: Calm (one soft chime, 20 s quiet, 90 s swell, vibration at
+60 s), Standard (60 s swell, vibration +30 s), Firm (20 s swell, vibration at once, escalation at 3 min), Quiet bed
(vibration only for 2 min, then sound). Gain is the player's, relative to the alarm volume (never the stream volume).
No Snooze/Stop by the escalation time → full volume + vibration; auto-silence after 10 min; one backup ring 5 min
later; a backup that times out is MISSED. With notifications or the alarm channel blocked a ring stops itself after
2 min (there is no Stop button).

**Snooze.** TAPERING 9, 7, 5, 3, 2 min / FIXED (1–20 min × 1–5) / NONE; every snooze ends by min(now + length, cap),
cap = the wake time while still before it (after an early ring a snooze never passes the wake time), else wake time +
overrun (0–20, default 10). "Last ring" shows before the button disappears.

**Wake landing.** After the night syncs, the ring is placed on the watch's hypnogram: stage at the ring, majority stage
in the 5 min before, whole minutes early, deep share of the window (when ≥ 50 % of it is scored). Copy: "Watch
estimate: light sleep when it rang." **Tuning:** of the last 7 surfacing landings with stages after the last change,
≥ 3 restless rings in watch-scored deep sleep raise zT by 0.25; else ≥ 5 wake-time rings with ≥ 10 min of non-deep
window lower it; bounds 1.5–3.5, 7-day cooldown. "Reset to default" stores a RESET row at 2.5 (rings before it don't
count again).

**Catnap.** Rings at now + 5 min + 10/20/30 min. Hint: IN_AFTERNOON_DIP, CLOSE_TO_BEDTIME (rings within 6 h of B), EARLY
(< 3 h after waking); 30 min flagged "may leave you groggy at first"; coffee hint for ≤ 20 min before the caffeine
cutoff. Snooze 5 min once. **Test ring**: 60 s after the tap through the real path, rings 60 s.

### 3.13 Night steps (v2)
- The evening's routine = its own list, or the every-evening list (`routine_activities.selected`). Each step starts
  `lead` before B and lasts `duration` (0 = checklist item); sorted by start, overlaps allowed. Defaults (lead/duration
  min): warm shower 90/15, screens down 60/0, read on paper 50/20, Tomorrow list 45/5, stretching 40/10, journaling
  35/5, Slow tide 30/10, muscle release 25/12, body scan 20/10, cool dark room 15/0, Hush 10/0, custom 45/10. The window
  shown is the wind-down phase start → B.
- Evidence grades (Good / Some / Early / Little evidence): warm shower, screens down, body scan, cool room = GOOD;
  Tomorrow list, Slow tide, muscle release = MODERATE ("Some"); reading, stretching, journaling = EARLY; Hush, TV,
  gaming = LITTLE. Screen-based steps are never suggested.
- Tracking: an evening is DONE with ≥ 1 and ≥ half its steps checked; evenings with steps off or none, and evenings
  before the first ever check, are neutral (neither count nor break). "4 of the last 7 nights"; a quiet streak bridges
  one missed evening.
- Suggestions (≤ 2, never already in the routine): none in a week with an Away-from-home or Unwell mark (incl.
  tonight's); slow to settle (median anchor latency > 30 min) → Tomorrow list, Slow tide; restless nights (mean awake
  ≥ 30 min) → muscle release, body scan, cool room; stressful days (Stress on ≥ 3 of 7) → Tomorrow list, Slow tide;
  drifting bedtimes (circular SD ≥ 60 min) → screens down, warm shower; screens in the routine or in bed → screens down,
  read on paper; HIGH debt → warm shower, Slow tide; then starters (GOOD, then MODERATE).
- Guided tools dim to 1 % after 20 s and cue with haptics. Slow tide 4 s in / 6 s out (also 5/5, 4/8), 5/10/15 min, an
  "eyes closed" haptics-only mode. Body scan 10 × 60 s. Muscle release 12 groups × (5 s tense, 15 s release). Night
  reset: 2 min of Slow tide, then the stimulus-control steps; no clock.

### 3.14 Marks and Morning feel (v2)
- Marks (stored): late caffeine, alcohol, late meal, late workout, stress, screens in bed, away from home, unwell.
  Derived: Night steps done, Hush on, anchor dropped. Keyed by the wake date of the night they precede; editable for
  7 days (and tonight).
- "What went with your nights": per mark × metric (total sleep, awake time, Morning feel) over 90 days from the mark's
  first date; ≥ 6 nights with and without; difference of medians with a seeded 90 % bootstrap interval (1000
  resamples). CLEAR when the interval excludes 0 by ≥ 15 min sleep / 5 min awake / 0.5 feel, POSSIBLE when it just
  excludes 0. Every insight ends "This shows what went together, not what caused it."
- Odd night: vs the 28-day medians (≥ 7 nights): total ±60 min, awake +20 min, onset ±60 min (good nights too), at most
  once a day → "Anything different last night?".
- Morning feel: Foggy / Slow / Okay / Clear / Bright (1–5), after a planned alarm or in the review, editable on its own
  wake date; buffered in the device-protected mirror before the first unlock.

### 3.15 Keel, social jetlag, planned keel (v2)
- **Keel** = Sleep Regularity Index over the last 14 wake dates: each valid day (night present) is the window
  [noon before, noon] in the night's offset, asleep = main sleep minus its awake periods (naps excluded); minutes
  (sampled at their midpoint) paired **24 elapsed hours** apart where both are valid; score = clamp(round(200·P −
  100), 0, 100). Needs 7 valid days ("Calibrating"). Bands ≥ 80 Even keel, ≥ 70 Fair, else Choppy. Also the circular
  SD of onset and wake (≥ 3 nights). A clock change costs about 2 h of mismatched minutes, so a perfectly regular
  fortnight across DST scores 99.
- **Social jetlag** = circular-mean mid-sleep on free days − alarm days (last 28 days, ≥ 2 free, ≥ 3 alarm days).
  Free days = days without an alarm in the week plan; before any alarm is set, Saturday and Sunday.
- **Planned keel** = Keel of 14 synthetic nights [B + latency, W] from the plan. Alarm days come from the weekly plan
  only (a one-off never makes 13 free days). Notice (dismissible, never blocking) when free days wake > 60 min later
  than alarm days or |planned social jetlag| ≥ 60 min.

### 3.16 Drop anchor, debt preview, Hush, night rules (v2)
- **Drop anchor** records an instant + offset and starts the night state: no current time or countdown anywhere in the
  app until the window opens (or the wake zone, with no alarm), remaining reminders suppressed, the alarm re-planned.
  Latency = Garmin onset − anchor (ignored outside −30..180 min, shown "about 15 min"); efficiency = asleep / (wake −
  anchor). With ≥ 7 latencies (of the last 30 within 60 days) the median, clamped 5–45 min, replaces the 15-min
  `sleepLatency` in bedtimes and the debt preview (`SleepTiming.latency`). The night ends at the morning alarm's stop,
  "I'm up", or anchor + 14 h.
- **Debt preview**: the evening before a wake date, predicted sleep = opportunity [B + latency, W] − median awake of the
  last 14 nights; synthetic nights for today .. that date are added to the real ones and run through §3.3; shown as
  "Asleep by 11:10 PM → debt tomorrow about 3.1 h (−0.4 h)"; an increase is grey, never red; needs 3 nights.
- **Hush**: white (Gaussian), pink (Paul Kellet) or brown (leaky integrator + DC block) noise, a pre-rendered 20-s loop
  with a crossfaded seam and a slow ±1.5 dB drift, `USAGE_MEDIA`, default volume 25 %, timer 15/30/45/60/90 (default 45)
  or "until my window", 10-s fade-in, 5-min fade-out; stops before any ring; never starts from boot. Copy: masks
  background noise; earplugs suit sudden noise better.

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
17. **Wake time and bedtime now follow your data** (changed after review). The original formula (sleep midpoint plus
    half your need) put the schedule's wake time near 7:50 for someone whose alarm goes at 6:55. Both anchors are now
    learned from when you actually wake and fall asleep, weekdays and weekends apart, and either can be fixed in
    Settings; bedtime can also be set to leave room for your full sleep need.
18. **A phone can't measure sleep stages** (v2). Phone movement can't tell light from deep sleep (validation studies
    found no correlation with lab stages), but movement bursts are less likely in deep sleep. The Surfacing alarm
    therefore rings at "a restless moment", never claims a stage, and rings at the wake time when there is no usable
    movement. The only stage words in the app are the watch's, after the night syncs (Wake landing).
19. **Most of a smart alarm's felt benefit is the gentle cues.** No trial shows phone smart alarms reduce grogginess
    after a full night (deep sleep is rare by then). The wake-up styles, the snooze that lands on the wake time and the
    debt-aware window (deep sleep near the end is likelier after short nights) carry most of the value.
20. **Sensing only around the window.** Whole-night accelerometer sensing would hold a wake lock for 7–9 h. The alarm
    senses from 15 min before its window; whole-night logging is an opt-in after Drop anchor.
21. **Per-day independence vs regularity.** Regular sleep timing is one of the strongest findings in recent sleep
    research, so a fully independent week plan can hurt. The editor shows a Planned keel and a gentle, dismissible
    notice when free days wake more than an hour later; it never blocks saving. Free days are days without an alarm.
22. **Night sounds.** The evidence for noise is very low and a 2026 lab study found pink noise reduced REM sleep. Hush
    is framed as masking a noisy room, defaults to a 45-min timer and a low volume, and suggests earplugs.
23. **No clock at night.** Watching the clock keeps people awake, so after Drop anchor (or bedtime) the app shows no
    time and no countdown until morning, and posts nothing but the alarm.
24. **Marks show patterns, not causes.** Small samples make "after alcohol you sleep 32 min less" mostly noise, so a
    pattern needs 6 nights each way and a 90 % bootstrap interval that excludes zero, and every line says it isn't
    causal.
25. **Tracking is an invitation.** Night steps count "4 of the last 7", evenings with steps off don't count, a single
    missed evening doesn't break the quiet streak, and nothing says "you missed".
26. **Existing names.** Research found that several existing terms (Energy schedule, Melatonin window, Wake zone,
    Grogginess, Energy potential) mirror another app's wording. They come from the original spec and were left for the
    owner to decide; all v2 names are new (Surfacing alarm, Night steps, Week plan, Drop anchor, Keel, Marks, Hush,
    Catnap, Wake landing, Morning feel).

## 5. Alarm reliability (v2, Android 9–17)

- **Permissions**: `USE_EXACT_ALARM` (+ `SCHEDULE_EXACT_ALARM` ≤ API 32), `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`
  + `_MEDIA_PLAYBACK` (ring, Hush) + `_HEALTH` (sensing; prerequisite `HIGH_SAMPLING_RATE_SENSORS`, install-time),
  `WAKE_LOCK`, `VIBRATE`. Never INTERNET, RECORD_AUDIO or ACTIVITY_RECOGNITION.
- **Arming**: `setAlarmClock` at the wake time (shown as the phone's next alarm, Doze-exempt) and
  `setExactAndAllowWhileIdle` at window start − 15 min for sensing. Snooze, test, smart trigger, catch-up and backup use
  a separate PendingIntent (RING_EXTRA), the catnap its own, so none of them un-arms the planned alarm. A smart trigger
  moves RING_EXTRA to now + 5 s: every ring goes through one path.
- **Direct boot**: the next alarms, ring state and a pending Morning feel live in device-protected storage; the
  receivers, ring service and ringing Activity are direct-boot aware, so an alarm rings before the first unlock after
  an overnight reboot. The Application injects its other bootstraps lazily and starts them after unlock.
- **State machine**: one coordinator serialises AlarmManager calls and mirror writes; every transient state has an
  expiry and is reconciled on start, boot and every re-plan (a ring that died mid-ring resumes within its timeout,
  else it is recorded as timed out and the backup ring is armed). Boot receivers and re-plans never start a service.
  Failures to read the plan never cancel an armed alarm.
- **Alarm check**: notifications, the alarm channel, exact alarms, full-screen intents, background restriction,
  alarm volume, Do Not Disturb total silence, battery optimisation and Bluetooth output, each with a fix; plus "Test
  ring in 1 minute". Enabling an alarm requires notifications (the Stop button lives in the notification).
