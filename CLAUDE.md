# CLAUDE.md

Guidance for working in this repository. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before touching
`domain/`; it explains the rules behind every number.

## What this is

Android app (Kotlin, Jetpack Compose, Material 3, Room, WorkManager) that shows screen time per day and per app.
It does not record anything itself: it periodically copies Android's usage-event log (`UsageStatsManager`) into
its own Room database. Single module `:app`, package `io.github.romanfabio.screentime`, minSdk 28, target and
compile SDK 36, version 1.0.1. Android only, offline, distributed as a sideloaded APK (not on Google Play).

## Commands

```bash
./gradlew assembleDebug                 # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest             # all unit tests (JVM + Robolectric)
./gradlew assembleRelease               # R8-minified; signed only if keystore.properties exists
./gradlew lint                          # Android lint
```

- Gradle needs JDK 17+. On the maintainer's Windows machine `java` on PATH is Java 8, so set
  `JAVA_HOME` to Android Studio's `jbr` folder first. Shell is Git Bash: prefix `adb` commands that take device
  paths with `MSYS_NO_PATHCONV=1`.
- `local.properties` (`sdk.dir`), `keystore.properties` and `*.jks` are git-ignored. Never commit or print them, and
  never add signing secrets to build files.
- Test on a device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then
  `adb shell appops set io.github.romanfabio.screentime GET_USAGE_STATS allow` to grant Usage access.
- Settings → Diagnostics shows raw events and writes `files/raw_events.csv` (pull with `adb exec-out run-as
  <package> cat files/raw_events.csv`). Raw usage data is personal: never commit it.

## Architecture in one screen

```
data/source  UsageEventSource, AppInfoSource      (the only Android-API wrappers)
data/db      Room entities, UsageDao, migrations
data/repo    DashboardRepository                  (queries -> pure builders; export)
domain       Sessionizer, Aggregator, IngestUseCase, DashboardBuilder, AppDetailBuilder, Exporter
sync         SyncWorker + SyncScheduler           (periodic ~3 h, plus ingest on every app open)
ui           Compose screens + ViewModels; ui/components = shared widgets
```

`Sessionizer`, `Aggregator`, `DashboardBuilder`, `AppDetailBuilder` and `Exporter` are pure Kotlin: keep them free of
Android imports so they stay unit-testable. DI is the hand-written `AppContainer` in `App.kt`.

## Rules that are easy to break

- **No `INTERNET` permission, ever** (it is a headline privacy promise), and no analytics or crash reporters.
- **Changing how events are interpreted** (`Sessionizer`) requires: new unit tests with synthetic event streams,
  bumping `IngestUseCase.SESSIONIZER_VERSION` (existing installs re-read the log), and a check against a real device.
  Foreground is tracked per activity, not per package; STOPPED/PAUSED pairing and the screen-off reset exist for
  real-world reasons described in the architecture doc. Do not simplify them.
- **Changing `Aggregator` semantics** requires bumping `IngestUseCase.AGGREGATE_VERSION`.
- **Schema changes**: bump the Room version, write a `Migration`, commit the generated `app/schemas/*.json`. Never
  use destructive migration: history older than Android's ~10-day event log cannot be rebuilt.
- **Headline = apps + "Home screen"** (launchers grouped, `DashboardBuilder.HOME_PACKAGE`); the app's own package and
  system packages without a launcher icon are hidden. This matches Digital Wellbeing's total; see the architecture doc.
- **Strings**: every user-visible string goes in both `res/values/strings.xml` (English) and
  `res/values-it/strings.xml` (Italian). Durations use the language-neutral `2h 15m` format (`ui/Format.kt`).
- Charts are drawn with Compose Canvas (no chart library). Keep the UI minimal; do not add screens without a reason.
- Do not add dependencies lightly: each one ships in the APK and must be license-compatible with MIT. When adding or
  removing a runtime dependency update **both** `THIRD_PARTY_NOTICES.md` and `app/src/main/res/raw/third_party_libraries.txt`
  (shown in Settings → Open source licenses, together with the full Apache-2.0 text and this app's MIT text).
  `res/raw/license_app_mit.txt` must stay identical to `LICENSE` (a unit test checks it).
- Compose BOM / Navigation are pinned to versions that compile against SDK 36 (newer ones need SDK 37 and a newer
  Android Gradle Plugin). Check `gradle/libs.versions.toml` before bumping.

## Testing notes

- Unit tests live in `app/src/test`. `IngestUseCaseTest` uses Robolectric with a plain `Application`
  (`@Config(application = Application::class)`) because the real `ScreenTimeApplication` schedules WorkManager,
  which is not initialized under Robolectric.
- JUnit test methods that use `runBlocking` must return `Unit` (`runBlocking<Unit> { ... }`).

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `./gradlew testDebugUnitTest assembleRelease` with `keystore.properties` present.
3. Install on a device, run through onboarding, check the dashboard, then attach the APK to a GitHub release.
   Signing with a different key than earlier releases breaks in-place updates.
