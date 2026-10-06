# Sleep Saver

Android app (Kotlin, Compose) that turns Garmin sleep data from Health Connect into sleep debt and a daily
circadian energy schedule. Spec, formulas and constants: `docs/PLAN.md`.

## Build
- `./gradlew assembleDebug`, `./gradlew :core:domain:test`, `./gradlew testDebugUnitTest`
- SDK lives at `C:/Users/carco/AppData/Local/Android/Sdk` (`local.properties` is gitignored).
- AGP 9 with built-in Kotlin: never apply `org.jetbrains.kotlin.android`; Kotlin compiler options go through
  `tasks.withType<KotlinJvmCompile>`. Shared module config lives in `build-logic/convention`.

## Module rules
- `core:domain` is pure Kotlin/JVM (no Android imports). Every formula lives there; every tunable number is a field of
  `SleepConfig`. Domain tests use JUnit 5 + Truth and `testing/Fx` builders.
- `core:data`: Room, DataStore, Health Connect, sync, repositories (interfaces in `repository/Repositories.kt`).
- `core:ui`: theme (`SleepSaverTheme`, `SleepSaverTheme.colors`), shared components, time/duration formatting.
- `feature:*` depend only on `core:*`, never on each other. Cross-feature navigation goes through lambdas wired in `app`.
- Android module tests: JUnit 4 + Robolectric + Truth.

## Invariants
- `java.time` only. Store instants (UTC) plus the offset they were recorded in; derive local times from that offset.
- Never write to Health Connect. No network calls, no analytics. All data stays on device.
- User edits are separate rows (overrides) so raw data can always be restored.
- Original name, copy and visuals: don't reuse another sleep app's names or wording (e.g. no "Super" streak).
- Dark-only UI, one accent colour (tide teal), big glanceable numbers. One line in-app: wellness tool, not medical advice.
