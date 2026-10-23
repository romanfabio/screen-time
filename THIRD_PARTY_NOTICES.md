# Third-party notices

Screen Time itself is released under the [MIT License](LICENSE). The release APK bundles the open-source
libraries below. Their licenses were checked in each library's published POM (Maven metadata) when version 1.0.0
was prepared; all of them are permissive and compatible with MIT.

## Libraries included in the app (runtime)

| Library | Used for | License |
|---|---|---|
| AndroidX (Activity, Core, Lifecycle, Navigation, Room, SQLite, Startup, WorkManager, Profile Installer, and their transitive AndroidX modules) | App framework, navigation, database, background work | Apache License 2.0 |
| Jetpack Compose (UI, Foundation, Material 3, Animation, Runtime, Graphics) | User interface | Apache License 2.0 |
| Kotlin standard library | Language runtime | Apache License 2.0 |
| kotlinx.coroutines (core, Android) | Asynchronous code | Apache License 2.0 |
| JSpecify annotations | Nullness annotations | Apache License 2.0 |
| Guava `listenablefuture` 1.0 | Empty marker artifact pulled in by AndroidX | Apache License 2.0 (part of Guava) |

Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>. These libraries are used unmodified. The app ships
no fonts, images, or other third-party assets: the launcher icon is an original vector drawing made for this
project, and all text is original.

## Build and test tooling (not shipped in the APK)

| Tool | License |
|---|---|
| Android Gradle Plugin, Gradle | Apache License 2.0 |
| Kotlin compiler and Compose compiler plugin, KSP | Apache License 2.0 |
| JUnit 4 | Eclipse Public License 1.0 |
| Robolectric | MIT License |
| kotlinx-coroutines-test, kotlin-test | Apache License 2.0 |

## Trademarks

"Android", "Google" and "Digital Wellbeing" are trademarks of Google LLC. This project is not affiliated with,
endorsed by, or sponsored by Google. "Digital Wellbeing" is mentioned only to describe what the numbers in this app
were compared against.
