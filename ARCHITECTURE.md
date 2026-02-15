# Architecture Overview

## Layers

- **UI (Compose)**
  - `ui/*` screens are responsible for layout, interaction handlers, and rendering states.
  - `DataViewView` drives the chart/entries/list experience for a selected view.
- **View Models**
  - `DataViewAdapterViewModel` coordinates active views, metrics, and chart settings.
  - `AppThemeViewModel` handles theme persistence and restoration.
  - `PermissionsViewModel` owns Health Connect availability and permission query cadence.
- **Domain/Data**
  - `HealthDataModel` maps Health Connect records into normalized series and metrics.
  - Mapping and aggregation helpers provide unit-tested transforms before rendering.
- **Persistence**
  - `room/` stores view metadata and record selection/settings.
  - `DataStore` stores compact app preferences (theme mode and support flags).

## Data Flow

1. User changes UI controls (metric selection, chart options, refresh interactions).
2. View model updates local state and persists view config through `DataViewAdapter`.
3. `HealthDataModel` resolves selected metrics, queries `Room`/`DataStore` as needed, and emits
   reactive series streams.
4. Compose UI collects these streams and redraws charts, entries, and headers.

## Testing Boundaries

- Unit tests target:
  - ViewModel behavior (`DataViewAdapterViewModel`, `AppThemeViewModel`, `PermissionsViewModel`)
  - Domain math/extraction (`HealthDataModel*Test`)
  - Compose interaction and screenshot rendering (`*Test`)
- Integration points:
  - Data migrations and flow hydration are expected to be covered by dedicated integration suites.

## Current Tradeoffs / Tech Debt

- Mapping/normalization logic is centralized in `HealthDataModel`, but remaining cleanup is
  tracked in `TODO.md` under architecture tasks.
- `DataViewView` is large and due for decomposition by screen section.
- Room/DataStore cache and health record mapping lifecycles should be constrained by explicit
  invalidation policies as data windows change.

## Screenshot Notes

- Screenshots are generated with Roborazzi into:
  - `app/build/outputs/roborazzi/screens`
  - side-by-side dashboard in `app/build/reports/roborazzi/debug/index.html`
- If outputs appear stale in a browser, hard-refresh and clear cache.
- If a screenshot appears empty, rerun `./gradlew :app:recordRoborazziDebug` to regenerate.

