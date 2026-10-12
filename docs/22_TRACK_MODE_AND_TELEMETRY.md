---
type: "feature_specification_and_implementation_tracking"
project: "Bmaps"
purpose: "Define track navigation, route-free recording, telemetry, Apple Watch integration, and their implementation phases."
---

# Track mode and telemetry

Created: 2026-10-12. Updated: 2026-10-12. Status: T0 contracts, pure lifecycle policy, validation, and API assessment are implemented in source. The owner approved watchOS-only SwiftUI and full telemetry stop on manual pause. Production watch work and physical feasibility checks are deferred to final phase T7. Builds/tests are unrun; runtime recording/navigation are not implemented.

This document owns the feature specification and its temporary implementation tracking. Sections 1–8 describe the target behavior, not existing functionality. Section 9 records current evidence. Section 10 contains the temporary implementation plan and decision register. Update behavior sections to describe the actual implementation as each slice lands. Once the complete feature is implemented and accepted, remove section 10, including the phase table, checklists, and resolved decision register. Retain the implementation description, contracts, limitations, and verification evidence; do not leave the completed plan in this document.

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
- Export reusable route geometry and share recorded paths. Exact telemetry export encoding is settled before the export phase.

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

Activities and reusable recorded routes must be independent of map-package deletion. Existing package annotations remain package-owned; route selection adapts their line geometry into the route model rather than silently relocating annotations. T0 selects independent application-owned SQLite spatial stores and a repairable Room catalog projection, specified in section 8.2. The storage implementation belongs to T1.

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

Initial map integration belongs to the offline viewer. Recording must survive leaving that viewer. The library action menu gains **Activities**, opening viewer-owned activity history through root navigation. History opens the same viewer in activity-only mode when no package is selected: recorded/planned vectors and position remain visible on a neutral map surface, without fabricated raster coverage. A route-free recording can start from that map via the Track control. Opening history itself does not start recording. Online constructor navigation remains outside this first integration; it does not become a navigation screen. These are T0 composition decisions; screens are implemented in T4/T5.

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

- `domain:activity` owns activity/route/telemetry contracts, independent validation, and pure lifecycle policy. Route matching, statistics, session ownership, and repository adapters will extend this ownership in later phases.
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
| `ActivityContracts.kt` | Session owner, atomic repository commit, route derivation, custom keyset history/sample/event queries, and typed failures; interfaces only |
| `ActivityValidation.kt` | Independently callable input/schema/geometry/sample/query/quality validation; not embedded in structural use cases |
| `WearableProtocol.kt` | Version-1 snapshots, commands/acknowledgments, bounded timestamped heart-rate batches, sample acknowledgments, and independent inbound validation |

Domain collections are ordinary data contracts, not Compose-stable display values. Repository/session owners must take owned copies of nested input collections before retaining a snapshot. Feature presentation must map these contracts into immutable display values as usual.

The route snapshot carries ordered segments and `RoutePosition(segmentIndex, edgeIndex, fraction)`; reverse travel changes interpretation, not stored geometry. Segment identifiers and control-point identifiers must be unique within the route. Adjacent repeated positions are rejected after import normalization; non-adjacent repetition is allowed for loops. A valid segment has at least two positions. Gap lengths are not counted as measured route length.

### 8.2 Selected storage and consistency design

T1 will implement two private spatial databases, outside package folders:

- `activities/activity.sqlite`: activity records, route snapshots, accepted position/non-positional samples, events, interval/segment boundaries, durable checkpoint, active-owner slot, and command deduplication/acknowledgments.
- `routes/routes.sqlite`: reusable route records, ordered segments/vertices, control points, route revisions, and derivation identity.

Room holds activity/route catalog metadata for custom history/filter queries, but never becomes the authority for raw spatial/health samples. No new foreign key cascades from a map package to an activity or reusable route. Schema versions begin at 1 and are independent of package schema versions. These databases do not join the `.bmaps` inventory or the package disk-size calculation.

Use bundled SQLite through core-owned primitive records and kotlinx-io path infrastructure. Initial policy is serialized transactions, `synchronous=FULL`, and the established DELETE journal mode; any measured change to WAL requires explicit concurrency/recovery documentation. The activity database's single active-owner slot and activity creation commit atomically, so two start requests cannot create two active activities. Room projection writes follow authoritative commits; startup reconciliation repairs missing/stale catalog rows using stored revisions.

`ActivityCommit` uses a compare-and-set `expectedRevision`; the next durable record revision increments by one. Record, samples, events, checkpoint, and applied-command identity commit together. Conflicting writes return `RevisionConflict`, not silent replacement. `(activityId, sourceId, sampleId)` deduplicates samples. Activity event sequences are strictly increasing. History order is start time descending/id ascending; sample order is acquisition epoch/source ID/sample ID ascending; cursors use those exact tuples. Queries bound each page to at most 500 records, without generic paging libraries.

Lifecycle control has a separate `controlRevision`, incremented only for control changes, not periodic telemetry commits. Wearable pause/resume compares this revision, preventing normal sample writes from constantly invalidating commands. `sessionGeneration` changes on recovery/resume from interruption and protects commands from a previous recorder instance.

Flush at 256 samples, 256 KiB of encoded transaction input, or the one-second scheduled commit interval, whichever is reached first. Start, pause, resume, finish, and error/interruption transitions flush immediately. The one-second interval is a scheduling target, not a guarantee while the OS suspends execution; only committed batches are durable. Capacity/IO failure ends healthy recording state, preserves committed data, and requires explicit recovery. Never acknowledge wearable samples before their transaction commits.

Finish first records `FINALIZING` and its stop boundary, releases native recording ownership, then derives a route if required. Derivation uses the predetermined route ID and activity ID as a unique key in the route store. A retry returns the existing derived route rather than creating another. Completion records that link and `COMPLETED`; finalization can resume on startup without resuming sensor recording. There is no claimed cross-database atomic transaction. An interrupted catalog/link update is reconciled from derivation identity. Deleting an activity does not delete an independently saved reusable route; removing associated pending wearable samples must prevent late delivery from recreating the deleted activity.

Detailed GPX limits are 32 MB UTF-8 input, at most 100 resulting routes, 200,000 vertices, 2,000 segments and 2,000 control points across the import document, with the same geometry caps on each stored route. The future streamed importer also retains XML depth/entity protections and counts limits before allocating the complete geometry. Existing annotation limits remain unchanged. Oversized routes fail explicitly; no implicit truncation or simplification. GPX export preserves track segments; health telemetry is an explicit separate versioned JSON payload rather than undocumented vendor GPX extensions. Export/storage codecs are not implemented in T0.

### 8.3 Start, clocks, lifecycle, and recovery

Start requires a visible user action, an available native location service, and appropriate precise location permission. Optional heart-rate denial does not block start. A missing initial fix is allowed: the activity begins in RECORDING with unavailable position/progress and no invented path. T2 must independently validate start input in presentation before calling the session owner.

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

T2/T3 will add profile-specific movement hysteresis and deviation policies with fixture/device evidence; no physical speed, ascent, or warning threshold is claimed calibrated in T0. Preserve speed source, never differentiate noisy positions without a defined quality policy, and never merge unidentified elevation references. Negative elevation is valid; zero heart rate is invalid/missing.

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
| Android | Keep project minSdk 30; start from a resumed user action using a location foreground service, fine location, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`; no initial background-location permission or boot auto-resume | T2 native implementation; API-level/device behavior, notification actions, process interruption |
| iPhone | Keep current deployment minimum iOS 18.2; foreground-started Core Location with location background mode and `allowsBackgroundLocationUpdates`; When In Use authorization and visible background-location status; disable automatic activity pauses while recording | T2 capability changes and physical locked-screen/background evidence; no force-quit guarantee |
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

The eventual activity owner is application-scoped through Metro with platform service ownership. `core:location` supplies raw native observation and retains source selection/quality behavior, but needs provenance/altitude metadata extensions in T2; its current `LocationFix` does not expose selected provider identity or height. Viewer location UI must observe the owner's stream during an activity and keep existing foreground subscriptions when no activity exists. Native wearable infrastructure is a separate future core adapter, not a dependency on viewer or domain; source DTO mapping happens in the activity domain adapter.

## 9. Current state and evidence

Baseline: 2026-10-12. T0 now adds contract/lifecycle/validation source and headless Apple probe source. Recording, persistence, navigation, phone/watch UI, and production integration remain unimplemented. No builds, application tests, or physical device checks have been run for T0.

| Area | Existing evidence / constraint | Feature work remaining |
| --- | --- | --- |
| Location | `core:location` exposes continuous fixes with time, accuracy, optional speed/course | Activity ownership, background lifecycle, recording quality policy |
| Viewer lifecycle | `LocationTracking` collects while the map lifecycle is resumed | Decouple active recording from map visibility while retaining ordinary foreground-map behavior |
| GPX | Annotation import maps each track segment to a line; input is bounded and route/segment vertices are limited to 1,000 | Bounded detailed-track import, grouped route identity, explicit gaps, separate reusable-route representation |
| Map UI | Viewer has object controls, callouts, immersive state, and translucent control styling | Preparation, activity panel, camera-follow state, navigation overlays |
| Activity contracts | Registered `domain:activity`, serializable models, pure transitions, independent validation, query/session/repository interfaces, wearable DTOs | Repository/session implementations and DI integration |
| Storage | Room app metadata and package-local annotations exist; T0 selects independent spatial SQLite stores and repairable catalog | Implement samples, recovery, migration, deletion/export in T1/T5 |
| Wearables | Official API and local SDK-header review; original headless hiking/mirroring probes authored | UI policy, user-run compilation and physical evidence, production buffering/commands, motorized capability decision |

Targeted source inspection: `core/location/src/commonMain/kotlin/bes/max/bmaps/core/location/LocationTracking.kt`, `domain/map-builder/src/commonMain/kotlin/bes/max/bmaps/domain/mapbuilder/AnnotationGpx.kt`, and `feature/viewer/src/commonMain/kotlin/bes/max/bmaps/feature/viewer/map/ui/ViewerScreen.kt`. Source inspection establishes integration constraints, not runtime acceptance.

For each implementation increment record its date, actual source changes, authored checks, executed checks/results, user-reported device acceptance, and remaining limits separately here. Existing project policy reserves mobile builds and test execution for the user; authored checks must not be marked as passed without execution evidence.

T0 regression source: `ActivityContractsTest` contains nine checks for terminal-state protection, explicit interruption resume/idempotent pause, reverse route snapshot/segment preservation, detailed-route independence from annotation limits, full telemetry rejection during manual pause, finish boundaries, command expiry/versioning, duplicate wearable sample IDs/timestamps, and unknown remote monotonic time/duration invariants. These checks are authored and unrun. User handoff: `./gradlew :domain:activity:testAndroidHostTest :domain:activity:iosSimulatorArm64Test` on a configured host, followed by physical probe scenarios above; no passing result is implied.

Executed T0 static checks: `scripts/license_headers.py --write` applied standard headers after original-source ownership review; the subsequent checker passed for 312 source files and three packaged notices. `git diff --check` passed. Additional path/module/source-boundary checks confirmed all 30 AGENTS documentation references, registered module/source references, all eight feature phases, English-only new feature text, and new-file whitespace. These checks do not establish Kotlin/Swift compilation or physical sensor behavior.

## 10. Temporary implementation plan

Remove this entire section after implementation and acceptance, moving enduring decisions and actual limits into sections 1–9 first. Phase identifiers below are feature-local and do not renumber the application phases in document 5.

| Phase | Deliverable | Dependency | Current state |
| --- | --- | --- | --- |
| T0 | Contracts, architecture decisions, and API feasibility assessment | None | Implemented in source; owner decisions resolved; builds/tests unrun |
| T1 | Durable activity/route/sample storage and detailed-route import | T0 | Not started |
| T2 | Phone-owned background recording and recovery; full telemetry stop on pause | T1 | Not started |
| T3 | Complex-track navigation and progress | T1–T2 | Not started |
| T4 | Map entry points and compact activity UI | T2–T3 | Not started |
| T5 | History, synchronized charts, reusable routes, export | T1–T4; no wearable dependency | Not started |
| T6 | Phone acceptance and performance | T1–T5 | Not started |
| T7 | Apple Watch integration, wearable acceptance, and final documentation closure | T0 API assessment and accepted phone feature T1–T6 | Not started; final implementation phase |

### T0 — Contracts and platform feasibility

- [x] Write concrete route, lifecycle, telemetry, quality, query/session/repository, and wearable contracts with independent validation and regression source.
- [x] Select domain/core/feature ownership and independent spatial storage; register `domain:activity` and update architecture/contracts/module documents.
- [x] Define start prerequisites, clock/duration semantics, explicit interruption recovery, full telemetry stop on pause, and finalization transitions.
- [x] Complete official API/local SDK assessment of phone background lifecycle, watch platform support, hiking workout mirroring, and motorized-profile capability limits; record proposed OS minima without claiming device verification.
- [x] Author isolated live-heart-rate/mirroring source probes without introducing UI or production capabilities. Keep these existing probes inactive until T7.
- [x] Record the owner's watchOS-only SwiftUI approval and fixed full-stop pause decision; move production watch integration and physical feasibility checks to T7.

Acceptance: contracts and ownership are implemented in source, and the policy choices are resolved. Builds/tests remain unrun under the existing owner-run verification policy. Physical watch evidence and unresolved motorized capabilities belong to T7 and do not gate phone phases T1–T6. No source/API assessment is described as device acceptance.

### T1 — Durable data and detailed routes

- [ ] Implement versioned activity, route snapshot, segmented positions, telemetry, event, and checkpoint storage with bounded IO and custom history queries.
- [ ] Define write-loss bounds, flush/finalization behavior, migration, capacity failures, deletion, and orphan recovery.
- [ ] Add detailed GPX route import preserving route/track grouping and segment gaps. Enforce explicit byte/point/segment limits.
- [ ] Retain source geometry and separate render simplification; adapt existing selected annotation lines to routes without changing package ownership.

Acceptance: realistic detailed 5–30 km fixtures round-trip without silent truncation; gaps survive import/storage; interrupted writes recover; deleting a map package preserves application-owned activities/routes.

### T2 — Recording and lifecycle

- [ ] Implement one activity owner, shared location distribution, accepted positional recording, telemetry/event recording, and explicit quality states.
- [ ] Add Android foreground-service ownership and iOS background-location integration with appropriate permissions and native status.
- [ ] Implement start/pause/resume/finish, locked-screen recording, durable interruption recovery, and storage-failure handling.
- [ ] Stop all telemetry/path recording during manual pause; retain lifecycle events and elapsed time; start a new path segment on resume.
- [ ] Compute elapsed, recording, moving time, and distance without counting duplicate fixes or bridging gaps.

Acceptance: routed and route-free activities record through screen lock and leaving the map; ordinary non-recording maps retain lifecycle cleanup; pause records no telemetry; interruption restores committed data with explicit gaps; denied sensors and write failures have truthful states.

### T3 — Complex-track navigation

- [ ] Implement indexed/bounded route matching using ordered progress, direction, continuity, and quality rather than nearest-point distance alone.
- [ ] Add reverse/intermediate starts, uncertain matching, manual section correction, control points, and planned-versus-actual distance.
- [ ] Add sustained-deviation detection, suppression/rejoin behavior, and throttled alert transitions.
- [ ] Add return along a recorded path with explicit gap handling.

Acceptance: intersection, loop, shared-return-section, parallel-trail, off-route, poor-fix, source-conflict, and segment-gap fixtures preserve sensible progress or report uncertainty. Corrections do not modify recorded samples.

### T4 — Map interface

- [ ] Add route selection/Follow and one Track control with a shared compact preparation panel containing Start.
- [ ] Add collapsed/expanded activity presentation, primary metrics, pause/resume, overflow actions, and finish confirmation.
- [ ] Add follow/free camera state, Return to position, route/recording overlays, and compact quality/deviation states.
- [ ] Integrate existing coordinate readout, callouts, controls, portrait/landscape insets, accessibility, and immersive gestures.

Acceptance: both start paths are discoverable on the map; normal activity display stays compact; panels do not overlap critical controls; free inspection and immersive mode preserve recording and guidance. Phone functionality requires no watch.

### T5 — History and reusable routes

- [ ] Add an accessible activity-history entry point and paginated summaries, with completed/interrupted distinctions.
- [ ] Add map/chart-linked review, available phone telemetry statistics, quality gaps, pauses, and control points. Leave absent heart rate unavailable until a source exists.
- [ ] Automatically derive a reusable segmented route after route-free completion; support optional derivation from routed activities and degenerate recordings.
- [ ] Add renaming, deletion, route export, and recorded-path sharing with explicit payload contents and independently retained health data.

Acceptance: route-free completion produces one valid reusable route when geometry permits; interrupted finalization cannot duplicate it; chart/map timestamps agree; exports preserve declared geometry and gaps without claiming omitted telemetry. History/export is complete without Apple Watch and can consume its samples later.

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
| Manual pause | Owner confirmed full telemetry/path stop; no selectable pause policy; resume creates a segment; late delivery uses acquisition-time boundaries | Implement T2/T7 |
| Moving time | Separate monotonic owned intervals; no automatic pause; movement hysteresis awaits measured profile tuning | T2 statistics |
| Application-owned spatial data | Selected independent activity/route SQLite stores plus repairable Room projection; no package cascade | Implement T1 |
| Module boundaries | `domain:activity` registered; viewer-owned activity/history; future core native adapter | Adapter creation |
| Apple Watch UI | Owner approved SwiftUI only for watchOS; production integration follows all phone phases | Implement final T7 |
| Apple Watch per-profile heart rate | Hiking probe source/API path established; continuous motorized HR and background alerts unresolved, no fabricated workout | Physical feasibility/T7 commitment |
| Map/history entry points | Offline viewer plus activity-only vector map; Activities callback from library; constructor unchanged | Implement T4/T5 |
| Metric defaults | Hiking pace; motorized speed; compact elapsed/state; fixed expanded presets initially | Implement T4 |
| Data-quality policies | Explicit provisional age/accuracy/gap/live-HR gates in section 8.3; profile movement/deviation/elevation tuning requires evidence | T2/T3 |
| Import/export limits | 32 MB/100 routes/200k total vertices/2k total segments and control points; GPX segmented geometry and explicit JSON telemetry | Streamed importer T1; codecs T5 |
| Recovery guarantees | Bounded commits, explicit resume/new generation/segment after interruption, idempotent finalization; no force-quit continuation guarantee | Implement T1/T2 |
| Wearable buffer policy | 24h/16 MB, bounded packets/chunks, acquired-time ingestion, durable sample acknowledgment, five-second commands without offline replay | Implement T7 |
