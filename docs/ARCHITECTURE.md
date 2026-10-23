# Architecture

How Screen Time works, the rules behind its numbers, and why they are what they are. Read this before changing
anything in `domain/`.

## 1. The core idea: read, don't record

Android already logs which activity is in the foreground, when the screen turns on or off, and when the keyguard
(lock screen) is shown or hidden, for every app, all the time. `UsageStatsManager` exposes that log to an app that
holds the *Usage access* permission. The log is kept for only about 10 days.

So Screen Time does **not** run a tracking service. It periodically copies the system log into its own database
and computes everything from there:

```
Android (always logging)        Screen Time
┌──────────────────────┐ read  ┌────────────┐ derive ┌────────────┐
│ UsageEvents (~10 d)  │ ────▶ │ Sessionizer│ ─────▶ │ Room DB    │ ──▶ UI
└──────────────────────┘       └────────────┘        │ (forever)  │
                                    ▲                └────────────┘
        WorkManager every ~3 h, on every app open, and after reboot
```

Consequences: near-zero battery use, nothing to keep alive, survives reboots and battery savers (a sync that runs
late only delays the numbers), and no data loss as long as one sync happens within ~10 days. The two failure modes
are the user revoking Usage access (the app shows a banner) and *Force stop* (Android cancels all scheduled work
until the app is opened again).

## 2. Code layout (single Gradle module `:app`)

```
data/source   UsageEventSource (UsageStatsManager), AppInfoSource (PackageManager)
data/db       Room: entities, DAO, migrations
data/repo     DashboardRepository (queries -> pure builders, export)
domain        Sessionizer, Aggregator, IngestUseCase, DashboardBuilder, AppDetailBuilder, Exporter  (pure Kotlin
              except IngestUseCase's Room use)
sync          SyncWorker (WorkManager), SyncScheduler
ui            Compose screens: onboarding, dashboard, appdetail, settings, debug (Diagnostics), components
```

`Sessionizer`, `Aggregator`, `DashboardBuilder`, `AppDetailBuilder` and `Exporter` have no Android dependencies and
carry most of the unit tests. Dependency injection is a hand-written `AppContainer`.

## 3. Event interpretation (`Sessionizer`)

Relevant `UsageEvents.Event` types: `ACTIVITY_RESUMED` (1), `ACTIVITY_PAUSED` (2), `ACTIVITY_STOPPED` (23),
`SCREEN_INTERACTIVE` (15), `SCREEN_NON_INTERACTIVE` (16), `KEYGUARD_SHOWN` (17), `KEYGUARD_HIDDEN` (18),
`DEVICE_SHUTDOWN` (26), `DEVICE_STARTUP` (27), `NOTIFICATION_INTERRUPTION` (12).

- **Device state.** *Screen on* = screen interactive. *Unlocked* = screen on and keyguard not showing. If the
  keyguard is not shown again after a short screen-off (lock-delay setting), screen-on counts as unlocked but is
  not a new unlock. An *unlock* is a keyguard shown → hidden transition.
- **Foreground app.** The owner of the most recently resumed activity that has not been paused. Tracked **per
  activity, not per package**, because real apps pause one activity, resume another of the same app and only then
  report the first as stopped, and because a translucent overlay (Recents) can be resumed over an app that never
  pauses.
- **PAUSED / STOPPED.** Only `ACTIVITY_PAUSED` ends an activity. A `STOPPED` pairs with a `PAUSED` of the same class
  if that happened at most 2 s earlier (it belongs to the replaced instance and is ignored). Otherwise the system
  skipped the pause (app covered by Recents or a permission dialog) and the activity is gone.
- **Screen off** forgets every "resumed" activity: the system resumes the top activity again at wake-up, so leftovers
  can only be missing PAUSED events.
- **Sessions** only count while *unlocked*, so screen-off audio and lock-screen UI are excluded. Sessions of the same
  app less than 5 s apart are merged; the number of merged sessions is the number of "opens".
- **Holes.** A gap of more than 4 h between events while the screen is on closes everything at the last event seen;
  shutdown closes everything.
- **Notifications** per app come from `NOTIFICATION_INTERRUPTION`, which Android delivers to apps with usage access
  (no notification-listener permission is needed).

## 4. Ingestion (`IngestUseCase`) and storage

Tables: `app_session`, `device_interval` (`SCREEN_ON` / `UNLOCKED`), `unlock_event`, `notification_event`
(raw, derived rows), `hourly_app_usage` and `daily_device` (aggregates for the UI), `app_info` (labels and
classification cache), `sync_state` (key/value). Dates are stored as local ISO strings; timestamps as UTC millis.

A run is **idempotent**. The Sessionizer returns a *resume point*: the start of the current screen-on period (with
the keyguard and resumed-activity state from just before it), or the last event + 1 ms if the screen is off. The
next run deletes every raw row at or after that cursor and rewrites it, so anything still open is stored
provisionally (closed at "now") and corrected later. Aggregates are recomputed for every date touched.

Versioned rebuilds, in `sync_state`:

- `sessionizer_version`: when the interpretation rules change, bump `IngestUseCase.SESSIONIZER_VERSION`. The next run
  re-reads every event Android still has and keeps older rows untouched (Android no longer has their events).
- `aggregate_version`: same idea for `Aggregator` semantics (recomputes all days from raw rows).
- `app_info_version`: clears the classification cache so it is resolved again.

Schema changes need a Room `Migration` (`AppDatabase.MIGRATION_*`) and a new exported schema in `app/schemas/`.
Never use destructive migration: history older than Android's event log cannot be rebuilt.

## 5. What the numbers mean

- **Screen time (headline)** = sum of foreground time of all apps **including the "Home screen"** (all launchers
  grouped into one row), excluding this app and system background packages (SystemUI, keyboards) that have no
  launcher icon. This matches Digital Wellbeing's total; Digital Wellbeing does not list the launcher as an app
  but counts its time.
- **Phone unlocked** = time with the screen on and the keyguard hidden. Larger than the headline when the phone is
  unlocked with no activity events (e.g. video in a floating window).
- **Unlocks, notifications, first pickup, last use** as described above. First pickup and last use ignore sessions
  carried over from the previous or into the next day.
- **Comparison with the previous period** is shown only when the stored history covers the whole previous period.
- Days start at local midnight. Daylight-saving days (23/25 h) and half-hour time zones are handled and tested.

## 6. Validation against Digital Wellbeing

During development the daily total and per-app times were compared with Google's Digital Wellbeing on a real
device (Android 15, realme UI) over six full days. Final results: the headline was 0.4–3.7 % above Digital
Wellbeing's total, and per-app times matched to the minute for most apps. Comparing exposed the interpretation bugs
described in §3 (per-package tracking, stray STOPPED events, phantom sessions after a missed PAUSED). When changing
`Sessionizer`, re-validate on a real device: unit tests prove the rules, only real event logs prove the rules
match what Android actually emits.

Known differences: time with no activity events at all is attributed to nobody; this app is hidden from its own
list; Digital Wellbeing merges apps that share a label and counts overlapping floating-window time for two apps.

## 7. Background work and permissions

| Permission | Why |
|---|---|
| `PACKAGE_USAGE_STATS` | Read the system usage log (user grants *Usage access* in Settings) |
| `QUERY_ALL_PACKAGES` | Resolve names and icons of every installed app |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional: ask to exempt the app from battery optimization |

There is **no `INTERNET` permission**. WorkManager adds its own normal permissions (wake lock, network state, boot
completed, foreground-service type), none of which grants network access. `QUERY_ALL_PACKAGES` is restricted on
Google Play, which is one reason this app is distributed as an APK rather than through the Play Store.

`SyncScheduler` enqueues a unique periodic job (3 h, 1 h flex) in `Application.onCreate`; WorkManager restores it
after reboot. The UI also ingests every time the app returns to the foreground. `IngestUseCase.run()` is guarded by
a mutex so the worker and the UI never overlap.
