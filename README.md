# Health Disconnect

Health Disconnect is a Compose Android app for browsing health data from Health Connect,
rendering metric charts, and managing multiple chart views.

## Project Layout

- `app/src/main/java/...` application sources
- `app/src/test/java/...` local unit tests
- `app/src/test/java/.../screenshot/...` Roborazzi screenshot tests
- `app/src/androidTest/java/...` instrumentation tests

## Build and Test

- Build debug APK:
  - `./gradlew assembleDebug`
- Run local unit tests:
  - `./gradlew :app:testDebugUnitTest`
- Run full local verification (unit + screenshot capture):
  - `./gradlew :app:allTests`
- Run only screenshot rendering:
  - `./gradlew :app:recordRoborazziDebug`

## Repo Conventions

Preferred test command for most local change validation:
- `./gradlew :app:allTests`

## Development Notes

- Use Robolectric-based tests for Compose and screenshot rendering.
- Use `HealthDataModel` for record extraction, normalization, and aggregation.
- View state lives in `DataViewAdapterViewModel` and `AppThemeViewModel`.

## Data & Storage Notes

- `Room` stores view metadata/configuration.
- `DataStore` stores app-level settings (theme mode and related preferences).
- Room and DataStore entries are initialized and migrated through `DataViewAdapterViewModel`
  startup pathways in the app process.

## Migrations

`Room` + `DataStore` compatibility expectations:
- `Room` is source of truth for view rows.
- `DataStore` is used for lightweight user preferences.
- The startup migration path from `DataStore` into Room is tested in existing integration tests.

## Related Commands

- View available Gradle tasks:
  - `./gradlew tasks`

