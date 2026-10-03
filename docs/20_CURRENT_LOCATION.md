# Current location and navigator preparation

## Ownership and contracts

`core:location` owns `LocationService.observe(LocationRequest)`, platform Metro bindings, native permission launchers, and a shared location widget ViewModel. It has no renderer, domain, or feature dependency. `LocationFix` contains WGS 84 latitude/longitude, horizontal accuracy in meters, acquisition time in epoch milliseconds, and optional speed in meters/second and course in degrees clockwise from true north. Course describes movement, not device compass heading.

Each collection creates a continuous native subscription; cancellation releases its listener/delegate. Consumers must collect on the main dispatcher for iOS manager/run-loop ownership. `LocationRequest` exposes an Android minimum update interval (default 1 second) and a native minimum distance (default zero). These are sensor delivery hints, not a guaranteed cadence; iOS Core Location determines its own update timing. Future navigator mode can reuse the same continuous contract and choose its distance/interval policy without changing the renderer or map tile sources. Multiple collectors own separate subscriptions; a future application-wide navigator session should own one collection and distribute its latest fixes to presentation consumers.

Platform binding files explicitly import Metro annotations to keep contribution scope resolution tied to `bes.max.bmaps.core.di.AppScope`; wildcard Metro imports produced a contribution hint for Metro’s own `AppScope` in the observed Android compilation.

Android subscribes continuously to `LocationManager.GPS_PROVIDER` and available platform fused/network providers, with no Google Play services SDK dependency. It requests a current fix and reads fresh cached fixes from enabled providers when attaching. The GPS subscription remains active when connectivity is absent; additional providers improve initial acquisition when satellite reception is poor. Older fixes are rejected, and a worse fallback does not replace an accepted GPS fix younger than 10 seconds. Android measures fix age with `elapsedRealtimeNanos` and maps it into the shared epoch timestamp so a device wall-clock offset does not incorrectly discard a newly acquired position. Both coarse and fine permissions are declared/requested together; the GPS stream needs precise permission. Approximate-only access is reported with an actionable message. A device with no supported location provider is reported as unavailable. iOS uses Core Location with best desired accuracy and When In Use authorization; the OS chooses available positioning sources. Reduced accuracy is accepted with the reported accuracy circle. The builder centers on the first fresh, valid position and displays its reported accuracy; there is no arbitrary accuracy threshold that can leave an otherwise located map showing the world.

Native API references: [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager), [Android permissions](https://developer.android.com/develop/sensors-and-location/location/permissions), and [Apple Core Location](https://developer.apple.com/documentation/corelocation/getting-the-current-location-of-a-device).

## Presentation and lifecycle

The widget ViewModel exposes one immutable `LocationState` and Channel-backed Center/Message events. Presentation validates coordinate ranges, accuracy, freshness, and ordering. Only fixes no older than 30 seconds and no more than 5 seconds in the future are accepted. Older-than-current fixes are ignored. A once-per-second freshness check removes expired dots rather than leaving them displayed as live positions. Device wall-clock time is used with native epoch timestamps; unusual system clock changes can temporarily reject fixes until the clock and sensor timestamps agree.

`LocationTracking` requests foreground permission when entering a map and collects only while the map navigation entry is resumed. Leaving the map, opening another screen, or backgrounding cancels the subscription and clears live state. The permission bridge refreshes the subscription after authorization changes; returning from Settings starts a new subscription. Tapping My location after denial opens app settings, and disabled location services open the native location settings. Pending recenter requests wait for a fresh fix and are cancelled by map gestures or leaving the foreground. Sensor failure, denied permission, disabled services, stale position, invalid fixes, and waiting states never block map use. Both maps show a persistent status notice while a position is unavailable, with progress and Retry/Settings actions. After 30 seconds without a valid initial fix, a timeout notice is shown while native subscriptions continue so a later fix can still recover. The offline viewer also shows a persistent notice when a fresh position is outside package coverage. Android diagnostics use the `BmapsLocation` Logcat tag for lifecycle, provider registration, acquisition age, accuracy, validation, and exceptions; coordinates are omitted. Camera rejections report a separate localized message. Fixture maps do not activate location.

Features own camera behavior:

- The builder centers once on a fresh fix at zoom 13, clamped to provider/source and renderer limits. A gesture, zoom button, area selection, or explicit My location action ends initial auto-focus. Fresh location updates afterward only move the dot. An explicit recenter keeps the current zoom after the initial view has been established or the user has interacted. A first My location request made while initial acquisition is pending also uses zoom 13; it does not cancel initial focus and then preserve the world zoom. Recentering can replace a regional online pyramid when necessary.
- The offline viewer keeps its existing package viewport. My location preserves zoom and checks the geographic package bounds. Dateline-split packages switch to the covered region before moving the camera. Positions outside coverage show a message and preserve the viewport. Location is a transient overlay, never a saved annotation or a package metadata change.

`core:map-engine` owns geographic marker/accuracy overlay construction, including geodesic circle sampling, dateline unwrapping, tile-rectangle clipping, and a noninteractive accuracy path. It does not know about permission, sensor, or tracking state. Location updates use the existing overlay registry, so tile-source sessions are retained.

## Future navigator mode

The implemented stream already updates the marker as the user moves. Navigator mode will add explicit session ownership, camera-follow state, gesture behavior while following, and any route/guidance UI. Speed and course are optional and must never be assumed available or precise enough for guidance. Background navigation will require a separate lifecycle policy, platform authorization/capability work, and Android foreground-service ownership; no background permission, iOS location background mode, route recording, or navigation UI is added here.

## User verification

Builds, automated tests, and native device testing are left to the user. No app builds or tests were run for this increment.

Suggested acceptance scenarios on Android and iOS:

- Grant precise/When In Use permission, verify builder focus at roughly source level 13, and verify live dot/accuracy updates on both map types.
- Pan, zoom, or begin area selection before the first fix; verify a later fix does not reset the viewport. Reenter/resume an existing builder entry and verify its view is retained.
- Disable internet and verify the location dot on a downloaded map using a physical GPS-capable device. A fresh satellite fix may take longer without network assistance.
- Deny or revoke access, grant Android approximate-only permission, disable services, and test recovery through Settings. Confirm maps remain usable throughout.
- Test movement, a stale fix, a device without a supported provider, repeated background/foreground changes, and navigation away from the map. Confirm native subscriptions stop and resume correctly.
- Check offline positions inside/outside actual coverage, both regions of a dateline-crossing package, accuracy circles near package edges, and coexistence with annotation editing.
- Verify provider limits and different tile sizes/layouts constrain initial zoom appropriately. Inspect battery use and map responsiveness on physical devices before treating native acceptance as complete.

Android recovery acceptance also covers indoor acquisition with Wi-Fi available, fresh cached startup, stale cache rejection, device wall-clock changes, a 30-second no-fix timeout, provider enable/disable events, and Logcat diagnostics. Native Android 16 acceptance remains with the user; no builds or tests were run for these recovery changes.

iOS diagnostics pass a scoped UTF-8 C string to `NSLog` with a fixed `%s` format. The scope retains the buffer until the synchronous logging call returns, and message contents are never interpreted as format directives. Passing a Kotlin string through C varargs with `%@` caused the reported `EXC_BAD_ACCESS` during the location authorization callback. iOS rebuild and runtime verification remain with the user, including permission changes and diagnostic messages containing percent signs or non-ASCII text.
