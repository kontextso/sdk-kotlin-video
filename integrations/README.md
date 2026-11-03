# UI integrations

Optional modules that fit the SDK into a UI toolkit, so the app does not write the glue itself. The core SDK
(`so.kontext:video`) works with Android Views; these add nothing it cannot do, they only remove the steps that are
easy to get wrong.

| Folder | Published as | For |
|---|---|---|
| [`compose/`](compose) | `so.kontext:video-compose` | Jetpack Compose: `rememberVideoAds` and `VideoAdHost` |

Each one is registered in `settings.gradle.kts` as `:video-<name>` with its folder here, and compiles against the
oldest toolkit version it supports, so it never raises the app's own.
