# Competitive analysis and the v2 feature decision

October 2026. Based on desk research into the leading sleep and alarm apps (store listings, help centres, reviews,
user discussions), the sleep-science literature and Android platform rules, scored by three panels: product and
retention, sleep science and product fit, and Android engineering. Sleep Saver's constraints shaped every call:
everything stays on the phone, there is no network permission, no account and no analytics, Health Connect is read
only, and the app is a wellness tool, not a medical device.

## 1. The landscape

| App | Best at | What users praise | Where it falls short |
|---|---|---|---|
| **Sleep Cycle** | Alarm that rings inside a window before a hard wake time (microphone or accelerometer) | The gentle window alarm, pre-sleep notes, a morning mood tap, shrinking snooze | Paywall creep; correlations without sample sizes; "the alarm always goes off at the end" is its top support question; only a weekend toggle for per-day schedules |
| **Sleep as Android, Alarmy** | The deepest Android alarm engine: smart period, skip next, one-off dates, snooze caps, backup alarm, regularity index; dismissal tasks (Alarmy) | Reliability for heavy sleepers, configurability | Steep learning curve, ads and subscriptions, cloud assistants, ad tracking (Alarmy); wrong sensitivity is the top smart-wake complaint; nobody checks the alarm against a real hypnogram |
| **Pillow, AutoSleep, SleepScore, SleepWatch** | Per-weekday wake schedules (learned or fixed, skip today), latest-bedtime reminders, a lights-off marker for latency and efficiency, consistency gauges | AutoSleep's one-time price and on-device data | Recurring alarms without smart wake (Pillow), paywalled tag analysis, stage-heavy scores that swing night to night |
| **BetterSleep, Calm, Headspace** | The evening: sound mixers, coloured noise, breathing guides, short guided exercises, a night-time SOS | Sounds and breathing; listening peaks at 9–11 pm | Billing complaints, timers that stop early or never, guilt-inducing streaks, shallow tracking, pseudo-scientific audio |
| **Hatch, Loftie, RISE** | Ordered bedtime steps with durations (Hatch), two-phase gentle alarms (Loftie), 14-night sleep debt and an energy schedule with a debt preview while setting the alarm (RISE) | Gentle wake-ups, routines tied to the bedside | Paywalled routines, "alarm not going off" help pages, a second alarm needed for weekends (RISE); no one has one weekly plan covering wake time, alarm behaviour and routine per day |
| **Garmin, Pixel Watch / Fitbit, WHOOP, Oura, Samsung** | 30-min wrist smart wake with a hard deadline, regularity metrics, bedtime suggestions, behaviour tags with impact analysis, 14-day debt (Oura) | Integration with the watch | Reviewers find the smart wake made little difference; tag logging is fiddly; cloud AI coaches are replacing structured features; none checks its smart wake against the night's data |

**The gap Sleep Saver can own.** Its users already wear a Garmin, so the watch's record of the night arrives the next
morning. The phone can sense a short window around the alarm, and the watch can then grade where the alarm landed.
No competitor closes that loop, and none combines per-day alarms, per-day wind-down routines and a sleep-debt view
in one weekly plan without an account or a network connection.

## 2. What the evidence says (and how it shaped the features)

| Topic | Evidence | Rule in Sleep Saver |
|---|---|---|
| Waking from deep sleep | Worse grogginess after deep (N3) awakenings, mostly after short nights or high sleep pressure (Hilditch & McHill 2019) | Debt-aware windows on short nights; avoid triggering where the Garmin prior says deep sleep is likely |
| Phone "sleep stage" sensing | Phone apps don't match lab stages and can't detect REM (Bhat 2015, Fino 2020); movement bursts are less likely in deep sleep (Giganti 2008) | Ring at "a restless moment", never claim a stage; ring at the wake time when there's no movement |
| Smart alarms overall | No trial shows less grogginess after a full night; a 30-min snooze window is harmless (Sundelin 2024) | Gentle cues, a snooze that lands on the wake time, honest copy |
| Warm shower or bath 1–2 h before bed | Meta-analysis of 17 studies (Haghayegh 2019) | Night step 90 min before bed, "Good evidence" |
| Evening light and screens | Strong lab evidence (Chang 2015; Brown 2022 consensus) | "Screens down" 60 min before bed |
| Five-minute to-do list | One sleep-lab trial (Scullin 2018) | Tomorrow list, shown again in the morning |
| Slow breathing, muscle relaxation, mindfulness | Subjective benefits (Laborde 2019; Eide 2026 review; Black 2015; AASM 2021) | Slow tide, Muscle release, Body scan; framed as pleasant, not effortful |
| Stimulus control | Part of CBT for insomnia, the best-evidenced approach (Edinger 2021; Furukawa 2024) | Night reset, pointing to a clinician for long-running problems |
| Clock watching | Increases worry and time to fall asleep (Tang 2007) | No time or countdown at night |
| Noise | Very low evidence; pink noise reduced REM in a 2026 lab study (Riedy 2021; Basner 2026) | Hush as low-volume masking with a timer and an earplug tip |
| Regularity | Sleep regularity predicts health outcomes more strongly than duration in large cohorts (Phillips 2017; Windred 2024); social jetlag is associated with worse outcomes (Wittmann 2006; Roenneberg 2012) | Keel score, social jetlag, Planned keel in the week editor |
| Naps | 10–20 min are best; 30 min brings grogginess first (Brooks & Lack 2006) | Catnap with a 30-min warning |

## 3. Decision

**Ordered core features (built):** Surfacing alarm (a restless-moment alarm with a hard wake time), Night steps (the
wind-down module: timed checklist, tracking, evidence-graded suggestions, guided tools), Week plan (independent
morning and evening plans per weekday, plus one-off Day off / Just this once / Pause).

**Added from the research (built):**

1. **Wake landing**: grades where the alarm rang on the Garmin hypnogram once the night syncs, and tunes the restless
   trigger within tight bounds. Unique; turns a weakly evidenced feature into something the user can inspect.
2. **Morning feel**: a one-tap rating (Foggy to Bright) after the alarm and in the morning review; the outcome that
   matters for a wellness tool.
3. **Debt preview**: shows how a planned wake time and bedtime move tomorrow's sleep debt while you edit them.
4. **Keel**: a 0–100 regularity score, social jetlag and a Planned keel for the week plan.
5. **Marks**: evening tags with honest with/without statistics (sample sizes, bootstrap intervals, "not what caused
   it") and an odd-night prompt.
6. **Drop anchor**: an "I'm settling in" tap that starts the no-clock night, measures how long you take to fall
   asleep once Garmin data arrives, and personalises that latency.
7. **Hush**: generated white, pink or brown noise with a fade-out timer, no audio files and no downloads.
8. **Catnap**: a power-nap timer through the same alarm engine, with timing advice from the energy schedule.
9. **Alarm check**: a readiness self-test and "Test ring in 1 minute", because missed alarms are the most damaging
   complaint in this category.

**Deferred:** weekly recap, gradual schedule-shift planner, Quick Settings tile, chronotype labels, custom marks, a
local sound-file picker, a Do Not Disturb bedtime rule, a debt-aware bedtime mode, the cognitive-shuffle tool.

**Rejected:** overnight microphone recording and snore analysis (privacy, battery), cloud AI coaches, smart bulbs and
live watch streaming (network), narrated story libraries (licensing, downloads), binaural or "brainwave" audio
(pseudoscience), dismissal tasks (clash with the calm tone, accessibility), composite sleep scores and "readiness"
(opaque, medical-adjacent), exact 90-minute cycle calculators (cycles vary too much), all-night sensing by default
(battery), anything written to Health Connect.

**Naming.** Every new name is original (Surfacing alarm, Night steps, Week plan, Drop anchor, Wake landing, Morning
feel, Keel, Marks, Hush, Catnap, Slow tide, Tomorrow list, Night reset, Alarm check). Competitor terms such as smart
wake, wake-up window or phase, sleep notes, wake-up mood, sunrise alarm, soundscapes, missions and readiness are
avoided. Several existing Sleep Saver terms (Energy schedule, Melatonin window, Wake zone, Grogginess, Energy
potential) closely mirror another app's wording; renaming them is left to the owner.
