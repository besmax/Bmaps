---
type: "feature_specification_and_implementation_tracking"
project: "Bmaps"
purpose: "Define track navigation, route-free recording, telemetry, Apple Watch integration, and their implementation phases."
---

# Track mode and telemetry

Created: 2026-10-12. Updated: 2026-10-13. Status: T1 storage/import, T2 phone recording/recovery, T3 route navigation/recorded return, T4 viewer controls/overlays, and T5 history/review/export are implemented in source. The owner's earlier T0 build/launch report did not specify platform or automated-test results. Successful T1–T5 builds/tests and native acceptance remain unreported; the T4 compilation failure and source correction are recorded below; production Apple Watch remains final phase T7.

This document owns the feature specification and its temporary implementation tracking. Sections 1–7 describe product behavior and phase-specific limits; section 8 records contracts and implemented slices. Section 9 records current evidence. Section 10 contains the temporary implementation plan and decision register. Update behavior sections to describe the actual implementation as each slice lands. Once the complete feature is implemented and accepted, remove section 10, including the phase table, checklists, and resolved decision register. Retain the implementation description, contracts, limitations, and verification evidence; do not leave the completed plan in this document.

## 1. Scope

The first complete version combines navigation along complex imported tracks, recording without a predefined route, detailed telemetry, and basic Apple Watch integration. Intermediate phases are delivery slices, not reductions of that scope.

Supported activity profiles are hiking, off-road motorcycle riding, and road travel. Profiles select appropriate display fields and navigation/movement policies; they do not alter route geometry. Parameters require device evidence rather than assumed universal thresholds.

The feature is offline-first. Internet access is not required to record an activity or follow an available route. Raster coverage and DEM availability are separate from location, recording, and route geometry. Missing map coverage must not stop recording.

First-version scope:

- Start an activity with or without a predefined route, from the map.
- Follow complex routes in either direction and start from a selected intermediate section.
- Record the actual path, available speed, heart rate, and elevation, with measurement timestamps and provenance.
- Continue recording with the phone screen locked or the map screen closed, subject to native platform lifecycle restrictions.
- Recover committed data after interruption without inventing samples for the missing interval.
- Show compact expandable map controls, route progress, sustained-deviation alerts, and data-quality states.
- Use Apple Watch as the first wearable for available heart-rate measurements, compact activity display, alerts, and pause/resume commands.
- Review completed activities on a map with synchronized telemetry charts.
- Automatically retain a reusable route after route-free recording; optionally derive a new route from any activity.
- Export reusable route geometry and share recorded paths. T5 defines segmented geometry GPX and a separate versioned JSON telemetry export.

Deferred scope includes automatic road/trail routing, automatic detour generation, reliable turn-by-turn maneuver generation, automatic pause, autonomous navigation/recording on Apple Watch without an iPhone, and additional wearable ecosystems. A future wearable adapter must not require changing the activity model.

## 2. Product entities and ownership

| Entity | Meaning | Lifetime |
| --- | --- | --- |
| Route | Planned or reusable ordered geometry, with explicit segments and optional control points | Can be followed repeatedly |
| Activity | One hike or ride, with profile, lifecycle events, optional route snapshot, and progress | Survives screen and application recreation |
| Recorded path | Accepted measured positions grouped into continuous segments | Belongs to an activity |
| Telemetry sample | A timestamped measurement, source, units, and available quality metadata | Belongs to an activity; may be independent of position samples |
| Activity event | Start, pause/resume, finish, control point, source loss/recovery, or interruption | Preserves the activity timeline |

An activity with a route retains an immutable route snapshot and selected direction. Editing or deleting the original route must not rewrite an active or historical activity.

Routes belong to a map package: manually drawn lines remain in its annotation database, and imported/derived detailed routes occupy its registered `routes.json` auxiliary asset. They travel with package export/import and disappear on package deletion. Activities, recorded telemetry and frozen planned/return snapshots remain independent and readable after source deletion. The original T0–T5 application-owned route store is retained for legacy entries and internal derivation snapshots; it no longer receives new GPX imports. Source for the revised ownership is present as of 2026-10-16; native acceptance remains pending.

At most one phone-owned activity is active at a time. The phone owns activity state, navigation, and the primary recording. Apple Watch owns its sensor session and temporary telemetry buffer, but does not become an independent activity authority in the first version.

## 3. Map entry points and layout

### Start with a route

Both entry paths remain on the map:

1. Select a route line on the map or a route in the existing Map objects panel.
2. Open a small temporary route panel showing its name, length, and **Follow** action. Existing object editing remains available through object actions.
3. **Follow** opens a compact preparation panel over the map.
4. Select profile, direction, and starting section; inspect wearable status and any coverage/segment-gap issues.
5. Press **Start** in this preparation panel. It closes and the active-activity indicator appears.

Additional preparation details, including elevation profile and coverage overview, expand only on request. GPX segments belonging to one track should remain selectable as one route with explicit gaps; do not depend on the current annotation import producing unrelated lines.

### Start without a route

Add one **Track** control to the map, initially proposed at the lower left with safe insets and no overlap with existing controls. Final placement requires portrait/landscape layout acceptance.

The control opens a small menu with **Record track** and **Follow route**. **Record track** opens the same preparation panel without route fields. **Follow route** selects a route or uses the current selection. **Start** is in the preparation panel, not a separate permanent map button.

Map integration supports the offline viewer and, from T5, the same viewer without a raster package. Recording must survive leaving that viewer. The library action menu gains **Activities**, opening viewer-owned activity history through root navigation. History opens the same viewer in activity-only mode when no package is selected: recorded/planned vectors and position remain visible on a neutral map surface, without fabricated raster coverage. As of 2026-10-16, new recording starts from an opened map; the package-free viewer remains available for historical review and active-owner recovery. Opening history itself does not start recording. Online constructor navigation remains outside this first integration; it does not become a navigation screen. T4 implements live controls; T5 implements history, review and the package-free viewer in source, without establishing native acceptance.

### During an activity

- The Track control becomes a compact indicator in the same position, showing recording/paused state, elapsed time, and one primary metric.
- Tapping the indicator expands a small translucent panel with primary telemetry, route progress if applicable, **Pause/Resume**, and an overflow menu.
- The overflow menu provides finish, add control point, activity settings, and access to detailed telemetry. Finish requires confirmation.
- Keep two panel states initially: collapsed and expanded. Do not add one permanent floating widget per measurement.
- Use content-sized translucent surfaces, readable text, adequate touch targets, and the existing visual system. Do not imply that true backdrop blur is already implemented.
- Detailed charts open explicitly; they do not occupy the map during ordinary movement.
- Route warnings are temporary compact messages and status indicators. They must not repeatedly expand telemetry or hide the map.
- Reconcile the panel with the center coordinate/height readout, callouts, right-side controls, attribution, and system insets. Avoid stacking multiple expanded bottom panels.
- Adapt the expanded panel to the left side in landscape where space permits.
- The existing two-finger immersive gesture hides activity chrome without stopping recording, navigation, or wearable alerts. Restoring controls must remain accessible.

Camera following is an explicit presentation state. Panning switches to free inspection; a **Return to position** action restores following. Gestures must not silently pause the activity. Missing or unreliable fixes must not trigger camera jumps.

## 4. Route navigation behavior

Match position to the ordered route using previous progress, movement direction, candidate continuity, and reported location quality. Nearest geometric distance alone is insufficient at intersections, parallel trails, loops, or repeated sections.

Keep actual traveled distance separate from progress along the planned route and remaining route distance. An off-route detour can increase actual distance without advancing route progress. Route-free activities show no remaining-route or deviation fields.

Required behaviors:

- Forward and reverse traversal, with explicit intermediate-start selection.
- Preserve GPX segment gaps; do not invent connectors or include unknown connectors as known route distance.
- Maintain section continuity at self-intersections and repeated paths.
- Represent uncertain matching explicitly instead of jumping to an unrelated section.
- Allow manual correction of the selected section without altering recorded measurements.
- Detect sustained deviation using accuracy-aware tolerances and hysteresis. Tune alert policy by profile using real evidence.
- Notify on meaningful deviation transitions, with throttled repeats and recovery indication.
- Offer map inspection and temporary suppression for intentional off-route travel; restore guidance deliberately or after a clear rejoin prompt.
- Show distance/direction to route geometry without claiming a safe traversable connector or an automatically computed detour.
- Support control points and propose finish near the end; never finish automatically solely from proximity.
- Offer return along the already recorded path during route-free recording. Preserve gaps and do not describe unknown portions as continuous guidance.

Poor or conflicting position sources must be visible in navigation quality. A fix accepted for a current-location display is not automatically suitable for distance accumulation or route matching. Reuse the existing source-conflict protection; define activity-specific freshness, ordering, and quality gates in T0/T3.

## 5. Recording, telemetry, and lifecycle

Record available measurements with their acquisition time, source identity, canonical units, and quality metadata. Preserve original accepted measurements separately from computed statistics and render simplification. GPS speed and a derived positional speed must be distinguishable. Course is movement direction, not compass heading.

Heart rate, location, and elevation may arrive at different frequencies. Missing values are unknown, never zero. Delayed wearable measurements are inserted at acquisition time, not receipt time. Historical samples must not drive live navigation or be displayed as fresh telemetry.

GPS altitude, DEM terrain height, and wearable/barometric height have different provenance. Store available reference metadata and do not mix vertical references or calculate ascent from an unlabeled combined stream. Missing DEM coverage does not stop recording.

Store start/finish, manual pauses/resumes, control points, source availability transitions, and interruptions. Telemetry must be queryable by time and linked to the map where valid location is available. Data collection does not imply that a position exists for every heart-rate sample.

Lifecycle rules:

- The activity owner outlives map/ViewModel composition and owns one continuous location collection; map consumers observe its state instead of opening duplicate recording subscriptions.
- Android requires an appropriate foreground location service and visible native status. iOS requires an explicit background-location lifecycle and capabilities. Exact permissions and supported execution behavior must be verified before implementation.
- Screen lock, panel collapse, immersive mode, and navigation to another app screen do not end an activity.
- System termination or force quit is not a promise of uninterrupted execution. Reconcile durable state and mark missing intervals on recovery; never fabricate continuation.
- Commit bounded batches and lifecycle transitions durably. Define the maximum uncommitted-loss window, flush policy, and capacity failure behavior in T1.
- Reject duplicate/out-of-order positional contributions to distance. Preserve independently timestamped telemetry without repeatedly counting the same measurement.
- Location loss does not automatically pause the whole activity. Time and available heart rate continue; location-dependent speed/progress become unavailable.
- Start a new recorded segment after a meaningful location gap. Do not connect absent positions with a measured straight-line journey.
- Storage failure must be explicit. Do not continue presenting a healthy recording state after durable writes fail.
- Denial or loss of heart-rate access does not block location recording.

The owner confirmed on 2026-10-12 that manual pause stops recording the path, distance, heart rate, speed, elevation, and all other telemetry. Pause/resume lifecycle events and elapsed time remain tracked. Resume starts a new recorded segment. This is a fixed first-version rule, not a selectable pause policy. Live position may remain available for map inspection, but paused measurements do not enter the activity recording. Automatic pause is deferred.

## 6. Apple Watch

Apple Watch is the first wearable target, implemented only in final phase T7 after phone recording, navigation, history, and phone acceptance. The first version uses an iPhone-owned activity; standalone watch navigation and recording are deferred. Existing T0 headless probes remain inactive feasibility artifacts, without further watch implementation before T7.

Target watch behavior:

- Provide available heart-rate samples with acquisition timestamps and source identity.
- Show compact speed, heart rate, elapsed time, distance, remaining route distance, and next control point when available.
- Provide a haptic deviation alert without repeated vibration for every measurement.
- Send pause/resume commands and show confirmation only after the phone acknowledges the applied state.
- Show stale phone/navigation data and undelivered commands explicitly during disconnection.
- Buffer available telemetry locally and reconcile it after reconnection, without duplication or changing acquisition timestamps.
- Phone recording continues independently when the watch disconnects.

Candidate Apple integration uses HealthKit workout sessions/live measurements and workout mirroring where appropriate, with Watch Connectivity for relevant state/transfer needs. These are integration candidates, not a guarantee of continuous delivery or support for every profile. Reading accumulated health history is not a substitute for live heart-rate collection.

References: [Apple multidevice workout sample](https://developer.apple.com/documentation/healthkit/building-a-multidevice-workout-app), [HKWorkoutSession](https://developer.apple.com/documentation/healthkit/hkworkoutsession), and [Watch Connectivity](https://developer.apple.com/documentation/watchconnectivity/wcsession).

T0 records the API-level platform assessment and explicit capability limits. T7 owns watch target integration and physical verification of supported OS/device combinations, authorization, background behavior, workout-session conflicts, reconnection, and live heart rate for each profile. Do not classify a motorized journey as a physical workout merely to obtain execution privileges or invent exercise metrics.

The owner approved SwiftUI only for the Apple Watch companion's watchOS UI on 2026-10-12. Android/iOS UI remains Compose, and AGENTS.md/document 2 record that scoped exception. The official platform matrix lists watchOS for Kotlin, but not for Compose UI. T0's headless Swift probes do not change production Xcode targets; the SwiftUI watch target is T7 work. See section 8.5 for the API assessment and actual evidence.

## 7. Completion, review, and reusable routes

Finishing durably closes the activity and sensor ownership before presenting its summary. Retries/recovery must not create duplicate activities or reusable routes.

The summary includes planned and actual geometry, measured distance, elapsed/recording/moving times, available speed/elevation/heart-rate statistics, control points, pauses, and gaps. Map selection and chart selection refer to the same timeline. Missing intervals remain visible and must not be interpolated as recorded facts.

A route-free activity automatically creates a reusable route from its accepted path segments. Naming can default to date/time; renaming is optional. A degenerate activity without usable geometry remains a valid history entry but must not create an invalid navigable route. Deriving a route from a routed activity is an explicit action.

Reusable geometry retains gaps. Display simplification must not overwrite the recording. Route export and activity export must disclose what is included; exporting geometry alone must not claim to include heart-rate telemetry.

## 8. Architecture constraints

Existing architecture and module ownership remain authoritative until updated with concrete implementation decisions.

- `domain:activity` owns activity/route/telemetry contracts, independent validation, and pure lifecycle policy. T1–T3 implement repository adapters, statistics/session ownership and route navigation in this module.
- Native sensors, HealthKit/Watch Connectivity, platform lifecycle machinery, and low-level IO belong in core/platform infrastructure without domain imports.
- Domain repository adapters can compose core storage/database APIs, following the existing repository pattern.
- Application-level catalog/history metadata belongs in Room KMP as a repairable projection. Independent SQLite stores in `core:storage` hold activity state/events/samples and reusable route geometry. Package-local annotations are unaffected.
- Viewer map controls and state belong to viewer-owned capability packages. History placement/ownership must not introduce feature-to-feature dependencies.
- All display state maps to truthful immutable presentation models. Extract panel/list/item components and give children only the fields they need.
- Presentation invokes input validation independently; structural use cases retain a single responsibility.
- `:shared` assembles DI and native entry points only; it must not own activity policy or recording logic.
- Build configuration uses convention plugins and catalog dependencies. Register actual new modules in document 3 when introduced.
- Coordinate rendering and geographic primitives reuse `core:map-engine`; route/session policy stays outside the renderer.
- Activity deletion and export explicitly cover sensitive route/health data and associated buffers. Coordinate or heart-rate logging must not be added as routine diagnostics.

### 8.1 Implemented domain contracts

`domain/activity` applies the existing Android/KMP and serialization conventions, depends on renderer-independent coordinates and catalog serialization/coroutines, and creates no framework, DI binding, native permission, screen, or service. It is registered in settings and document 3.

| Source | Contract / implemented behavior |
| --- | --- |
| `ActivityModels.kt` | Segmented route vertices/control points, route revision snapshot, direction/start edge, profile, clock anchors, activity state/revisions, events, durations, and uncertain route progress |
| `ActivityTelemetry.kt` | Typed position/speed/heart-rate/elevation/course measurements, source/phase/acquisition/receipt metadata, quality policy, and resource bounds |
| `ActivityLifecycle.kt` | Pure transition table with explicit interruption/resume, finalization boundary, and idempotent repeated actions |
| `ActivityContracts.kt` | Session owner, atomic repository commit, route derivation, custom keyset history/sample/event queries, and typed failures; T2 implements the phone session |
| `ActivityValidation.kt` | Independently callable input/schema/geometry/sample/query/quality validation; not embedded in structural use cases |
| `WearableProtocol.kt` | Version-1 snapshots, commands/acknowledgments, bounded timestamped heart-rate batches, sample acknowledgments, and independent inbound validation |

Domain collections are ordinary data contracts, not Compose-stable display values. Repository/session owners must take owned copies of nested input collections before retaining a snapshot. Feature presentation must map these contracts into immutable display values as usual.

The route snapshot carries ordered segments and `RoutePosition(segmentIndex, edgeIndex, fraction)`; reverse travel changes interpretation, not stored geometry. Segment identifiers and control-point identifiers must be unique within the route. Adjacent repeated positions are rejected after import normalization; non-adjacent repetition is allowed for loops. A valid segment has at least two positions. Gap lengths are not counted as measured route length.

### 8.2 Selected storage and consistency design

T1 implements two private spatial databases, outside package folders:

- `activities/activity.sqlite`: activity records, route snapshots, accepted position/non-positional samples, events, interval/segment boundaries, durable checkpoint, active-owner slot, and command deduplication/acknowledgments.
- `routes/routes.sqlite`: reusable route records, ordered segments/vertices, control points, route revisions, and derivation identity.

Room holds activity/route catalog metadata for custom history/filter queries, but never becomes the authority for raw spatial/health samples. No new foreign key cascades from a map package to an activity or reusable route. Schema versions begin at 1 and are independent of package schema versions. These databases do not join the `.bmaps` inventory or the package disk-size calculation.

Use bundled SQLite through core-owned primitive records and kotlinx-io path infrastructure. Initial policy is serialized transactions, `synchronous=FULL`, and the established DELETE journal mode; any measured change to WAL requires explicit concurrency/recovery documentation. The activity database's single active-owner slot and activity creation commit atomically, so two start requests cannot create two active activities. Room projection writes follow authoritative commits; startup reconciliation repairs missing/stale catalog rows using stored revisions.

`ActivityCommit` uses a compare-and-set `expectedRevision`; the next durable record revision increments by one. Record, samples, events, checkpoint, and applied-command identity commit together. Conflicting writes return `RevisionConflict`, not silent replacement. `(activityId, sourceId, sampleId)` deduplicates samples. Activity event sequences are strictly increasing. History order is start time descending/id ascending; sample order is acquisition epoch/source ID/sample ID ascending; cursors use those exact tuples. Queries bound each page to at most 500 records, without generic paging libraries.

Lifecycle control has a separate `controlRevision`, incremented only for control changes, not periodic telemetry commits. Wearable pause/resume compares this revision, preventing normal sample writes from constantly invalidating commands. `sessionGeneration` changes on recovery/resume from interruption and protects commands from a previous recorder instance.

Transaction caps are 256 samples/events and 256 KiB of encoded input. T2 flushes before its smaller buffer exceeds 128 samples or 128 KiB of encoded samples, bounds quality-event buffering, and schedules commits once per second. Start, pause, resume, finish, and error/interruption transitions flush immediately. The one-second interval is a scheduling target, not a guarantee while the OS suspends execution; only committed batches are durable. Capacity/IO failure ends healthy recording state, preserves committed data, and requires explicit recovery. Never acknowledge wearable samples before their transaction commits.

Finish first records `FINALIZING` and its stop boundary, releases native recording ownership, then derives a route if required. Derivation uses the predetermined route ID and activity ID as a unique key in the route store. A retry returns the existing derived route rather than creating another. Completion records that link and `COMPLETED`; finalization can resume on startup without resuming sensor recording. There is no claimed cross-database atomic transaction. An interrupted catalog/link update is reconciled from derivation identity. Deleting an activity does not delete an independently saved reusable route; removing associated pending wearable samples must prevent late delivery from recreating the deleted activity.

Detailed GPX limits are 32 MB UTF-8 input, at most 100 resulting routes, 200,000 vertices, 2,000 segments and 2,000 control points across the import document, with the same geometry caps on each stored route. The streamed importer retains XML depth/entity protections and counts limits before allocating the complete geometry. Existing annotation limits remain unchanged. Oversized routes fail explicitly; no implicit truncation or simplification. GPX export preserves track segments; health telemetry is an explicit separate versioned JSON payload rather than undocumented vendor GPX extensions. The T1 storage/import codecs and T5 streamed GPX/JSON export codecs are implemented in source; native acceptance is pending.

### 8.3 Start, clocks, lifecycle, and recovery

Start requires a visible user action, an available native location service, and appropriate precise location permission. Optional heart-rate denial does not block start. A missing initial fix is allowed: the activity begins in RECORDING with unavailable position/progress and no invented path. The session invokes structural validation; T4 preparation presentation must independently validate input before calling the owner.

| State | Pause | Resume | Finish | Process interruption | Complete finalization |
| --- | --- | --- | --- | --- | --- |
| RECORDING | PAUSED | Same state | FINALIZING | INTERRUPTED | Rejected |
| PAUSED | Same state | RECORDING | FINALIZING | INTERRUPTED | Rejected |
| INTERRUPTED | Rejected | RECORDING with new generation/segment | FINALIZING | Same state | Rejected |
| FINALIZING | Rejected | Rejected | Same state | Retain finalization | COMPLETED |
| COMPLETED | Rejected | Rejected | Rejected | Retain completion | Same state |

Repeated command identity returns its stored result and never creates another lifecycle event. Internal finalization completion is not a user/wearable command. Source loss, permission loss after start, or a storage failure is recorded explicitly; GPS loss alone is not a manual pause. Recorder-ownership loss or inability to continue durable writes produces an interruption. Recovery of source quality within the same running owner may resume positional data with a new segment.

`ActivityTime` stores epoch milliseconds, optional monotonic milliseconds, and clock continuity identity. Phone-owned lifecycle events require a monotonic anchor. Wall-clock changes do not alter accumulated durations inside one continuity interval. Epoch supports inter-device/history display; remote HealthKit samples have no fabricated phone monotonic timestamp. Monotonic deltas are valid only within the same continuity identity. Across a reboot/unknown discontinuity, an interruption remains explicit; elapsed interruption estimates based on epoch are not added to moving/recording time. Resume requires a visible explicit action after process recovery; never restart tracking solely on boot or a background launch.

Elapsed time includes pause/interruption and is accumulated from monotonic owned intervals, rather than blindly subtracting start/finish wall clocks. A cross-continuity interruption estimate sets `elapsedIsEstimated`; an unknowable interval remains visibly uncertain. Recording time accumulates unpaused owned intervals; moving time is a subset supported by accepted movement measurements, never filled through unknown gaps. Durations must remain nonnegative and moving <= recording <= elapsed. A paused activity must not add path/distance or retain any telemetry acquired in its paused interval. Domain sample validation rejects PAUSED measurements of every kind. In T7 the sensor adapter follows the acknowledged phone pause/resume state; no paused heart rate is buffered for activity recording. Sensor shutdown latency or lost connectivity must not bypass the acquisition-time pause boundary.

Default quality candidates in `ActivityQualityPolicy` are position age <= 5 seconds, future allowance <= 2 seconds, accuracy <= 50 m, segment break after a 10-second position gap, and live-heart-rate age <= 15 seconds. They are application defaults awaiting device calibration, not sensor guarantees. Source conflict makes a position ineligible for path accumulation/matching until trust recovers. Position contributions are strictly chronological within a segment; a presentation correction to an older selected fix does not add backward distance. Cross-device clock uncertainty affects freshness, not acquisition timestamp rewriting.

T2 adds initial profile-specific movement hysteresis; T3 adds deviation policy. Fixture execution and device calibration remain pending; no physical speed, ascent, or warning threshold is claimed calibrated in T0. Preserve speed source, never differentiate noisy positions without a defined quality policy, and never merge unidentified elevation references. Negative elevation is valid; zero heart rate is invalid/missing.

### 8.4 Wearable protocol and retention

Packets are UTF-8 JSON, version 1, capped at 65,536 bytes before decoding. Enum identifiers match serialized Kotlin names. Heart-rate batches contain at most 128 samples. Unknown protocol versions are rejected rather than treated as commands. Transport receipt is not durable ingestion.

Commands are PAUSE/RESUME, with `commandId`, activity ID, session generation, `expectedControlRevision`, issue/expiry epoch milliseconds, and a maximum five-second validity window. Do not queue offline control commands for eventual replay. The phone verifies matching active owner, current control revision, expiry/clock uncertainty, and valid transition, commits the result, then acknowledges APPLIED or an explicit rejection. A duplicate already-applied command returns its original durable acknowledgment even if its validity interval later expires.

Snapshots carry durable and control revisions, state, generated time, elapsed/distance, and optional speed with its acquisition timestamp, remaining distance, match quality, and next control-point name. Missing measurements remain null; stale snapshots cannot display apparently live guidance. Navigation haptics derive from distinct phone-issued alert transitions, not every coordinate callback; alert wire details are added with T3/T7.

Health batches include original activity/generation, source/device session, stable sample ID, measurement start/end epoch, and beats/minute. The activity interval timeline determines phase at acquisition time, using half-open intervals: pause begins inclusively at its event timestamp and resume ends the paused interval exclusively. Measurements acquired in a paused interval are discarded even if delivered after resume; a pre-pause sample delivered during pause can still be retained. For a HealthKit measurement interval overlapping a paused interval, discard the entire sample rather than inventing a partial measurement. A delayed batch from an older generation may otherwise be ingested into its original authorized activity, including a completed activity, without reopening it. Session generation rejects stale commands, not all historical telemetry. Late telemetry updates statistics/revisions but not path geometry or original finish time.

Acknowledgments distinguish durably recorded `sampleIds` from `ignoredSampleIds` discarded because their acquisition interval is paused. A discard acknowledgment follows the durable pause-boundary decision and lets the watch release that buffered sample without saving its health value. Ignored IDs never become recorded telemetry. Watch retention is bounded to 24 hours and 16 MB, with at most one bounded transfer in flight. Reconnection queries/acknowledges chunks rather than transferring the entire backlog. Expiry/capacity loss generates a telemetry-gap status/event; no silent unlimited buffering or fabricated replacement samples. Domain limit/packet contracts exist now; buffer, expiry scheduler, and transport retry implementation are T7 work.

### 8.5 Platform feasibility assessment and source probe

API/documentation assessment date: 2026-10-12. Source/header inspection is separate from compilation and physical testing.

| Platform / scenario | Selected direction and evidence | Remaining verification |
| --- | --- | --- |
| Android | Keep project minSdk 30; start from a resumed user action using a location foreground service, fine location, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`; no initial background-location permission or boot auto-resume | T2 source present; user-run API-level/device, notification and interruption acceptance |
| iPhone | Keep current deployment minimum iOS 18.2; foreground-started Core Location with location background mode and `allowsBackgroundLocationUpdates`; When In Use authorization and visible background-location status; disable automatic activity pauses while recording | T2 source/capability changes present; physical locked-screen/background evidence pending; no force-quit guarantee |
| Apple Watch hiking | Proposed minimum watchOS 10; real outdoor hiking workout with HealthKit live builder and mirrored workout data. No hardware model whitelist is asserted beyond platform/API availability | T7 physical paired device, read denial/no samples, competing workout, screen-off, full telemetry pause, battery and disconnection |
| Apple Watch motorized profiles | Compact state/commands can use Watch Connectivity when the watch app is reachable; passive authorized heart-rate history is opportunistic, not a continuous sensor subscription | No supported dedicated continuous motorized heart-rate/session policy established; background alert/display reliability without a workout also remains unresolved |
| watchOS UI | Owner-approved SwiftUI companion target; Android/iOS retain Compose | Target/toolchain and physical verification in T7 |

A missing/denied heart-rate read cannot be reliably diagnosed just from a successful HealthKit authorization request; authorization completion does not prove read access or live samples. Display unavailable telemetry without claiming a particular denial cause. Apple Watch allows one active workout; a competing workout can end this sensor session, but must not finish the phone activity. Motorcycle/road profiles must not start a pretend hiking/cycling/Other workout to gain background execution. This leaves a known first-version capability risk, not a silently removed requirement.

Local Xcode SDK headers confirm the mirror APIs are available from iOS 17/watchOS 10; the existing app's iOS 18.2 minimum therefore needs no downgrade/upgrade for mirroring. `sendToRemoteWorkoutSession` is intended for current workout state and has a 100 KB per 10-second budget in the inspected header. Production transport must bound total traffic, not merely packet size; backlog transfer cannot indiscriminately use the live mirror channel. A dedicated phone-owned location service is still required independently of workout mirroring.

Original headless probe sources are in `prototypes/apple-watch/`:

- `ProbeProtocol.swift`: Codable heart-rate batch matching the Kotlin version-1 field names and explicit probe failures.
- `WatchWorkoutProbe.swift`: HealthKit authorization, real hiking session/live builder, source-time most-recent heart-rate statistics, mirrored transmission, receive callback, cancellation-token guarding, and cleanup/discard without saving an exercise record.
- `PhoneWorkoutProbe.swift`: mirrored-session handler installation, retained delegate/session, bounded matching-activity batch decode, and reverse data send callback. HealthKit state notifications remain sensor observations, never automatic Bmaps lifecycle authority.

The probe is not part of production Gradle/Xcode targets and has no UI, durable outbox, exhaustive raw-sample capture, automatic remote launch, or production command processor. Most-recent builder statistics can coalesce measurements; exhaustive recording must be addressed in T7. It establishes source integration shape only and has not been compiled or executed.

For user-run validation, add the shared protocol and respective platform source to paired temporary iPhone/watchOS targets, enable HealthKit and the appropriate workout-processing capability, provide localized health read/update purpose strings, and install with matching companion identifiers/signing. Keep the probe objects alive, install the phone handler before starting `startHiking` on the watch with matching activity/generation IDs, and observe callbacks without logging raw location or health values. End with `stop`/`uninstall`. Do not copy these prototype hooks into production app startup before the activity owner exists.

Primary sources reviewed:

- [JetBrains platform matrix](https://kotlinlang.org/docs/multiplatform/supported-platforms.html): Kotlin watchOS support and Compose UI platform list.
- [Android location foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#location): service type, permissions, and while-in-use start restrictions.
- [Core Location background updates](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates): foreground-started continuous updates and background indicator.
- [Apple multidevice workouts](https://developer.apple.com/videos/play/wwdc2023/10023/): primary Watch/mirrored iPhone roles and bidirectional data.
- [HealthKit authorization](https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data): fine-grained read/write access and purpose strings.
- [Apple Watch workout sessions](https://developer.apple.com/documentation/healthkit/hkworkoutsession): session ownership/conflicts and optional source behavior.

### 8.6 Presentation and ownership decisions for later phases

`feature:viewer` owns `activity.ui`/`activity.presentation` for map preparation/live controls and `history.ui`/`history.presentation` for review. Create these packages only when code lands. `feature:library` exposes an Activities callback; `:shared` wires history/viewer destinations and no feature imports another feature. An activity-only viewer has optional map-package context and remains available after package deletion. Reuse map-engine geometry/camera contracts without moving map constructor behavior into this workflow.

Collapsed primary metric is hiking pace and motorcycle/road speed; elapsed recording state is always visible. Expanded fields include speed/pace, available heart rate, actual distance, and remaining route distance when meaningful. Metric customization is deferred until the fixed presets/layout pass acceptance. Coordinates, DEM readout, callouts, and activity panels share a visibility/layout policy rather than independent overlapping expansions.

The T2 activity owner is application-scoped through Metro with platform service ownership. `core:location` supplies raw native observation and retains source selection/quality behavior, with T2 uptime/provenance/altitude metadata extensions. The map widget and owner use `SharedLocationService`; foreground maps release their consumer on lifecycle stop, while recording retains its background consumer. Native wearable infrastructure is a separate future core adapter, not a dependency on viewer or domain; source DTO mapping happens in the activity domain adapter.

### 8.7 T1 implementation — 2026-10-12

`LocalActivityStore` is application-scoped and serializes both activity and route repositories. The umbrella contributes interface bindings and graph entry points only. `ActivityFileStorage` receives the native application storage root: Android filesDir and iOS Application Support. The activity/route directories are siblings of `packages`, not members of any package inventory. Fixed paths and journal companions are checked for symlinks before opening.

`ActivityDatabase` and `ActivityRouteDatabase` expose primitive rows in core; JSON semantic mapping, validation, pause rules and route derivation stay in domain. Both spatial databases begin at schema version 1; a new empty version-0 database is initialized transactionally. Unsupported nonzero versions fail without destructive fallback. A nonempty unversioned database also fails instead of being overwritten. SQLite writable reopen recovers hot journals; first access and explicit reconciliation use quick/foreign-key checks. Cancellation before transaction commit rolls back; once SQLite commits, a catalog or directory-sync failure can still return a failure, and the next reconciliation repairs its projection.

Activity headers exclude the route selection payload. Frozen selections occupy `route_snapshots` once per activity; periodic commits update small headers, samples, events and checkpoint without rewriting that geometry. The store caches only the current owner's decoded snapshot and returns owned collection copies. Route JSON is bounded to 64 MB; activity headers and individual row payloads to 64 KiB. Read queries bound text before decoding. History/catalog pages return scalar summaries and never load all route geometries.

The checkpoint records the last event sequence, position epoch/monotonic/continuity anchor, segment ID and pending segment-break flag. New positions require strictly advancing measurement time within a known clock continuity; wall-clock rollback does not override a valid monotonic order. Pause/interruption/position-loss events and a >10-second position gap forbid reuse of the same path segment. Re-entering an already closed segment ID is rejected. Positions are read in durable insertion sequence for route derivation, independently of chart epoch ordering.

Pause eligibility uses durable lifecycle events plus any events in the pending atomic commit. Local measurements compare monotonic anchors when available; remote/unknown-continuity measurements use acquisition epoch, subject to later source-quality/clock policy. The repository accepts telemetry acquired in recording intervals only. A measurement falsely labeled RECORDING but acquired during a pause is rejected. Pre-pause non-positional data delivered after resume remains eligible. Position recording after finalization is rejected; late eligible non-positional data can update a completed history entry without reopening it. Wire ignored-sample acknowledgments and interval-overlap handling remain T7 work.

Compare-and-set protects both activity and route revisions. Activity control revisions change only with lifecycle state; generation changes only for explicit recovery from interruption. Initial create uses a stable content fingerprint; retrying the same create returns the existing record. Command-bearing commits retain fingerprint/result metadata, so identical retries return the original result without repeating events or samples. Same sample identity/content is deduplicated within/across batches; conflicting identity payloads fail and roll back. Batches accept at most 256 samples, 256 events and 256 KiB combined encoded header/event/sample input. T2 adds the recorder's one-second flush scheduler; every repository commit is synchronous/durable.

Room schema 4 adds `activity_catalog` and `activity_route_catalog`, without package foreign keys. The explicit 3-to-4 migration preserves package/job/preferences rows. Startup/explicit reconciliation scans authoritative headers and route summaries in 500-row pages, stamps projection generations, and prunes stale catalog entries only after a successful scan. Stale/missing Room rows do not delete spatial data. Generated schema 4 is now present after the owner's build attempt; static inspection confirms its version and both activity tables. Full review, successful builds and migration execution remain pending; no identity hash is hand-authored.

Activity deletion is refused while recording, paused or finalizing. Deleting completed/interrupted activities transactionally removes their samples/events/snapshot/checkpoint/command data and owner slot, and retains an ID tombstone so stale writes or repeated creation cannot resurrect them. Independently saved routes remain. Route deletion retains derivation identity/tombstones to prevent finalization retries recreating an explicitly deleted route. `secure_delete=ON` is configured; this is a SQLite policy, not a physical flash-erasure guarantee.

Route-free derivation is available for finalizing/completed activities. It reads bounded position pages, preserves segment boundaries, removes consecutive duplicate coordinates and omits non-navigable one-point segments. An activity without any valid segment returns no route. Geometry resource limits fail explicitly; no implicit truncation occurs. Activity ID uniquely binds its derived route across retries; independently updating/deleting an original planned route never changes the activity snapshot. T2 orchestrates automatic derivation and completion; T5 adds history/reuse UI.

`ActivityRouteGpx.decode(RawSource)` bridges a bounded strict UTF-8 stream into XMLUtil's catalog-pinned generic reader; it does not retain an XML tree or close the caller's source. `decode(String)` is a bounded convenience wrapper. Limits apply to the complete import: 32 MB, 100 routes, 200,000 source positions, 2,000 segments and 2,000 named control points; XML nesting is at most 64, elements at most 2,000,000, and attributes at most 128 per element. DTD/entity declarations, malformed UTF-8, unrelated root namespaces/versions, duplicate supported text properties, invalid coordinates, and degenerate segments reject the complete result. Import persistence uses one route-store transaction, so an ID/revision collision cannot partially save a batch.

Supported GPX mapping is `rte/rtept` to one route/segment and each `trk` to one route retaining all `trkseg` gaps. Route name/description/comment fallback, optional vertex elevation and explicitly zoned timestamps, and named route/track points are retained. Imported heights use `GPX_UNSPECIFIED` vertical reference; they are never silently labeled as a DEM datum. Consecutive duplicate positions keep the first vertex metadata. Top-level waypoints and vendor extensions are outside this route importer and ignored; the existing annotation importer remains the way to import standalone waypoints. Its 4 MB/1,000-vertex contract is unchanged.

`ActivityRouteAdapter.line` accepts already mapped line vertices without importing map-builder annotation models. `ActivityRoutePreview.create` makes a separate bounded overview geometry with source indices, preserving segment endpoints and control-point edge endpoints. Its deterministic sampling has no geometric error bound; it is an overview helper, not route-matching or measurement geometry. Navigation/storage continue using the complete route. UI wiring and zoom-dependent render policy belong to T4.

### 8.8 T2 implementation — 2026-10-12

`PhoneActivitySession` is the application-scoped phone owner, bound through Metro as `ActivitySession`. Android application creation and the existing iOS initialization entry point initialize recovery without starting recording. Start/control/recovery serialize with sensor ingestion and flushes in the owner's scope, so leaving a map or cancelling a caller does not cancel an accepted control transaction. Preparation/control UI and its independently validating ViewModel belong to T4; no map buttons are added here.

`SharedLocationService` distributes one native subscription to the map widget and recorder. Requests combine minimum interval/distance hints and enable background mode only for a recording consumer. Identical effective requests retain the subscription. Distribution preserves sequential updates rather than conflating them to a latest-state value; iOS forwards each supplied native location in a batch. Stopping map collection clears map presentation but retains the recorder; stopping the last consumer releases native listeners. Effective-policy changes and explicit map Retry/permission refresh restart the native stream and publish waiting/quality states.

Phone fixes carry native uptime, provider identity, optional altitude/vertical accuracy and speed accuracy. Android uses acquisition `elapsedRealtimeNanos`; iOS maps Core Location timestamp age to uptime. Map selection/freshness/order use uptime when available, retaining epoch fallback for older/test fixes. Activity continuity is a process-specific identity. Accepted points must be valid, at most five seconds old, no worse than 50 m accuracy, free of source conflict, strictly chronological and inside the current recording/quality boundary. Future phone acquisitions are rejected rather than counted before a pause boundary. A missing local uptime anchor can be mapped from epoch age; no phone anchor is invented for remote health samples.

Each qualified fix retains position and available valid speed, course and altitude with independent sample IDs, acquisition/receipt anchors and provider provenance. Altitude uses `PHONE_UNSPECIFIED`, rather than silently combining it with DEM heights. Missing speed stays missing. The initial recorder takes these channels together from qualified fixes; poor/conflicting fixes do not produce apparently trustworthy phone telemetry. Heart rate is explicitly unavailable until T7; there is no production HealthKit/BLE/watch producer.

The one-second scheduled flush commits samples, quality events, statistics and checkpoint together; thresholds flush earlier. Pending samples are bounded to 128/128 KiB; quality transitions flush before 120 pending events. Native callback and shared distribution queues are also bounded to 256 updates each. A native queue overflow or terminal subscription exception produces an explicit delivery-gap signal; the owner commits a gap/interruption and requires recovery instead of silently losing telemetry. Repository transaction caps remain independent. Start/control/interruption boundaries flush immediately. The interval is a scheduling target, not an OS suspension guarantee; only committed batches are durable. Write failure stops native ownership, clears uncommitted input and exposes `ActivityLiveState.failure` with the last durable record/statistics. A still-persisted RECORDING header does not indicate a healthy runtime after failure. Explicit recovery reconciles it to interruption when storage becomes available.

Pause persists its boundary and removes the recorder's sensor consumer. Late callbacks and cached acquisitions before resume are rejected. Pause adds elapsed time only: no path, speed, course, altitude or future health samples. Android retains its paused service notification; a separately visible map may retain its foreground consumer. iOS releases background location during pause and requires foreground resume. Resume/quality recovery create new segments. Quality/source-conflict/loss and long-gap events are explicit. GPS loss alone retains RECORDING with unavailable position; permission or native-owner loss interrupts recording.

Android owns a non-exported `location` foreground service, native foreground-service permissions, a low-importance ongoing notification with recording/paused status, an app-opening intent and Finish action. Start checks fine permission, enabled location and a resumed activity, and waits for service foreground promotion. A paused existing service can continue without creating another. `START_NOT_STICKY` and no boot receiver prevent auto-restart. Notification permission is declared but not required to record; T4 must offer a contextual Android 13+ request because denial limits notification visibility. The Application exposes graph-backed service/session properties only; business policy stays in `domain:activity`.

The iOS app declares location background mode and an updated usage explanation. Recording configures Core Location background updates/indicator and disables automatic pausing. The native host checks foreground state, services, When In Use/Always authorization and full accuracy without prompting or requesting Always. Reduced accuracy/authorization loss during recording interrupts the owner; ordinary map requests retain foreground cleanup and reported reduced accuracy. Physical locked-screen delivery remains unverified. API references: [Android foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android location service type](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [Apple authorization](https://developer.apple.com/documentation/corelocation/requesting-authorization-to-use-location-services), and [Apple background configuration](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates).

`ActivityRecord.statistics` persists elapsed/recording/moving/interrupted time and distance. Owned time uses monotonic deltas; pause adds no recording/motion, and cross-continuity downtime is estimated without filling motion. Movement uses consecutive points in one segment at most ten seconds apart and horizontal geodesic distance. Initial speed hysteresis starts hiking at 0.5 m/s and motorized profiles at 1 m/s; stop thresholds are half those values. Without native speed, displacement must exceed both 3 m and half the sum of accuracy radii. Displacements below 1 m do not count. These heuristics await device calibration; movement-filtered distance can differ from reusable-route geometry length. Moving time is capped by recording time; distance/time never bridge segments.

Recovery marks persisted RECORDING/PAUSED interrupted from their last committed `updatedAt`, a conservative ownership boundary. Downtime is outside recording/moving time. Explicit resume rotates generation and starts a segment. FINALIZING retries derivation/completion without sensors. Route-free finish uses deterministic `recorded-<digest of activity ID>` identity; insufficient geometry yields no reusable route. Finish acknowledgment is the durable FINALIZING transition; completion is exposed separately in session state. Stored command results retain `lastCommand` to reject an identical ID carrying a different payload.

Owned snapshots use persistent immutable collections. Periodic commits reuse unchanged trusted geometry while validating headers; new geometry receives full validation. This avoids copying/scanning 200k vertices every tick. Domain contracts are not annotated presentation models. Statistics/command payload additions have readable defaults in activity version 1 and require no spatial/Room schema increment.

### 8.9 T3 implementation — 2026-10-12

`RouteNavigationIndex` indexes the complete frozen route rather than preview geometry. It stores geodesic edge lengths, segment boundaries and route-order offsets in primitive arrays, and builds a balanced spatial hierarchy over unit-sphere bounds of entire minor great-circle arcs. This handles datelines, poles and long edges without creating connectors across route segments. Navigation rejects edges shorter than 1 mm and nearly antipodal edges, whose direction is undefined/ambiguous in this model; import/storage still preserve the original geometry. Index construction and matching run on the default dispatcher, serialized by the session owner. No timing/memory benchmark has been executed.

Search narrows by the current segment and an ordered progress window, then uses spatial bounds to project nearby candidates. It retains the closest projection per 10 m route-order group, preserving candidate diversity across nearby sections rather than letting dense vertices occupy every slot. At most 64 groups are retained; overflow is explicit uncertainty. Each search is budgeted to 4,096 visited nodes and projected edges. A fix can perform one local and one global availability search; exhausted budgets yield `UNCERTAIN/SEARCH_LIMIT`, not an arbitrary winner. Ranking includes route-order continuity, expected advance and travel-direction course when native speed is at least 1 m/s. Plausible alternatives separated by more than 25 route meters can yield `AMBIGUOUS_SECTION`; unrelated later loop/return sections cannot replace a reachable current section simply because they are nearer. Fixes outside the reachable section but near other route geometry report `CONTINUITY_LOST` and require correction.

`RouteNavigator` requires two coherent fixes after start, pause, manual correction, source loss or a recording-segment break. It checks phone acquisition/receipt continuity, freshness, conflict and accuracy independently of the recording gate: the position may still be recorded when its accuracy is unsuitable for navigation. Duplicate/backward acquisitions do not advance progress. Unavailable/uncertain positions expose no apparently current matched position or remaining distance. State retains its last confirmed anchor for conservative reacquisition.

The initial policies are application heuristics, not calibrated device guarantees:

| Profile | Navigation accuracy cap / base on-route tolerance | Base deviation tolerance | Search radius | Base forward / backward window | Maximum forward-window speed | Endpoint tolerance | Sustained deviation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Hiking | 15 m | 30 m | 200 m | 35 / 15 m | 12 m/s | 10 m | 8 s |
| Off-road motorcycle | 25 m | 55 m | 500 m | 90 / 30 m | 80 m/s | 20 m | 6 s |
| Road travel | 20 m | 45 m | 500 m | 100 / 30 m | 90 m/s | 20 m | 6 s |

The on-route corridor adds reported accuracy; deviation adds twice reported accuracy. The forward window adds at most five seconds of its profile speed allowance. Course opposing intended travel by more than 100 degrees reports uncertainty. Rejoin and endpoint confirmation hold for three seconds. Deviation reminders require at least 60 seconds since the previous alert and a fresh sustained off-route interval; an unknown prior alert clock uses a new 60-second owned interval. Uncertain/conflicting/poor/stale positions, manual pause and waiting at a route gap reset/suppress alert timers.

`RouteProgress` exposes known planned distance from the selected origin, completed/remaining route distance, remaining current-leg distance, next control point, explicit gap and endpoint proposal. Actual movement-filtered distance remains `ActivityLiveState.distanceMeters`; detours do not advance the route anchor. Reverse and intermediate starts interpret original geometry without rewriting it. At a segment endpoint, `SEGMENT_GAP` identifies both boundary endpoints and holds the selected leg. No connector contributes to route distance, and proximity to the next segment cannot advance automatically. `CORRECT_POSITION` explicitly chooses that or another section. Endpoint confirmation emits a finish proposal/event and never finishes the activity automatically.

Control points are visited once per route in an activity, using confirmed proximity or continuous passage on the active segment, with at most eight point events per fix. Manual correction does not mark skipped points visited. Their durable events restore visitation after recovery and returning to the original plan. Distinct gap/end/deviation/rejoin transitions and rate-limited reminders are written with the sample/header checkpoint. Navigation alerts publish through a bounded, non-replaying flow only after successful persistence. Quality loss, pause, finish and navigation changes suppress pending transient alerts while retaining their timeline events. Alerts can be disabled independently; durable deviation/rejoin history remains. T4/T7 own phone/watch UI, notification/haptic consumption and alert expiry checks; this phase adds no native vibration or map controls.

`ActivitySession.navigate` accepts generation-checked commands with a separate navigation revision: correction, return along recording, restoration of the original planned selection, and alert enablement. Exact repeated command identity returns its original persisted navigation state; conflicting reuse or stale revisions/generation fail. Navigation controls do not change lifecycle control revision, recorded positions, actual-distance statistics or the original planned snapshot. `ActivityValidation.navigationCommand`, `navigation` and `validRoutePosition` are available for independent T4 presentation validation.

`RecordedReturnRoute` reads committed positions in insertion-sequence order through custom 500-row pages, including across wall-clock rollback. Return preparation flushes pending recording first. It freezes original coordinates/timestamps into ordered segments, removes consecutive exact duplicates and excludes isolated non-navigable older segments. The latest segment must contain at least two distinct vertices. It caps inspected source positions at 200,000 and navigable segments at 2,000; excess fails without truncation. The snapshot begins at its last vertex and is interpreted in reverse. Recording continues independently, so later points never mutate the selected return geometry. Repeating Return with a new command explicitly creates a new snapshot. Optional elevation/health rows remain separate telemetry, with no invented vertex-height association.

The initial activity SQLite schema 1 includes `navigation_snapshots(activity_id, id, payload)`. This unreleased development schema is extended without a migration or version increase; routes SQLite remains schema 1 and Room remains schema 4. Existing development databases created with the earlier table set are not upgraded or deleted automatically and must be intentionally recreated before testing the new schema. Frozen return snapshots and their navigation command/header/event commit in one activity transaction; a failed write cannot leave a selected orphan snapshot. Snapshot input has its own 64 MB bound and capacity allowance, independent of ordinary 256 KiB sample/header batch limits. Multiple snapshots can be retained for the activity timeline and cascade only with activity deletion; they do not become reusable-route catalog rows or map-package assets. The current return geometry is cached as an immutable owned snapshot, avoiding decode/rewrite on each GPS update. Activity version-1 headers gain defaulted navigation state/command fields; the new reader accepts older headers lacking these fields.

### 8.10 T4 viewer integration

The offline viewer now owns `activity.presentation` and `activity.ui` inside `feature:viewer`, with a navigation-entry `TrackViewModel` bound through Metro. It consumes the application-scoped `ActivitySession`; it never owns native recording subscriptions or stops a session when cleared. All new display state uses immutable presentation values and immutable nested collections. Domain snapshots, command construction and independent input validation stay inside presentation. The constructor, root navigation and library action menu are unchanged in this phase; history and the package-free vector viewer remain T5.

The map has one **Track** control at the lower left. It opens **Record track** or **Follow route**; both lead to an in-map preparation panel with **Start**. Reusable route summaries load in custom pages of 50. The route list can open the existing native picker for streamed GPX import, preserving T1 full geometry and track grouping. One imported route opens preparation directly; multiple routes remain selectable. Existing package line callouts show length and **Follow**; selecting a line in Map objects opens the same callout. Following copies the annotation into the activity snapshot without moving or editing the package-owned object.

Preparation offers three profiles, forward/reverse traversal, segment selection and an integer source-edge slider with previous/next position controls for precise adjustment. The selected edge is highlighted separately from the render preview; **Show selected section on map** centers its selected start vertex through the viewer's existing coverage-aware camera command. Forward starts at the edge's first vertex; reverse starts at its last vertex. Reversing initially selects the opposite route endpoint. Multi-segment routes display an explicit gap warning. A full-geometry coverage check runs outside the UI thread and warns when any route vertex is outside the opened tile rectangle; it does not infer available coverage from the simplified preview. Wearable status is a phone-only notice, with no watch connection or sensor claims before T7. Coverage outside the opened package is not synthesized.

The collapsed indicator displays lifecycle state, elapsed time and hiking pace or motorized speed. Expanding shows actual distance, remaining planned distance/deviation when available, next named control point, GPS/matching quality, pause/resume and an Actions menu. Missing or paused speed/heart rate displays unknown, not zero. Recording/storage failure changes the indicator and exposes **Recover activity**; interrupted sessions require deliberate resume. Actions include confirmed finish, a manual timeline control point during recording, current telemetry details, section correction, navigation-alert suppression/restoration, frozen recorded return and restoration of the original plan. Telemetry details currently show elapsed/recording/moving time, distance, primary speed/pace and available heart rate; synchronized charts and historical review are T5. A manual point records an idempotent `MARK_CONTROL_POINT` command/event, with the accepted position sample ID only when location is live; it does not modify the planned control-point geometry or lifecycle.

Panel surfaces use theme colors at 0.88 opacity, content-sized widths up to 300 dp when expanded and 220 dp when collapsed, bounded scrolling at up to 60% of the viewport height, and existing minimum touch targets. Profile options scroll horizontally. Landscape places the expanded panel at the left-center. The collapsed panel reserves the measured bottom-content height; both states reserve the right control rail and safe drawing insets. Expanded track controls hide the coordinate readout and object callout. Opening annotation editing collapses track controls. Large-font, narrow-window and portrait/landscape overlap acceptance remains unverified.

Camera following is presentation state. Successful start or **Return to position** enables it; map gestures and ordinary layer/zoom interaction switch to free inspection. Fresh accepted live activity positions drive coverage-aware recentering at the current zoom. Unavailable/conflicting fixes do not drive activity following, and moving outside package coverage does not repeatedly trigger recenter errors. The existing My location control also provides explicit recentering during pause. None of these presentation actions changes recording. Immersive mode hides track controls and dialogs while keeping map vectors, session ownership and navigation alive.

`TrackMapLayer` projects and clips separate planned, return-guidance, preview, selected-edge and recorded-path strokes to the current package tile rectangle. It never creates connectors between segments or across the date line; unsupported Web Mercator positions split rendering. Route previews retain at most 10,000 vertices under the existing preview contract; matching still uses the complete snapshot. The incremental recording display keeps at most 20,000 points and the latest 2,000 segments, thinning only presentation geometry while retaining each displayed segment's endpoints. Older segments beyond that display budget are omitted; all raw samples remain stored. The insertion-sequence page now also returns `lastSequence` on a nonempty final page so polling can advance without repeatedly rereading it; `nextSequence` retains its existing pagination meaning. Committed positions refresh approximately once a second, with catch-up pages read in batches of 500. This is a rendering budget, not a recording limit or measured performance result.

T3 durable alerts are consumed as transient Channel events. While the viewer is RESUMED, meaningful transitions trigger the platform Compose haptic effect and a compact snackbar; immersive mode keeps the haptic and suppresses visible messages. New alerts replace the previous transient message. Events are not queued for replay after leaving/backgrounding the viewer. T4 does not install a separate background sound/haptic notification service; background recording/navigation remains T2/T3, and physical alert delivery or phone vibration behavior has not been verified. Apple Watch alerts remain T7.

No activity/route database schema or migration changes are made in T4: both independent spatial stores remain version 1, and the existing Room catalog remains version 4.

### 8.11 T5 history, review and sharing (ownership updated in 8.12)

`feature:viewer.history.presentation` owns `HistoryViewModel`, `ReviewViewModel`, immutable summary/chart/timeline models and the bounded `ActivityReviewReader`. `history.ui` owns the list, list items, dialogs and expandable review/chart components. Root composition wires **Activities** from the library's existing action menu and the Track menu. The library menu now has three animated branches/actions, with a 312 dp popup. `:shared` adds only navigation callbacks/destinations; it contains no recording, statistics or export policy. Successful foreground Finish navigates to review after the existing owner completes finalization. Opening history or review never starts an activity.

History loads custom keyset pages of 50 activity summaries or reusable-route summaries. Name search and completed/interrupted activity filters execute through existing catalog queries. Rows distinguish all lifecycle states and profiles. Completed activities can be renamed, deleted with confirmation, exported or explicitly derived into reusable routes; active/interrupted rows provide access to the owner's controls. Completed and interrupted records can be exported, with a revision check protecting concurrent resume/changes. Metadata rename increments the durable revision without changing recorded timestamps, statistics, events or frozen route geometry. Reusable route names use their own revision/CAS and remain independent of activity names. Deleting an activity removes its recording/events/private snapshots while retaining independently saved routes; deleting a reusable route preserves all activity snapshots. In the history UI, interrupted owner data must first be finished through its controls before rename/deletion.

T2 automatic route-free finalization remains authoritative and idempotent. Degenerate recordings remain valid history entries without a navigable derived route. T5 exposes explicit derivation for completed routed activities and handles deliberate deletion of a derived route: a later explicit derive can replace the stale derivation link with a new route ID in one route-store transaction. Repeated derivation returns that replacement. Existing tombstoned IDs are not resurrected, and the historical activity's original derived-route reference is not rewritten. This behavior uses the existing schema-1 tables; it adds no migration.

The existing viewer accepts optional package context and an optional review activity. With no package, it uses the existing map engine with a global Web Mercator pyramid (levels 0–18) and zero raster layers over a themed neutral surface. No tile source, network provider or fictional tile coverage is installed. Position and planned/recorded vectors remain available, with an explicit no-background-map label; unavailable package-only layers/annotations/DEM tools are inactive. Initial available geometry is fitted once, unless the user has already moved/recentered the camera. History and route selection work after map-package deletion. Ordinary offline package rendering remains unchanged. The existing renderer now accepts an empty layer list for this vector-only use. Source/API implementation is not evidence that native no-layer rendering has passed.

Review loads acquisition-ordered telemetry and insertion-ordered positions in pages of 500 and checks the activity revision again before exposing the result. It shows actual distance and stored elapsed/recording/moving/interrupted time, including an estimated-time label where applicable. Speed, heart rate and altitude charts are separate per source; altitude also separates vertical references. Statistics use all read samples: count, minimum, maximum and arithmetic sample mean, with no claimed time-weighted average or mixed-reference ascent. Missing heart rate is explicitly unavailable; phone data does not depend on Watch. Chart runs split at pauses, interruptions, position/telemetry loss, source conflicts, clock changes, unknown monotonic continuity and long measurement gaps. Display reduction retains source measurements and extrema rather than creating interpolated samples.

All charts use the same UTC acquisition-time axis. Selecting a plotted sample, accessible sample slider, timeline event or a recorded map stroke sets a shared time cursor. Recorded-path selections retain the selected sample ID/time through display thinning. Position lookup uses raw samples within five seconds; an exact referenced position is preferred, and ambiguous equally close candidates are unavailable. The map highlights and centers the measured position when supported, with separate labels for the selected time and actual position acquisition time. Health-only samples do not acquire invented coordinates. A temporal neighbor is displayed as a nearby recorded measurement, not an interpolated position. Wall-clock rollback remains visible through actual acquisition epochs and split clock runs; monotonic stored duration totals are not rebuilt from that axis.

Review vectors preserve segments and use the existing clipping/date-line limits. The map keeps at most 20,000 displayed positions across at most 2,000 segments, retaining segment boundaries and endpoint IDs. Each chart keeps at most 1,200 display points; statistics still include all processed samples. Reader limits are two million telemetry samples/positions, 10,000 events and 32 source/reference series. Exceeding a review limit fails explicitly without discarding stored data; export remains independently available from history. These are conservative source limits, not measured T6 performance. Review controls remain collapsed over the map until explicitly opened; expanded content scrolls within 60% of viewport height and reserves the right rail. Immersive mode hides review chrome, and viewing history does not change the current session lifecycle.

`domain:activity.ActivityExporter` streams caller-owned sinks and `core:sharing.SharedDocumentStore` provides private outbound `.gpx`/`.json` files and the existing native share sheet. Export choices disclose their contents before file creation:

- GPX 1.1 contains the route/activity name, 2D coordinates, separate track segments and available point acquisition times. It excludes altitude, speed, heart rate, control-point events and quality/provenance metadata. Reusable-route export uses its complete copied geometry; recorded-path export reads all accepted positions in insertion order, including degenerate segments, without display simplification. A geometry-only file does not claim to include telemetry.
- JSON identifies `format: bmaps.activity` and `version: 1`, with `activity`, `navigationRoute`, `samples` and `events`. The activity includes its frozen planned snapshot and stored statistics; `navigationRoute` is the current planned/private return geometry. Samples preserve canonical measurement values/units, sources, quality fields, phase, acquisition/receipt times and segment identity. Events retain durable sequence/time and navigation/related IDs. Older replaced private return geometries are not embedded; their event identifiers remain in the timeline. JSON import is not implemented.

Export reads records only when completed/interrupted, verifies the revision at the end and aborts rather than sharing a mixed snapshot when data changes. The UTF-8 output limit is 512 MB per file, writes flush in bounded chunks, invalid XML names fail, and no caller sink is closed. Explicit cancellation cancels the export job; existing share-store cleanup removes partial files. The GPX importer still has its separate 32 MB/200,000-vertex limits, so a large exported recording is not promised to be reimportable as a navigable route. Existing outbound-cache retention remains 24 hours with first-use cleanup, independently of activity deletion; previously shared copies cannot be revoked. Completed outbound files can also be purged by the OS. No active recording stops merely because a review/export UI is closed.

Activity/route spatial versions remain 1, Room remains 4, and no migration or watch implementation is added in T5. Apple Watch remains final phase T7. Phone builds, native share sheets, rendering, chart interaction and performance acceptance remain pending.

### 8.12 Phone tracking UX and map-owned routes — 2026-10-16

The map and the tracking screen reuse `ViewerScreen`/the renderer with distinct navigation entries. Start preparation remains on the map; successful Start opens `viewer/{packageId}/track`. The dedicated screen removes the crosshair/center-coordinate readout, shows the current location and keeps map editing long-press disabled. Leaving it does not stop the application-owned session. A normal map shows a compact active-track return indicator, which navigates to the activity's source map even when another map is open. History's new-record action leads to map selection; recording starts from an opened map. Completed live snapshots no longer block preparing another activity or leave an active indicator behind.

A bottom translucent, scroll-bounded panel exposes speed, recording time, actual distance, route progress, course, available live heart rate, pause/resume and an accessible overflow menu with explicit Finish confirmation. The right map-control rail remains clear. Collapsing retains speed/time, status, pause/menu and location failures; expanding restores details. Recording time freezes on pause while elapsed wall time remains available in details. Actual travelled distance and planned-route progress are separate (for example 6.2 km travelled versus 5.8 / 16 km on route). Course means movement direction; values require a fresh non-conflicted map fix, accuracy at most 35 m and speed at least 1 m/s. A stopped/unreliable course reads unknown. Heart rate is omitted when no current measurement exists. Route end remains an alert, not automatic Finish.

The screen continues its foreground map location subscription during recording pause; the recorder's telemetry subscription is stopped as before. These fixes update the map/arrow only and are not appended to telemetry. The blue navigator arrow retains the last trustworthy orientation while stopped, starts as a neutral dot when no orientation exists and becomes neutral when location is unavailable. Its orientation uses shortest-angle smoothing plus a 350 ms visual transition. North remains at the top. GPS failure/conflict is explicit and prevents camera follow; recorder recovery retains its existing segment-break policy.

Following uses a central free area (30–70% of map width, 25–60% of height); leaving it starts a cancellable 300 ms eased move at the current scale, with the marker biased above the bottom panel. Map pan/pinch beyond touch slop enters manual inspection. A tap or zoom button does not permanently disable follow. Return to position explicitly restores following; no timer recenters the map. The core adapter reports pan separately from initial touch/callout dismissal without consuming gestures. Existing projection/package limits remain: location outside the opened raster pyramid has an explicit coverage warning and cannot be centered beyond that pyramid; recording continues. Native camera thresholds, coverage handling and motion are unverified.

`PackageRouteStore` is a primitive core interface implemented by `LocalPackageRepository` and bound through Metro. It reads/writes a package's full JSON route asset under the existing package operation/file locks. Writes compare the prior payload, reserve capacity, mark metadata dirty and atomically replace the asset/manifest at the non-cancellable commit boundary. Reconciliation registers a committed route asset after interruption, updates inventory/size and rebuilds package-prefixed Room summaries. A single-package text cache avoids repeated asset decoding; manual full verification clears it. The asset is limited to 64 MB UTF-8 and 1,000 routes per package; per-route geometry limits remain unchanged. These bounds are storage guards, not performance acceptance.

Route IDs encode map ownership as `map:<packageId>:<routeIdentity>`; archive import validates the route document and rebinds IDs to the newly imported map. Full coordinates, segment gaps, control points, heights and source times are preserved; no display preview is written back. `ActivityRouteQuery.packageId` filters through the core Room query without a schema change; package-projection generations survive independent activity reconciliation. Package-owned derived rows in the old spatial store are internal recording-derived snapshots, not independent user routes. Frozen activity snapshots and recorded telemetry never look up the deleted package during historical review/export.

Map objects loads only its map's routes and renders all catalog pages, independently of list paging, with a bounded preview per route. Selection highlights the route; list selection fits it, while tapping an existing route opens its card without replacing the camera viewport. Manual lines use the same Start preparation. Legacy unassigned routes remain available in Activities: using one prompts for a target map and copies its full geometry into that map before preparation. Route-free completion retains its automatic recorded-route derivation into the source map. Editing a source route never rewrites an ongoing activity's snapshot.

The active record persists its source package ID with a version-1 default. A shared `PackageUseGuard` reserves that map before starting and releases it on failed start/completion; deletion checks the guard plus durable active catalog metadata, covering paused/interrupted/finalizing sessions and restart. A failed package-start check releases the reservation. Deletion remains allowed after completion, with independent recordings intact. Activity/route spatial schema versions remain 1 and Room remains 4; no migration or Watch implementation is introduced.

Authored verification, unrun: package transfer now checks route asset CAS, map deletion while reserved, identity rebinding and source timestamps; the existing native activity snapshot scenario checks map-owned route rollback/deletion/reopen with the frozen snapshot retained. Two `PackageUseGuardTest` scenarios cover active-map retention and a start waiting for deletion. Viewer checks cover distinct route/actual distances and recording/elapsed time, stopped/paused/lost course presentation, completed-live cleanup/repeated preparation and package-required import/cancel. The earlier GPX, session and chart checks remain. These are source scenarios, not successful test results.

Executed static verification: Kotlin PSI parsed 38 changed/new Kotlin files with no syntax errors; viewer/library resource XML/reference validation, 365 source headers and three packaged notices, documentation paths/module boundaries, unchanged spatial/Room schema definitions and whitespace checks passed. Syntax parsing does not resolve Kotlin/Compose/Metro types. No Gradle build, automated test execution, device/emulator/simulator run or performance/battery measurement was performed; the owner explicitly retains build/native acceptance. Required handoff: Android/iOS build and DI, start from manual/imported/free recording, pause without telemetry but with foreground map updates, lock/background/return, manual pan/zoom/recenter, weak/lost/conflicted GPS, azimuth wrap/stop, missing heart rate, arrival without finish, small/landscape/large-font panel layouts, source-map return and repeat recording after Finish. Check map export/import/rebinding/deletion and crash recovery, legacy copy-to-map, edited/deleted original snapshots, low storage/CAS/partial mixed import and large route/overlay performance. No phone acceptance phase is marked complete by this source increment.

## 9. Current state and evidence

Baseline: 2026-10-12. T0 added contracts/lifecycle/validation and headless Apple probes. The owner subsequently reported that the app builds and launches, without specifying a platform or test results. T1 adds storage/import source; T2 adds the phone owner, shared location distribution, native background ownership, recording/quality/statistics and recovery. T3 adds indexed matching, guarded progress/alerts, navigation commands and private recorded-return snapshots. Preparation/history UI and production watch integration remain later phases. No T1–T3 build, application test execution or physical acceptance was performed by the agent.

| Area | Existing evidence / constraint | Feature work remaining |
| --- | --- | --- |
| Location | T2 shares map/recorder subscriptions with native uptime/provenance/phone telemetry | Native background/permission/clock/overflow acceptance |
| Viewer lifecycle | Map collection stays RESUMED-only; T2 recorder retains its own consumer; T4 controls do not own recording lifetime | User-run screen-lock/map-exit acceptance |
| GPX | Unified confirmed file/pasted import maps grouped full routes and waypoints into Map objects; manual/imported routes share Start | User-run detailed-file/picker acceptance |
| Navigation | T3 full-geometry index, ordered/directed matching, explicit gaps/uncertainty, control points, durable alert transitions and return snapshots | User-run fixture/device calibration and foreground alert acceptance |
| Map UI | T4 route/free preparation, compact expandable controls, correction, camera follow and segmented overlays are present | User-run builds, layout/accessibility/camera/alert acceptance; T5 package-free viewer/history present; native acceptance pending |
| Activity contracts | Registered `domain:activity`; T1 repository adapters and umbrella bindings are present; history uses summaries | T2 phone owner present; T3 navigation/return present; T4 UI consumers present; T5 review/export present; native acceptance pending |
| Storage | T1–T3 activity/routes schema-1 databases, atomic commits/checkpoints, tombstones, route derivation, projection repair, Room schema 4 and 3-to-4 migration are present | User-run builds/schema review, native migration/IO acceptance; T2 timed recorder flush present; T5 export present; native sharing acceptance pending |
| Wearables | Official API and local SDK-header review; original headless hiking/mirroring probes authored | UI policy, user-run compilation and physical evidence, production buffering/commands, motorized capability decision |

Targeted source inspection: `core/location/src/commonMain/kotlin/bes/max/bmaps/core/location/LocationTracking.kt`, `domain/map-builder/src/commonMain/kotlin/bes/max/bmaps/domain/mapbuilder/AnnotationGpx.kt`, and `feature/viewer/src/commonMain/kotlin/bes/max/bmaps/feature/viewer/map/ui/ViewerScreen.kt`. Source inspection establishes integration constraints, not runtime acceptance.

For each implementation increment record its date, actual source changes, authored checks, executed checks/results, user-reported device acceptance, and remaining limits separately here. Existing project policy reserves mobile builds and test execution for the user; authored checks must not be marked as passed without execution evidence.

T0 regression source: `ActivityContractsTest` contains nine checks for terminal-state protection, explicit interruption resume/idempotent pause, reverse route snapshot/segment preservation, detailed-route independence from annotation limits, full telemetry rejection during manual pause, finish boundaries, command expiry/versioning, duplicate wearable sample IDs/timestamps, and unknown remote monotonic time/duration invariants. These checks are authored and unrun. User handoff: `./gradlew :core:location:testAndroidHostTest :domain:activity:testAndroidHostTest :domain:activity:iosSimulatorArm64Test` on a configured host, followed by physical probe scenarios above; no passing result is implied.

Executed T0 static checks: `scripts/license_headers.py --write` applied standard headers after original-source ownership review; the subsequent checker passed for 312 source files and three packaged notices. `git diff --check` passed. Additional path/module/source-boundary checks confirmed all 30 AGENTS documentation references, registered module/source references, all eight feature phases, English-only new feature text, and new-file whitespace. These checks do not establish Kotlin/Swift compilation or physical sensor behavior.

T1 source evidence: five `ActivityRouteGpxTest` checks cover a detailed 4,001-point track, grouped gaps and metadata, split UTF-8/BOM/prefix handling with caller-owned source lifetime, duplicate/invalid geometry, declaration/input limits, and preview endpoints/control edges. Seven `ActivityStorageScenarios` are exposed as Android device and iOS simulator tests: single owner/restart, real transaction rollback and repeated-command deduplication, paused acquisition rejection and segmented derivation, frozen snapshots/import rollback, catalog recovery/package-directory independence, batch/history cursors, and monotonic ordering across wall-clock rollback. These are authored and unrun. The owner-reported preceding app build/launch is not evidence for T1.

Executed T1 static checks: the license checker passed for 328 original source headers and three packaged notices; `git diff --check` passed. Source/document checks confirmed all 30 AGENTS paths, unchanged phase ordering, implementation status, core/domain import boundaries, module/DI references, Room migration registration, and authored test counts. These checks did not compile Kotlin, execute SQLite scenarios, generate the Room schema, or establish device acceptance.

T1 compilation follow-up (2026-10-12): the owner reported a Kotlin reified intersection-type inference error in `ActivityDatabase.page`. SQL parameter arrays in `ActivityDatabase` and `ActivityRouteDatabase` now explicitly use `Any?`, matching the query contract for mixed string/integer/null bindings. A successful rebuild or test run has not yet been reported.

T2 source evidence: eight `PhoneActivitySessionTest` scenarios cover all phone-channel pause/resume, duplicate/poor/conflicting fixes, shared map/recorder lifetime, checkpoint recovery/generation guards, storage stop/finalization retry, precise-permission failure, non-conflated native batches and explicit delivery overflow. Four statistics tests cover monotonic rollback/pause, discontinuity, segment/gap distance and duplicate/stationary movement. Location selection adds an uptime/wall-clock rollback regression. Native storage adds statistics/command-payload reopen coverage on Android/iOS (eight scenarios per platform). All are authored and unrun; fake hosts do not establish physical background delivery.

Executed T2 static checks: license validation passed for 336 original source headers and three packaged documents; `git diff --check` passed. Source/DI inspection and targeted checks covered 23 unique AGENTS document paths, Android service/permission declarations and matching native notification strings, iOS location background mode, core import boundaries, phase ordering, authored test counts and generated Room schema-4 presence. Local Kotlin/Native 2.4.10 metadata inspection confirmed the Core Location background properties and enum-qualified accuracy/UIApplication state access. These checks did not compile Kotlin, execute tests or establish background/device acceptance. At T2 delivery, session testing required a development caller; T4 now exposes map controls, without establishing their runtime acceptance.

T3 authored checks: thirteen `RouteNavigatorTest` cases cover reverse/intermediate starts, crossing/loop/shared-return/parallel ambiguity, direction, sustained deviation/rejoin/reminders, quality/pause/gap suppression, manual section transitions, control/end events, dateline/polar/long edges, and a 200,000-vertex budget fixture. Two session scenarios cover correction without telemetry/plan mutation and frozen reverse return with gaps while recording continues (ten session scenarios total). One added native storage scenario covers initial schema version 1, private snapshot/command reopen, ordered position pagination, trigger-induced post-snapshot rollback and activity-delete cascade (nine scenarios per Android/iOS suite). All are authored and unrun.

Executed T3 static checks passed: the license checker covered 341 original source headers and three packaged documents; `git diff --check` passed. Targeted source/document checks covered 23 unique AGENTS paths, unchanged phase order and T3 source status, core/domain boundaries, complete-geometry references, initial navigation-snapshot schema definition and authored scenario counts. Source inspection also covered contracts and DI ownership. It does not establish Kotlin compilation, SQLite test results, runtime guidance accuracy or measured performance. At T3 delivery, preparation UI and haptics were future consumers; T4 now wires foreground controls and alerts, without establishing runtime acceptance.

T4 source evidence (2026-10-12): viewer-owned preparation/route selection and GPX picker, compact expandable controls, confirmed finish/recovery, manual timeline points, section correction/return/alert actions, immutable presentation state, accepted-fix camera follow and segmented/clipped overlays are present. Working-tree inspection at the start of this increment found an empty `git diff`; the existing T0–T3 implementation was retained. Activity/route spatial versions remain 1 without migrations; Room schema/migration remains unchanged.

T4 authored checks, unrun: three `TrackViewModelTest` scenarios cover reverse/intermediate preparation without premature start and with a nonblocking coverage warning, denied permission with route-free retry, and current-identity lifecycle commands with collapse/inspection not sending commands. Four `TrackPresentationTest` scenarios cover paused/unreliable/unknown metric display, hiking pace versus route-free fields, bounded segmented recording geometry and clipped/dateline/gap overlays. One session scenario covers manual point deduplication and rejection during pause (eleven session scenarios total). The existing native navigation-snapshot scenario additionally checks the final-page insertion cursor (still nine scenarios per platform). These are source scenarios, not test results.

Executed T4 static checks: `git diff --check`, XML parsing and track resource reference checks, license validation for 347 original source headers and three packaged documents, and Kotlin PSI syntax parsing of 17 changed/new Kotlin files passed. Documentation-reference, module/package ownership and unchanged spatial-schema checks passed. PSI parsing does not resolve Kotlin types, compile Compose/Metro, execute tests or verify native APIs. No Gradle build, automated test execution, simulator/device launch, visual acceptance, haptic/background acceptance or performance/battery measurement was performed. Builds and tests remain assigned to the owner under the existing project policy.

T4 compilation follow-up (2026-10-13): the owner reported `:feature:viewer:compileAndroidMain` failing because `TrackViewModel` resolved `Annotation` to the default-imported `kotlin.Annotation` instead of the map-domain model. Explicit imports for the domain `Annotation`, `AnnotationKind` and `AnnotationValidation` replace the wildcard import. The missing-property, collection-inference and viewer-call argument errors follow from that type conflict. Static whitespace and license checks passed; no agent-run build or test execution was performed. A successful owner rebuild remains pending.

T4 device handoff: open an offline package and start route-free recording through Track; select a map line through its callout/Map objects or import a grouped detailed GPX and start in both directions from an intermediate edge. Check gaps/manual correction, recorded return, pause with no telemetry, explicit interruption recovery and finish confirmation/retry. Check large fonts, small portrait and landscape windows, right-rail/readout/callout coexistence, pan versus following, permission/settings retry, coverage loss, alerts in normal/immersive mode and absence of delayed alerts on return. Leaving the viewer must retain the phone recording; history and access to a map without a package arrive in T5. None of these scenarios is marked passed.

T5 source evidence (2026-10-13): viewer-owned paginated activity/route history, independent metadata actions, package-free map composition, post-finish review, synchronized source-aware charts/timeline/map selection, repeated derivation after explicit route deletion and streamed GPX/JSON sharing with cancellation are present. T0–T4 code and the explicit domain Annotation import correction were retained. No schema version increase/migration or Watch implementation was added.

T5 authored checks, unrun: two `HistoryViewModelTest` scenarios cover repository paging/filter/search requests and validation/CAS rename without timeline changes. Four `ReviewBuffersTest` scenarios cover full-sample statistics/extrema, bounded chart output, pause/clock/gap runs, preserved map segment boundaries/sample IDs and negative altitude. Three `ActivityExportWriterTest` checks cover invalid XML rejection, name/time and small decimal-coordinate round-trip plus caller sink lifetime, and incremental output. One added `ViewerViewModelTest` scenario covers package-free setup without package/tile-source access. The native segmented-recording scenario additionally verifies GPX gaps/timestamps, JSON sample/source acquisition/receipt fidelity, rename/reopen, explicit recreation of a deleted derived route, deduplication and independent retention after activity deletion (still nine scenarios per Android/iOS suite).

Executed T5 static checks: Kotlin PSI syntax parsing, changed resource XML/reference validation, license validation, documentation path/module/feature-boundary and unchanged schema-definition checks, and `git diff --check` passed. The license checker covered 357 original source headers and three packaged documents; PSI parsed 27 changed/new Kotlin files. These checks do not resolve Kotlin/Compose/Metro types, compile native targets, execute the authored tests, establish chart/map/haptic/share behavior or measure memory/battery/performance. No Gradle build, automated test run or device/simulator launch was performed; successful user build/test results remain unreported.

GPX/start follow-up (2026-10-13): the owner reported that a Strava GPX imports through Track but fails at Start with the generic geometry error, and cannot import through Map objects. File structure inspection found one GPX 1.1 track/segment, 2,749 source points, 2,710 consecutive-distinct positions, valid finite coordinates/heights/times and approximately 16 km of geometry. The retained shortest edge is about 0.079 m, above the navigation index's 0.001 m lower bound. This is structural inspection, not execution of the Kotlin importer against the private file. No original GPS fixture was committed.

The start failure is caused by `ActivityValidation.record`: its planned-navigation branch used an Elvis fallback on the nullable validation result, so valid navigation (`null`) became `navigationRoute` and repository creation rejected the activity. The fallback now checks for a missing selection before validation; valid planned navigation succeeds while identity, origin/direction and checkpoint errors remain rejected. This also fixes starting from saved routes/map lines, not just GPX. Database versions and migrations are unchanged.

Unified routes follow-up (2026-10-13): Map objects now presents manually drawn package lines and application-owned imported/recorded routes in one Routes section with the same Start/preparation flow. Follow a route opens this panel. The separate import-target button and Track-specific importer entry point were removed. Native file selection and pasted input share `MapObjectsViewModel` confirmation: GPX `trk`/`rte` become grouped full routes, `wpt` become package markers, and GeoJSON retains package annotations. Full stored geometry/source elevation/time and navigation snapshots are preserved without simplification; adjacent duplicate route coordinates are normalized. The selected saved route uses a bounded display preview, map fit, clickable segmented paths and a compact translucent card. The route catalog is global and ID-keyset paginated; only the selected application-owned route is overlaid. Package lines keep their existing editing/callouts. Independent storage lifetime remains an internal ownership contract, not a user-selected route category.

GPX limits remain 32 MB, 100 routes, 200,000 source route points, 2,000 segments/controls, plus 1,000 waypoints; GeoJSON/editable annotations keep their independent 4 MB/1,000-object/1,000-vertex bounds. Summary confirmation allocates one prepared batch with new IDs. Route-only imports work without a map package; package-dependent mixed input rejects before writes without a package. Mixed commits span two stores: routes save first, package objects second, with an explicit partial-result/retry state and stable marker IDs, never a claim of cross-store atomicity. Closing after partial save retains committed routes; process termination loses the transient retry state. Successful import refreshes package objects/routes and selects a sole route without starting telemetry. Active activity/review disable Start; importing objects does not alter the phone owner. Route metadata/GPX export remain available in Activities. Package annotation GeoJSON export is explicitly labeled.

Authored regressions, unrun: planned-record validation in both directions with missing/mismatched selection/route/checkpoint; synthetic 2,749-point Strava-style GPX with repeated positions and navigation-index/record validation; unified mixed/grouped and waypoint-only GPX with invalid waypoint rejection; four `MapObjectsViewModelTest` scenarios for detailed mixed confirmation/fidelity, pre-write package checks, package-free route import/cancel and partial-save retry without duplicate route writes. Existing Track preparation/session scenarios remain. Executed static checks: resource XML/reference and documentation path validation, Kotlin PSI syntax parsing, license validation and `git diff --check` passed. These do not type-check or establish runtime behavior. No build, automated test execution or device launch was performed. Owner acceptance remains: rebuild; import the supplied GPX through Map objects and Follow a route; select/start/pause/resume/finish/reopen manually drawn and imported routes; check multiple routes, reverse/intermediate starts, package-free display, line visibility, catalog paging, picker cancellation/failure, partial mixed-save retry, process interruption and active-activity guards. Watch stays T7; activity/route spatial databases stay version 1 without migrations.

T5 owner handoff: build both platforms and run the domain/viewer suites listed below. Check Activities from the library and map menu, empty history/search and paging, all lifecycle distinctions, protected active/interrupted edits, independent rename/delete and route reuse, degenerate and segmented completion, post-finish summary, viewing after package deletion, zero-raster rendering and continued active recording after history/map exit. Exercise chart/map/event selection, missing heart rate, negative/source-specific altitude, pauses/gaps, wall-clock rollback and unavailable/ambiguous nearby positions. Inspect exported segmented GPX and JSON against stored sample/event counts, test cancellation, permission/native share failure, revision conflict and output-size/storage failure, and confirm no truncated file is shared. Large text, narrow portrait/landscape windows, dateline/polar limits, long-recording memory and physical share-provider behavior remain unverified.

User verification handoff:

```sh
./gradlew :core:location:testAndroidHostTest :domain:activity:testAndroidHostTest :feature:viewer:testAndroidHostTest
./gradlew :domain:activity:iosSimulatorArm64Test :feature:viewer:iosSimulatorArm64Test
./gradlew :domain:activity:connectedAndroidDeviceTest
```

Confirm available Android device task naming with the installed Gradle plugin if needed. Review/commit the generated `core/database/schemas/bes.max.bmaps.core.database.PackageDatabase/4.json` after building. Native acceptance must also cover upgrading an existing schema-3 installation without losing packages, actual package deletion with retained activities, low storage/failed writes, cancellation/journal recovery, large GPX limits, and unsupported spatial schemas. T2 native acceptance also covers screen lock/navigation-away, single subscription with a map, paused sensor release, notification denial/Finish, foreground start/resume restrictions, permission/owner loss, wall-clock changes, finalization restart and write failure. T3 acceptance also covers initial activity-schema creation with private navigation snapshots, reverse/intermediate starts, physical intersections/parallel trails and loops, explicit gap crossing, correction without path mutation, sustained deviation/rejoin, disabled alerts, snapshot return through pauses and restart, large-route query budgets and performance. No T1–T3 build or test result is claimed here.

## 10. Temporary implementation plan

Remove this entire section after implementation and acceptance, moving enduring decisions and actual limits into sections 1–9 first. Phase identifiers below are feature-local and do not renumber the application phases in document 5.

| Phase | Deliverable | Dependency | Current state |
| --- | --- | --- | --- |
| T0 | Contracts, architecture decisions, and API feasibility assessment | None | Source present; owner decisions resolved; app build/launch user-reported; tests unreported |
| T1 | Durable activity/route/sample storage and detailed-route import | T0 | Implemented in source; builds/tests/schema/native acceptance pending |
| T2 | Phone-owned background recording and recovery; full telemetry stop on pause | T1 | Implemented in source; builds/tests/background/native acceptance pending |
| T3 | Complex-track navigation and progress | T1–T2 | Implemented in source; builds/tests/storage/navigation acceptance pending |
| T4 | Map entry points and compact activity UI | T2–T3 | Implemented in source; builds/tests/layout/camera/alert acceptance pending |
| T5 | History, synchronized charts, reusable routes, export | T1–T4; no wearable dependency | Implemented in source; builds/tests/history/chart/share/native acceptance pending |
| T6 | Phone acceptance and performance | T1–T5 | Not started |
| T7 | Apple Watch integration, wearable acceptance, and final documentation closure | T0 API assessment and accepted phone feature T1–T6 | Not started; final implementation phase |

### T0 — Contracts and platform feasibility

- [x] Write concrete route, lifecycle, telemetry, quality, query/session/repository, and wearable contracts with independent validation and regression source.
- [x] Select domain/core/feature ownership and independent spatial storage; register `domain:activity` and update architecture/contracts/module documents.
- [x] Define start prerequisites, clock/duration semantics, explicit interruption recovery, full telemetry stop on pause, and finalization transitions.
- [x] Complete official API/local SDK assessment of phone background lifecycle, watch platform support, hiking workout mirroring, and motorized-profile capability limits; record proposed OS minima without claiming device verification.
- [x] Author isolated live-heart-rate/mirroring source probes without introducing UI or production capabilities. Keep these existing probes inactive until T7.
- [x] Record the owner's watchOS-only SwiftUI approval and fixed full-stop pause decision; move production watch integration and physical feasibility checks to T7.

Acceptance: contracts and ownership are implemented in source, and the policy choices are resolved. The owner reported app build/launch without a specified platform; automated-test evidence remains unreported. Physical watch evidence and unresolved motorized capabilities belong to T7 and do not gate phone phases T1–T6. No source/API assessment is described as device acceptance.

### T1 — Durable data and detailed routes

- [x] Implement versioned activity, route snapshot, segmented positions, telemetry, event, and checkpoint storage with bounded IO and custom history queries.
- [x] Define write-loss bounds, flush/finalization behavior, migration, capacity failures, deletion, and orphan recovery.
- [x] Add detailed GPX route import preserving route/track grouping and segment gaps. Enforce explicit byte/point/segment limits.
- [x] Retain source geometry and separate render simplification; adapt existing selected annotation lines to routes without changing package ownership.

Acceptance targets: realistic detailed 5–30 km fixtures round-trip without silent truncation; gaps survive import/storage; interrupted writes recover; deleting a map package preserves application-owned activities/routes. Source deliverables and regression scenarios are present; none of these runtime targets is marked verified until user-run tests/device evidence are recorded. Timed sensor buffering is T2, and map import UI is T4.

### T2 — Recording and lifecycle

- [x] Implement one activity owner, shared location distribution, accepted positional recording, telemetry/event recording, and explicit quality states.
- [x] Add Android foreground-service ownership and iOS background-location integration with appropriate permissions and native status.
- [x] Implement start/pause/resume/finish, locked-screen recording, durable interruption recovery, and storage-failure handling.
- [x] Stop all telemetry/path recording during manual pause; retain lifecycle events and elapsed time; start a new path segment on resume.
- [x] Compute elapsed, recording, moving time, and distance without counting duplicate fixes or bridging gaps.

Acceptance targets: routed and route-free activities record through screen lock and leaving the map; ordinary non-recording maps retain lifecycle cleanup; pause records no telemetry; interruption restores committed data with explicit gaps; denied sensors and write failures have truthful states.

Source and regression scenarios are present. Builds/tests and physical background, service/permission, battery and storage acceptance remain pending. Checked items describe implementation, not passed runtime acceptance; map preparation/controls arrive in T4.

### T3 — Complex-track navigation

- [x] Implement indexed/bounded route matching using ordered progress, direction, continuity, and quality rather than nearest-point distance alone.
- [x] Add reverse/intermediate starts, uncertain matching, manual section correction, control points, and planned-versus-actual distance.
- [x] Add sustained-deviation detection, suppression/rejoin behavior, and throttled alert transitions.
- [x] Add return along a recorded path with explicit gap handling.

Acceptance targets: intersection, loop, shared-return-section, parallel-trail, off-route, poor-fix, source-conflict, and segment-gap fixtures preserve sensible progress or report uncertainty. Corrections do not modify recorded samples.

Source and regression scenarios are present; builds/tests, native initial-schema/storage checks, physical navigation and threshold/performance calibration are pending. Checked items represent source implementation, not passed acceptance. T4 now provides viewer controls/overlays and foreground phone alert consumption; their device acceptance remains pending.

### T4 — Map interface

- [x] Add route selection/Follow and one Track control with a shared compact preparation panel containing Start.
- [x] Add collapsed/expanded activity presentation, primary metrics, pause/resume, overflow actions, and finish confirmation.
- [x] Add follow/free camera state, Return to position, route/recording overlays, and compact quality/deviation states.
- [x] Integrate existing coordinate readout, callouts, controls, portrait/landscape insets, accessibility, and immersive gestures.

Acceptance: both start paths are discoverable on the map; normal activity display stays compact; panels do not overlap critical controls; free inspection and immersive mode preserve recording and guidance. Phone functionality requires no watch.

Source and regression scenarios are present. Builds/tests, physical layout/accessibility/camera/haptic acceptance and measured performance remain pending. Checked items indicate implementation only. Package-free viewing, history and charts remain T5; wearable functionality remains T7.

### T5 — History and reusable routes

- [x] Add an accessible activity-history entry point and paginated summaries, with completed/interrupted distinctions.
- [x] Add map/chart-linked review, available phone telemetry statistics, quality gaps, pauses, and control points. Leave absent heart rate unavailable until a source exists.
- [x] Automatically derive a reusable segmented route after route-free completion; support optional derivation from routed activities and degenerate recordings.
- [x] Add renaming, deletion, route export, and recorded-path sharing with explicit payload contents and independently retained health data.

Acceptance: route-free completion produces one valid reusable route when geometry permits; interrupted finalization cannot duplicate it; chart/map timestamps agree; exports preserve declared geometry and gaps without claiming omitted telemetry. History/export is complete without Apple Watch and can consume its samples later.

Source and authored regression checks are present. Builds/tests and physical rendering/chart, cancellation/share, storage and performance acceptance remain pending. Checked items indicate source implementation only; T6 owns phone acceptance and T7 owns Watch.

### T6 — Phone acceptance and performance

- [ ] Complete focused phone algorithm/persistence checks and user-run builds/tests with recorded results.
- [ ] Obtain device acceptance for routed and route-free hike/ride scenarios, locked-screen/background lifecycle, camera/panels, GPS loss, full telemetry pauses, and recovery.
- [ ] Measure detailed-route matching/rendering, long-recording memory/storage, and battery behavior on representative Android/iPhone devices.
- [ ] Update implementation descriptions and remaining phone limitations before watch integration begins.

Acceptance: the complete phone workflow and history/export operate without a wearable; source completion, automated results, and device acceptance are recorded separately. No unperformed verification is marked passed. Keep the temporary plan until T7 finishes.

### T7 — Apple Watch integration and final closure

- [ ] Revisit the T0 headless probes and obtain user-run compilation/physical feasibility evidence for supported activity profiles; resolve live heart rate and background alert limits explicitly.
- [ ] Implement the watchOS-only SwiftUI target and platform adapters, authorization, available live heart rate, and session-conflict handling.
- [ ] Show compact metrics/control-point status and haptic navigation alerts.
- [ ] Implement acknowledged, idempotent pause/resume commands with session identity and explicit stale/undelivered states.
- [ ] Implement durable bounded telemetry buffering, timestamp/provenance preservation, deduplication, reconnection, and buffer cleanup. Discard all paused-interval samples, including late arrivals, using durable ignored-sample acknowledgments.
- [ ] Verify the complete phone/watch workflow, screen-off operation, permission failures, disconnection, delayed samples, competing workouts, full telemetry pause/resume, history, and supported activity profiles on physical devices.
- [ ] Measure bounded reconciliation, recording impact, and phone/watch battery behavior; confirm phone-only operation still works.
- [ ] Reconcile documents 1–5, 10, 13, 15, 19, and 20 where responsibilities/behavior change; keep AGENTS.md discovery accurate.
- [ ] Rewrite sections 1–9 around the delivered implementation and actual limitations; remove this temporary plan only after full scope and acceptance are complete.

Acceptance: Apple Watch is the final functionality implemented after phone acceptance. Phone recording survives watch loss; no paused telemetry is recorded or restored from a backlog. Capability limits and source/automated/physical evidence are explicit. No phase checklist remains after final closure.

### Decision register

| Decision | Current proposal / open issue | Required before |
| --- | --- | --- |
| Manual pause | Owner confirmed full telemetry/path stop; no selectable pause policy; resume creates a segment; late delivery uses acquisition-time boundaries | T2 native acceptance; T7 health producer |
| Moving time | Separate monotonic owned intervals; no automatic pause; movement hysteresis awaits measured profile tuning | T2 source present; device tuning pending |
| Application-owned spatial data | T1 source implements independent activity/route SQLite stores and repairable Room projection; no package cascade | Native migration/storage acceptance |
| Module boundaries | `domain:activity` registered; viewer-owned activity/history; future core native adapter | Adapter creation |
| Apple Watch UI | Owner approved SwiftUI only for watchOS; production integration follows all phone phases | Implement final T7 |
| Apple Watch per-profile heart rate | Hiking probe source/API path established; continuous motorized HR and background alerts unresolved, no fabricated workout | Physical feasibility/T7 commitment |
| Map/history entry points | T4 live controls and T5 history, package-free viewer, review and library callback implemented; constructor unchanged | User-run T4/T5 navigation/UI acceptance |
| Metric defaults | T4 displays hiking pace, motorized speed and elapsed/state; expanded presets are fixed | User-run T4 layout/metric acceptance |
| Data-quality policies | Explicit provisional age/accuracy/gap/live-HR gates in section 8.3; profile movement/deviation/elevation tuning requires evidence | T2/T3 source present; calibration pending |
| Import/export limits | T1 streamed importer enforces 32 MB/100 routes/200k total vertices/2k total segments and named points; T5 streams segmented geometry GPX and versioned telemetry JSON | User-run file/share acceptance |
| Recovery guarantees | T1 atomic commits/checkpoints/projection repair/idempotent derivation are present; T2 recorder interruption/native ownership are present in source | User-run T1/T2 native evidence |
| Wearable buffer policy | 24h/16 MB, bounded packets/chunks, acquired-time ingestion, durable sample acknowledgment, five-second commands without offline replay | Implement T7 |

### Track start dialog and map button — 2026-10-16

The lower-left Track control now uses the shared `MapIconButton`, with a route glyph, 48 dp minimum target, themed translucent background, safe insets and 16 dp margins. Without an active recording it opens a separate start-settings dialog directly. The dialog contains the activity profile, selected route and its direction/start section when applicable, coverage/gap warnings and an explicit Start action. Choosing a route opens the existing map objects UI; following a selected route opens the same preparation dialog. Free recording remains available without selecting a route. Choose on map temporarily hides the dialog and opens the position picker described below. Cancel clears preparation, and dismissal/start actions are disabled during submission. Errors stay in the dialog.

During recording, the ordinary map uses this same icon with an active tint and an Open active track accessibility label to return to the tracking screen. This replaces the previous text return indicator. The dedicated recording panel is unchanged; section correction uses the dialog as well. Preparation remains owned by the existing navigation-scoped TrackViewModel because map selection/preview and session submission span the dialog lifecycle.

Verification for this UI increment: static XML/resource and whitespace checks only. No build, automated tests, emulator or device runs were performed. Owner acceptance still needs free/selected-route start, submission failure, cancellation, section preview/reopening, active-track return, portrait/landscape, large fonts and screen-reader checks.

### Start position selected on the map — 2026-10-16

The preparation/correction dialog no longer exposes segment numbers, vertex indices or a slider. It shows distance from the stored route beginning and Choose on map. A direction-aware route-beginning shortcut resets the start to the first/last point. Choosing on the map hides the dialog and center readout, suspends camera following, and reserves taps for route selection while allowing pan/zoom. An outlined direction marker and highlighted edge identify the selected passage. The compact translucent bottom panel provides distance, Confirm position and Cancel. Cancel or the map Back button restores the original position and dialog; confirmation retains the exact edge fraction and returns to the dialog without starting recording. Activity profile and direction remain selected. Starting/correcting navigation consumes that fraction; telemetry still starts at the real device position.

Hit testing uses full stored geometry, independently of display simplification, in screen space with a 24 dp tolerance. Distant taps show a localized hint and disable confirmation until another valid tap. Adjacent edges within the tap neighborhood form one passage candidate; separate passes/segments offer a Switch action with the selected passage highlighted. Up to 32 nearest passage candidates are retained. No imaginary line is inserted across GPX segment gaps. Very tight loops wholly inside the hit neighborhood require zooming in to distinguish their passages. Distance excludes segment gaps and is always measured from the stored route beginning, including when travelling in reverse. Antimeridian-crossing edges remain unselectable, consistent with the current route overlay. Matching scans run cancellably on Default; newer taps cancel older scans. Picker state and display models stay in TrackViewModel so the dialog/map transition does not discard settings. A GPS-based nearest-start shortcut is not included in this increment.

Verification: Kotlin PSI syntax parsing, resource XML/name checks, license headers and `git diff --check`. Added synthetic test cases for interior fractions, distant taps, crossing alternatives, segment gaps, shared vertices, cancellation and passing the confirmed fraction into activity start. These tests were authored but not run. No compilation, automated test execution or device/emulator acceptance was performed. Owner checks: manual/imported/reversed routes, crossings and dense loops at several zoom levels, correction while recording, quick successive taps/cancel, landscape/large fonts, native Back navigation, GPS/camera interaction and long-route performance. The map Back control handles cancellation explicitly; native Back behavior remains unverified.
