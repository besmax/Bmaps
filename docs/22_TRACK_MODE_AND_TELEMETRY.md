---
type: "feature_specification_and_implementation_tracking"
project: "Bmaps"
purpose: "Define track navigation, route-free recording, telemetry, Apple Watch integration, and their implementation phases."
---

# Track mode and telemetry

Updated: 2026-10-19. This document owns track-mode behavior, contracts, limits and feature-local implementation status. [Current state and evidence](#9-current-state-and-evidence) separates source implementation, automated verification and device acceptance. Under the [verification policy](../AGENTS.md#verification-policy), agents run focused tests without devices; the owner retains project builds and every device/emulator/simulator run. Ask before running the full test suite.

Sections 1–7 define product behavior; section 8 defines current contracts and implementation limits; section 9 owns the T0–T7 status and verification handoff. Section 10 retains acceptance targets and remaining work until full feature acceptance. Keep current behavior in place rather than appending activity change history or superseded designs. Once the complete feature is accepted, remove section 10 after retaining enduring constraints and verification evidence in sections 1–9.

## Contents

- [1. Scope](#1-scope)
- [2. Product entities and ownership](#2-product-entities-and-ownership)
- [3. Map entry points and layout](#3-map-entry-points-and-layout)
  - [Start with a route](#start-with-a-route)
  - [Start without a route](#start-without-a-route)
  - [During an activity](#during-an-activity)
- [4. Route navigation behavior](#4-route-navigation-behavior)
- [5. Recording, telemetry, and lifecycle](#5-recording-telemetry-and-lifecycle)
- [6. Apple Watch](#6-apple-watch)
- [7. Completion, review, and reusable routes](#7-completion-review-and-reusable-routes)
- [8. Architecture constraints](#8-architecture-constraints)
  - [8.1 Implemented domain contracts](#81-implemented-domain-contracts)
  - [8.2 Selected storage and consistency design](#82-selected-storage-and-consistency-design)
  - [8.3 Start, clocks, lifecycle, and recovery](#83-start-clocks-lifecycle-and-recovery)
  - [8.4 Wearable protocol and retention](#84-wearable-protocol-and-retention)
  - [8.5 Platform feasibility assessment and source probe](#85-platform-feasibility-assessment-and-source-probe)
  - [8.6 Presentation ownership](#86-presentation-ownership)
  - [8.7 Persistence and detailed GPX import](#87-persistence-and-detailed-gpx-import)
  - [8.8 Phone recording and recovery](#88-phone-recording-and-recovery)
  - [8.9 Route matching, progress and recorded return](#89-route-matching-progress-and-recorded-return)
  - [8.10 Map preparation and tracking UI](#810-map-preparation-and-tracking-ui)
  - [8.11 Activity browsing, review and sharing](#811-activity-browsing-review-and-sharing)
  - [8.12 Map-owned route storage](#812-map-owned-route-storage)
- [9. Current state and evidence](#9-current-state-and-evidence)
  - [Authored verification coverage](#authored-verification-coverage)
  - [Verification handoff](#verification-handoff)
- [10. Temporary implementation plan](#10-temporary-implementation-plan)
  - [T0 — Contracts and platform feasibility](#t0--contracts-and-platform-feasibility)
  - [T1 — Durable data and detailed routes](#t1--durable-data-and-detailed-routes)
  - [T2 — Recording and lifecycle](#t2--recording-and-lifecycle)
  - [T3 — Complex-track navigation](#t3--complex-track-navigation)
  - [T4 — Map interface](#t4--map-interface)
  - [T5 — History and reusable routes](#t5--history-and-reusable-routes)
  - [T6 — Phone acceptance and performance](#t6--phone-acceptance-and-performance)
  - [T7 — Apple Watch integration and final closure](#t7--apple-watch-integration-and-final-closure)
  - [Remaining decisions and calibration](#remaining-decisions-and-calibration)

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

Routes belong to a map package: manually drawn lines remain in its annotation database, and imported/derived detailed routes occupy its registered `routes.json` auxiliary asset. They travel with package export/import and disappear on package deletion. Activities, recorded telemetry and frozen planned/return snapshots remain independent and readable after source deletion. The private route SQLite store holds internal derivation snapshots and unassigned routes supported by the repository; unassigned routes must be copied into a selected map before map preparation. [Section 8.12](#812-map-owned-route-storage) owns asset limits, identity, consistency and active-map deletion protection.

At most one phone-owned activity is active at a time. The phone owns activity state, navigation, and the primary recording. Apple Watch owns its sensor session and temporary telemetry buffer, but does not become an independent activity authority in the first version.

## 3. Map entry points and layout

### Start with a route

Both entry paths remain on the map:

1. Select a route line on the map or a route in the existing Map objects panel.
2. Open a small temporary route panel showing its name, length, and **Follow** action. Existing object editing remains available through object actions.
3. **Follow** opens the start-settings dialog over the map.
4. Select profile, direction, and starting position on the map; inspect any coverage/segment-gap issues and the phone-only wearable notice.
5. Press **Start** in the dialog. Successful submission opens the dedicated tracking screen; the ordinary map retains an icon for returning to the active track.

A GPX track remains one selectable route with explicit segment gaps. Preparation shows route distance and coverage/gap warnings without exposing storage indices. [Section 8.10](#810-map-preparation-and-tracking-ui) defines the dialog and map position picker.

### Start without a route

The lower-left **Track** map icon opens the start-settings dialog directly when no activity is active. Free recording requires no selected route; route selection opens Map objects and returns to the same dialog. Start is an explicit dialog action. During an activity the icon returns to its dedicated tracking screen.

New recording starts from an opened map. The library top bar and track menu open **Activities**, with completed records and a separate current-owner card. **Routes** opens reusable/unassigned route management. Historical review can use a neutral vector map after source-map deletion; opening a list or map never starts recording. The package-free tracking entry supports active-owner recovery. Online constructor navigation is outside this integration. Navigation ownership and destinations are defined in [document 7](7_FEATURE_SHELL.md#activity-navigation).

### During an activity

- The ordinary map Track icon returns to the dedicated tracking screen.
- The tracking screen has a compact bottom panel with speed/time, lifecycle state, **Pause/Resume** and an overflow menu; expanding reveals additional telemetry and route progress.
- The overflow menu provides finish, add control point, activity settings, and access to detailed telemetry. Finish requires confirmation.
- Keep two panel states initially: collapsed and expanded. Do not add one permanent floating widget per measurement.
- Use content-sized translucent surfaces, readable text, adequate touch targets, and the existing visual system. Do not imply that true backdrop blur is already implemented.
- Detailed charts open explicitly; they do not occupy the map during ordinary movement.
- Route warnings are temporary compact messages and status indicators. They must not repeatedly expand telemetry or hide the map.
- Reconcile the panel with the center coordinate/height readout, callouts, right-side controls, attribution, and system insets. Avoid stacking multiple expanded bottom panels.
- Keep the scroll-bounded bottom panel clear of the right control rail and system insets in portrait and landscape.
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

Poor or conflicting position sources must be visible in navigation quality. A fix accepted for a current-location display is not automatically suitable for distance accumulation or route matching. Reuse the existing source-conflict protection; apply the activity-specific freshness, ordering and quality gates in sections 8.3 and 8.9.

## 5. Recording, telemetry, and lifecycle

Record available measurements with their acquisition time, source identity, canonical units, and quality metadata. Preserve original accepted measurements separately from computed statistics and render simplification. GPS speed and a derived positional speed must be distinguishable. Course is movement direction, not compass heading.

Heart rate, location, and elevation may arrive at different frequencies. Missing values are unknown, never zero. Delayed wearable measurements are inserted at acquisition time, not receipt time. Historical samples must not drive live navigation or be displayed as fresh telemetry.

GPS altitude, DEM terrain height, and wearable/barometric height have different provenance. Store available reference metadata and do not mix vertical references or calculate ascent from an unlabeled combined stream. Missing DEM coverage does not stop recording.

Store start/finish, manual pauses/resumes, control points, source availability transitions, and interruptions. Telemetry must be queryable by time and linked to the map where valid location is available. Data collection does not imply that a position exists for every heart-rate sample.

Lifecycle rules:

- The activity owner outlives map/ViewModel composition and owns one continuous location collection; map consumers observe its state instead of opening duplicate recording subscriptions.
- Android requires an appropriate foreground location service and visible native status. iOS requires an explicit background-location lifecycle and capabilities. Native configuration is implemented; supported execution behavior still requires owner-run acceptance.
- Screen lock, panel collapse, immersive mode, and navigation to another app screen do not end an activity.
- System termination or force quit is not a promise of uninterrupted execution. Reconcile durable state and mark missing intervals on recovery; never fabricate continuation.
- Commit bounded batches and lifecycle transitions durably. The batching bounds, flush policy and capacity failure behavior are defined in section 8.2.
- Reject duplicate/out-of-order positional contributions to distance. Preserve independently timestamped telemetry without repeatedly counting the same measurement.
- Location loss does not automatically pause the whole activity. Time and available heart rate continue; location-dependent speed/progress become unavailable.
- Start a new recorded segment after a meaningful location gap. Do not connect absent positions with a measured straight-line journey.
- Storage failure must be explicit. Do not continue presenting a healthy recording state after durable writes fail.
- Denial or loss of heart-rate access does not block location recording.

Manual pause stops recording the path, distance, heart rate, speed, elevation, and all other telemetry. Pause/resume lifecycle events and elapsed time remain tracked. Resume starts a new recorded segment. This is a fixed first-version rule, not a selectable pause policy. Live position may remain available for map inspection, but paused measurements do not enter the activity recording. Automatic pause is deferred.

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

SwiftUI is approved only for the Apple Watch companion's watchOS UI. Android/iOS UI remains Compose, and AGENTS.md/document 2 record that scoped exception. The official platform matrix lists watchOS for Kotlin, but not for Compose UI. T0's headless Swift probes do not change production Xcode targets; the SwiftUI watch target is T7 work. See section 8.5 for the API assessment and actual evidence.

## 7. Completion, review, and reusable routes

Finishing durably closes the activity and sensor ownership before presenting its summary. Retries/recovery must not create duplicate activities or reusable routes.

The summary includes planned and actual geometry, measured distance, elapsed/recording/moving times, available speed/elevation/heart-rate statistics, control points, pauses, and gaps. Map selection and chart selection refer to the same timeline. Missing intervals remain visible and must not be interpolated as recorded facts.

A route-free activity automatically creates a reusable route in its source map from its accepted path segments. Naming can default to date/time; renaming is optional. A degenerate activity without usable geometry remains a valid history entry but must not create an invalid navigable route. Deriving a route from a routed activity is an explicit action.

Reusable geometry retains gaps. Display simplification must not overwrite the recording. Route export and activity export must disclose what is included; exporting geometry alone must not claim to include heart-rate telemetry.

## 8. Architecture constraints

Module ownership is defined in [document 3](3_MODULES.md); the following contracts describe the current implementation.

- `domain:activity` owns activity/route/telemetry contracts, independent validation, and pure lifecycle policy. T1–T3 implement repository adapters, statistics/session ownership and route navigation in this module.
- `core:location` owns native sensor distribution; `core:storage` owns low-level IO. Phone foreground-service/background lifecycle bridges belong to the domain-owned session. Future wearable infrastructure must expose core/platform APIs without domain imports.
- Domain repository adapters can compose core storage/database APIs, following the existing repository pattern.
- Room KMP holds repairable activity/route catalog projections. Activity records, snapshots and telemetry are independent of packages; user route assets and manual annotations are map-owned. Storage boundaries are defined in sections 8.2 and 8.12.
- Viewer map controls and state belong to viewer-owned capability packages. History placement/ownership must not introduce feature-to-feature dependencies.
- All display state maps to truthful immutable presentation models. Extract panel/list/item components and give children only the fields they need.
- Presentation invokes input validation independently; structural use cases retain a single responsibility.
- `:shared` assembles DI and native entry points only; it must not own activity policy or recording logic.
- Build configuration uses convention plugins and catalog dependencies. Register actual new modules in document 3 when introduced.
- Coordinate rendering and geographic primitives reuse `core:map-engine`; route/session policy stays outside the renderer.
- Activity deletion and export explicitly cover sensitive route/health data and associated buffers. Coordinate or heart-rate logging must not be added as routine diagnostics.

### 8.1 Implemented domain contracts

`domain/activity` applies the existing Android/KMP, serialization and DI conventions. It contains repository adapters, the phone session and platform lifecycle bridges, uses renderer-independent coordinates, and generates no framework. The umbrella contributes bindings and native graph entry points; feature screens remain in `feature:viewer`.

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

The following private spatial databases live outside package folders:

- `activities/activity.sqlite`: activity records, route snapshots, accepted position/non-positional samples, events, interval/segment boundaries, durable checkpoint, active-owner slot, and command deduplication/acknowledgments.
- `routes/routes.sqlite`: internal derived-route snapshots/identity and unassigned route records, ordered segments/vertices, control points and revisions. New GPX imports and map-owned user routes use the package asset described in section 8.12.

Room holds activity/route catalog metadata for custom history/filter queries, but never becomes the authority for raw spatial/health samples. Activity and route spatial schemas are version 1; Room is version 4, with a non-destructive 3-to-4 migration. Private databases are outside `.bmaps` inventory and package disk-size accounting. Package `routes.json` belongs to both inventory and accounting; deleting a package removes those user routes and their catalog rows while preserving independent activity records/snapshots. Active-map protection is defined in section 8.12.

Use bundled SQLite through core-owned primitive records and kotlinx-io path infrastructure. Initial policy is serialized transactions, `synchronous=FULL`, and the established DELETE journal mode; any measured change to WAL requires explicit concurrency/recovery documentation. The activity database's single active-owner slot and activity creation commit atomically, so two start requests cannot create two active activities. Room projection writes follow authoritative commits; startup reconciliation repairs missing/stale catalog rows using stored revisions.

`ActivityCommit` uses a compare-and-set `expectedRevision`; the next durable record revision increments by one. Record, samples, events, checkpoint, and applied-command identity commit together. Conflicting writes return `RevisionConflict`, not silent replacement. `(activityId, sourceId, sampleId)` deduplicates samples. Activity event sequences are strictly increasing. History order is start time descending/id ascending; sample order is acquisition epoch/source ID/sample ID ascending; cursors use those exact tuples. Queries bound each page to at most 500 records, without generic paging libraries.

Lifecycle control has a separate `controlRevision`, incremented only for control changes, not periodic telemetry commits. Wearable pause/resume compares this revision, preventing normal sample writes from constantly invalidating commands. `sessionGeneration` changes on recovery/resume from interruption and protects commands from a previous recorder instance.

Transaction caps are 256 samples/events and 256 KiB of encoded input. T2 flushes before its smaller buffer exceeds 128 samples or 128 KiB of encoded samples, bounds quality-event buffering, and schedules commits once per second. Start, pause, resume, finish, and error/interruption transitions flush immediately. The one-second interval is a scheduling target, not a guarantee while the OS suspends execution; only committed batches are durable. Capacity/IO failure ends healthy recording state, preserves committed data, and requires explicit recovery. Never acknowledge wearable samples before their transaction commits.

Finish first records `FINALIZING` and its stop boundary, releases native recording ownership, then derives a route if required. Derivation uses the predetermined route ID and activity ID as a unique key in the route store. A retry returns the existing derived route rather than creating another. Completion records that link and `COMPLETED`; finalization can resume on startup without resuming sensor recording. There is no claimed cross-database atomic transaction. An interrupted catalog/link update is reconciled from derivation identity. Deleting an activity does not delete an independently saved reusable route; removing associated pending wearable samples must prevent late delivery from recreating the deleted activity.

Detailed GPX limits are 32 MB UTF-8 input, at most 100 resulting routes, 200,000 vertices, 2,000 segments and 2,000 control points across the import document, with the same geometry caps on each stored route. The streamed importer retains XML depth/entity protections and counts limits before allocating the complete geometry. Existing annotation limits remain unchanged. Oversized routes fail explicitly; no implicit truncation or simplification. GPX export preserves track segments; health telemetry is an explicit separate versioned JSON payload rather than undocumented vendor GPX extensions. Storage/import and streamed GPX/JSON export codecs are implemented; verification state is in section 9.

### 8.3 Start, clocks, lifecycle, and recovery

Start requires a visible user action, an available native location service, and appropriate precise location permission. Optional heart-rate denial does not block start. A missing initial fix is allowed: the activity begins in RECORDING with unavailable position/progress and no invented path. The session invokes structural validation; Preparation presentation independently validates input before calling the owner.

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

### 8.6 Presentation ownership

`feature:viewer` owns `activity.ui`/`activity.presentation` for map preparation/live controls and `history.ui`/`history.presentation` for review. These packages are implemented. `feature:shell` exposes the library top-bar Activities callback; `:shared` wires history/viewer destinations and no feature imports another feature. An activity-only viewer has optional map-package context and remains available after package deletion. Reuse map-engine geometry/camera contracts without moving map constructor behavior into this workflow.

The dedicated tracking panel shows speed, recording time and lifecycle state when collapsed. Expanded fields include actual distance, planned-route progress, course and available live heart rate. Metric customization is deferred until the fixed presets/layout pass acceptance. Coordinates, DEM readout, callouts, and activity panels share a visibility/layout policy rather than independent overlapping expansions.

The T2 activity owner is application-scoped through Metro with platform service ownership. `core:location` supplies raw native observation and retains source selection/quality behavior, with T2 uptime/provenance/altitude metadata extensions. The map widget and owner use `SharedLocationService`; foreground maps release their consumer on lifecycle stop, while recording retains its background consumer. Native wearable infrastructure is a separate future core adapter, not a dependency on viewer or domain; source DTO mapping happens in the activity domain adapter.

### 8.7 Persistence and detailed GPX import

`LocalActivityStore` is application-scoped and serializes both activity and route repositories. The umbrella contributes interface bindings and graph entry points only. `ActivityFileStorage` receives the native application storage root: Android filesDir and iOS Application Support. The activity/route directories are siblings of `packages`, not members of any package inventory. Fixed paths and journal companions are checked for symlinks before opening.

`ActivityDatabase` and `ActivityRouteDatabase` expose primitive rows in core; JSON semantic mapping, validation, pause rules and route derivation stay in domain. Both spatial databases begin at schema version 1; a new empty version-0 database is initialized transactionally. Unsupported nonzero versions fail without destructive fallback. A nonempty unversioned database also fails instead of being overwritten. SQLite writable reopen recovers hot journals; first access and explicit reconciliation use quick/foreign-key checks. Cancellation before transaction commit rolls back; once SQLite commits, a catalog or directory-sync failure can still return a failure, and the next reconciliation repairs its projection.

Activity headers exclude the route selection payload. Frozen selections occupy `route_snapshots` once per activity; periodic commits update small headers, samples, events and checkpoint without rewriting that geometry. The store caches only the current owner's decoded snapshot and returns owned collection copies. Route JSON is bounded to 64 MB; activity headers and individual row payloads to 64 KiB. Read queries bound text before decoding. History/catalog pages return scalar summaries and never load all route geometries.

The checkpoint records the last event sequence, position epoch/monotonic/continuity anchor, segment ID and pending segment-break flag. New positions require strictly advancing measurement time within a known clock continuity; wall-clock rollback does not override a valid monotonic order. Pause/interruption/position-loss events and a >10-second position gap forbid reuse of the same path segment. Re-entering an already closed segment ID is rejected. Positions are read in durable insertion sequence for route derivation, independently of chart epoch ordering.

Pause eligibility uses durable lifecycle events plus any events in the pending atomic commit. Local measurements compare monotonic anchors when available; remote/unknown-continuity measurements use acquisition epoch, subject to later source-quality/clock policy. The repository accepts telemetry acquired in recording intervals only. A measurement falsely labeled RECORDING but acquired during a pause is rejected. Pre-pause non-positional data delivered after resume remains eligible. Position recording after finalization is rejected; late eligible non-positional data can update a completed history entry without reopening it. Wire ignored-sample acknowledgments and interval-overlap handling remain T7 work.

Compare-and-set protects both activity and route revisions. Activity control revisions change only with lifecycle state; generation changes only for explicit recovery from interruption. Initial create uses a stable content fingerprint; retrying the same create returns the existing record. Command-bearing commits retain fingerprint/result metadata, so identical retries return the original result without repeating events or samples. Same sample identity/content is deduplicated within/across batches; conflicting identity payloads fail and roll back. Batches accept at most 256 samples, 256 events and 256 KiB combined encoded header/event/sample input. The recorder uses the one-second flush scheduler; every repository commit is synchronous/durable.

Room schema 4 adds `activity_catalog` and `activity_route_catalog`, without package foreign keys. The explicit 3-to-4 migration preserves package/job/preferences rows. Startup/explicit reconciliation scans authoritative headers and route summaries in 500-row pages, stamps projection generations, and prunes stale catalog entries only after a successful scan. Stale/missing Room rows do not delete spatial data. Generated schema 4 is present. Schema review and native migration execution remain pending; no identity hash is hand-authored.

Activity deletion is refused while recording, paused or finalizing. Deleting completed/interrupted activities transactionally removes their samples/events/snapshot/checkpoint/command data and owner slot, and retains an ID tombstone so stale writes or repeated creation cannot resurrect them. Independently saved routes remain. Route deletion retains derivation identity/tombstones to prevent finalization retries recreating an explicitly deleted route. `secure_delete=ON` is configured; this is a SQLite policy, not a physical flash-erasure guarantee.

Route-free derivation is available for finalizing/completed activities. It reads bounded position pages, preserves segment boundaries, removes consecutive duplicate coordinates and omits non-navigable one-point segments. An activity without any valid segment returns no route. Geometry resource limits fail explicitly; no implicit truncation occurs. Activity ID uniquely binds its derived route across retries; independently updating/deleting an original planned route never changes the activity snapshot. The phone session orchestrates automatic derivation and completion; history exposes explicit reuse actions. Map-owned derivation also writes the result into the source package asset (section 8.12).

`ActivityRouteGpx.decode(RawSource)` bridges a bounded strict UTF-8 stream into XMLUtil's catalog-pinned generic reader; it does not retain an XML tree or close the caller's source. `decode(String)` is a bounded convenience wrapper. Limits apply to the complete import: 32 MB, 100 routes, 200,000 source positions, 2,000 segments and 2,000 named control points; XML nesting is at most 64, elements at most 2,000,000, and attributes at most 128 per element. DTD/entity declarations, malformed UTF-8, unrelated root namespaces/versions, duplicate supported text properties, invalid coordinates, and degenerate segments reject the complete result. A detailed route batch is saved by one route-store commit (SQLite transaction or package-asset CAS); an ID/revision collision cannot partially save that batch. A mixed route/marker import has separate commits and explicit partial-result/retry state.

Supported GPX mapping is `rte/rtept` to one route/segment and each `trk` to one route retaining all `trkseg` gaps. Route name/description/comment fallback, optional vertex elevation and explicitly zoned timestamps, and named route/track points are retained. Imported heights use `GPX_UNSPECIFIED` vertical reference; they are never silently labeled as a DEM datum. Consecutive duplicate positions keep the first vertex metadata. `decodeDocument` also returns top-level waypoint DTOs for the unified Map objects importer, including waypoint-only documents; `decode` requires at least one route. At most 1,000 waypoints are allowed. Vendor extensions are ignored. Map objects maps waypoints into package annotations and confirms all input before writes. Detailed mapping and partial-save/retry behavior belong to [document 15](15_ANNOTATIONS_AND_GEOJSON.md#unified-object-import-and-map-owned-routes).

`ActivityRouteAdapter.line` accepts already mapped line vertices without importing map-builder annotation models. `ActivityRoutePreview.create` makes a separate bounded overview geometry with source indices, preserving segment endpoints and control-point edge endpoints. Its deterministic sampling has no geometric error bound; it is an overview helper, not route-matching or measurement geometry. Navigation/storage continue using the complete route. UI rendering uses this preview without changing stored geometry.

### 8.8 Phone recording and recovery

`PhoneActivitySession` is the application-scoped phone owner, bound through Metro as `ActivitySession`. Android application creation and the existing iOS initialization entry point initialize recovery without starting recording. Start/control/recovery serialize with sensor ingestion and flushes in the owner's scope, so leaving a map or cancelling a caller does not cancel an accepted control transaction. Preparation/control UI uses the independently validating viewer ViewModel described in section 8.10.

`SharedLocationService` distributes one native subscription to the map widget and recorder. Requests combine minimum interval/distance hints and enable background mode only for a recording consumer. Identical effective requests retain the subscription. Distribution preserves sequential updates rather than conflating them to a latest-state value; iOS forwards each supplied native location in a batch. Stopping map collection clears map presentation but retains the recorder; stopping the last consumer releases native listeners. Effective-policy changes and explicit map Retry/permission refresh restart the native stream and publish waiting/quality states.

Phone fixes carry native uptime, provider identity, optional altitude/vertical accuracy and speed accuracy. Android uses acquisition `elapsedRealtimeNanos`; iOS maps Core Location timestamp age to uptime. Map selection/freshness/order use uptime when available, retaining epoch fallback for older/test fixes. Activity continuity is a process-specific identity. Accepted points must be valid, at most five seconds old, no worse than 50 m accuracy, free of source conflict, strictly chronological and inside the current recording/quality boundary. Future phone acquisitions are rejected rather than counted before a pause boundary. A missing local uptime anchor can be mapped from epoch age; no phone anchor is invented for remote health samples.

Each qualified fix retains position and available valid speed, course and altitude with independent sample IDs, acquisition/receipt anchors and provider provenance. Altitude uses `PHONE_UNSPECIFIED`, rather than silently combining it with DEM heights. Missing speed stays missing. The initial recorder takes these channels together from qualified fixes; poor/conflicting fixes do not produce apparently trustworthy phone telemetry. Heart rate is explicitly unavailable until T7; there is no production HealthKit/BLE/watch producer.

The one-second scheduled flush commits samples, quality events, statistics and checkpoint together; thresholds flush earlier. Pending samples are bounded to 128/128 KiB; quality transitions flush before 120 pending events. Native callback and shared distribution queues are also bounded to 256 updates each. A native queue overflow or terminal subscription exception produces an explicit delivery-gap signal; the owner commits a gap/interruption and requires recovery instead of silently losing telemetry. Repository transaction caps remain independent. Start/control/interruption boundaries flush immediately. The interval is a scheduling target, not an OS suspension guarantee; only committed batches are durable. Write failure stops native ownership, clears uncommitted input and exposes `ActivityLiveState.failure` with the last durable record/statistics. A still-persisted RECORDING header does not indicate a healthy runtime after failure. Explicit recovery reconciles it to interruption when storage becomes available.

Pause persists its boundary and removes the recorder's sensor consumer. Late callbacks and cached acquisitions before resume are rejected. Pause adds elapsed time only: no path, speed, course, altitude or future health samples. Android retains its paused service notification; a separately visible map may retain its foreground consumer. iOS releases background location during pause and requires foreground resume. Resume/quality recovery create new segments. Quality/source-conflict/loss and long-gap events are explicit. GPS loss alone retains RECORDING with unavailable position; permission or native-owner loss interrupts recording.

Android owns a non-exported `location` foreground service, native foreground-service permissions and a low-importance ongoing notification. The notification observes live session snapshots and silently refreshes changed display values, including recording time at whole-second precision. Its title is the activity name (with an unnamed fallback); the summary contains recording/paused status, profile and navigation/location status. The compact metrics show recording time (HH:MM:SS), recorded distance and available remaining route distance, with locale-aware kilometer values. Expanded text repeats the summary/metrics and adds available current speed in km/h. Free recording omits remaining distance. Pause preserves recording time/distance and suppresses live speed/remaining distance; unavailable or untrusted position suppresses those measurements and shows No GPS. Route uncertainty and segment gaps are explicit rather than reported as on route. English and Russian labels are provided. Pause/Resume and Finish actions carry immutable activity ID, generation, control revision and a deterministic command identity; stale actions cannot target a replacement activity. Tapping the notification opens the active-owner tracking destination through the native host/umbrella navigation wiring, including an existing Activity receiving a new intent. Start checks fine permission, enabled location and a resumed activity, and waits for service foreground promotion. Resume from the paused notification uses the retained service; without an existing service a new start/resume still requires foreground ownership. `START_NOT_STICKY` and no boot receiver prevent auto-restart; a control intent without an active owner stops the service. Notification permission is declared but not required to record; the viewer offers a contextual Android 13+ request because denial limits notification visibility. The Application exposes graph-backed service/session properties only; business policy stays in `domain:activity`.

The iOS app declares location background mode and an updated usage explanation. Recording configures Core Location background updates/indicator and disables automatic pausing. The native host checks foreground state, services, When In Use/Always authorization and full accuracy without prompting or requesting Always. Reduced accuracy/authorization loss during recording interrupts the owner; ordinary map requests retain foreground cleanup and reported reduced accuracy. Physical locked-screen delivery remains unverified. API references: [Android foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android location service type](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [Apple authorization](https://developer.apple.com/documentation/corelocation/requesting-authorization-to-use-location-services), and [Apple background configuration](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates).

`ActivityRecord.statistics` persists elapsed/recording/moving/interrupted time and distance. Owned time uses monotonic deltas; pause adds no recording/motion, and cross-continuity downtime is estimated without filling motion. Movement uses consecutive points in one segment at most ten seconds apart and horizontal geodesic distance. Initial speed hysteresis starts hiking at 0.5 m/s and motorized profiles at 1 m/s; stop thresholds are half those values. Without native speed, displacement must exceed both 3 m and half the sum of accuracy radii. Displacements below 1 m do not count. These heuristics await device calibration; movement-filtered distance can differ from reusable-route geometry length. Moving time is capped by recording time; distance/time never bridge segments.

Recovery marks persisted RECORDING/PAUSED interrupted from their last committed `updatedAt`, a conservative ownership boundary. Downtime is outside recording/moving time. Explicit resume rotates generation and starts a segment. FINALIZING retries derivation/completion without sensors. Route-free finish uses deterministic `recorded-<digest of activity ID>` identity; insufficient geometry yields no reusable route. Finish acknowledgment is the durable FINALIZING transition; completion is exposed separately in session state. Stored command results retain `lastCommand` to reject an identical ID carrying a different payload.

Owned snapshots use persistent immutable collections. Periodic commits reuse unchanged trusted geometry while validating headers; new geometry receives full validation. This avoids copying/scanning 200k vertices every tick. Domain contracts are not annotated presentation models. Statistics/command payload additions have readable defaults in activity version 1 and require no spatial/Room schema increment.

### 8.9 Route matching, progress and recorded return

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

Control points are visited once per route in an activity, using confirmed proximity or continuous passage on the active segment, with at most eight point events per fix. Manual correction does not mark skipped points visited. Their durable events restore visitation after recovery and returning to the original plan. Distinct gap/end/deviation/rejoin transitions and rate-limited reminders are written with the sample/header checkpoint. Navigation alerts publish through a bounded, non-replaying flow only after successful persistence. Quality loss, pause, finish and navigation changes suppress pending transient alerts while retaining their timeline events. Alerts can be disabled independently; durable deviation/rejoin history remains. Viewer presentation consumes foreground phone alerts (section 8.10); watch delivery remains T7.

`ActivitySession.navigate` accepts generation-checked commands with a separate navigation revision: correction, return along recording, restoration of the original planned selection, and alert enablement. Exact repeated command identity returns its original persisted navigation state; conflicting reuse or stale revisions/generation fail. Navigation controls do not change lifecycle control revision, recorded positions, actual-distance statistics or the original planned snapshot. `ActivityValidation.navigationCommand`, `navigation` and `validRoutePosition` are available for independent presentation validation.

`RecordedReturnRoute` reads committed positions in insertion-sequence order through custom 500-row pages, including across wall-clock rollback. Return preparation flushes pending recording first. It freezes original coordinates/timestamps into ordered segments, removes consecutive exact duplicates and excludes isolated non-navigable older segments. The latest segment must contain at least two distinct vertices. It caps inspected source positions at 200,000 and navigable segments at 2,000; excess fails without truncation. The snapshot begins at its last vertex and is interpreted in reverse. Recording continues independently, so later points never mutate the selected return geometry. Repeating Return with a new command explicitly creates a new snapshot. Optional elevation/health rows remain separate telemetry, with no invented vertex-height association.

The initial activity SQLite schema 1 includes `navigation_snapshots(activity_id, id, payload)`. Routes SQLite is schema 1 and Room is schema 4. Unsupported development table layouts are not migrated or deleted automatically; schema creation/reopen still requires owner verification. Frozen return snapshots and their navigation command/header/event commit in one activity transaction; a failed write cannot leave a selected orphan snapshot. Snapshot input has its own 64 MB bound and capacity allowance, independent of ordinary 256 KiB sample/header batch limits. Multiple snapshots can be retained for the activity timeline and cascade only with activity deletion; they do not become reusable-route catalog rows or map-package assets. The current return geometry is cached as an immutable owned snapshot, avoiding decode/rewrite on each GPS update. Activity version-1 headers gain defaulted navigation state/command fields; the new reader accepts older headers lacking these fields.

### 8.10 Map preparation and tracking UI

`viewer.activity.presentation.TrackViewModel` is navigation-scoped, consumes the application-scoped `ActivitySession`, and independently validates preparation and command inputs. Immutable presentation models stay in `activity.presentation`; `activity.ui` components receive display values/callbacks. Clearing the ViewModel never stops recording.

The lower-left Track control uses the shared `MapIconButton`, route glyph, a 48 dp minimum target, safe insets and 16 dp margins. Without an active activity it opens a separate start-settings dialog directly. The dialog includes profile, optional route, direction, starting position, coverage/gap warnings and explicit Start. Route selection uses Map objects; manual-line Follow uses the same dialog and copies geometry into the activity snapshot. Free recording requires no route. Cancel clears preparation; dismissal and Start are disabled during submission, and errors remain visible in the dialog. During an activity the ordinary map icon has an active tint and an Open active track accessibility label.

The preparation/correction dialog displays distance from the stored route beginning and Choose on map, without segment numbers, vertex indices or sliders. The direction-aware route-beginning shortcut resets to the first/last point. Choosing on map hides the dialog and center readout, suspends following, and reserves taps for route selection while allowing pan/zoom. An outlined direction marker and highlighted edge identify the selected passage. A compact bottom panel provides distance, Confirm position and Cancel. Cancel/map Back restores the original position and dialog; confirmation retains the exact edge fraction and returns without starting recording. Profile/direction survive the transition. Recording starts at the real device position, independently of the selected navigation origin.

Hit testing uses full stored geometry in screen space with a 24 dp tolerance. Distant taps show a localized hint and disable confirmation. Adjacent nearby edges form one passage; separate passes/segments offer Switch with the selected passage highlighted. At most 32 nearest passage candidates are retained. Tight loops require zooming in; GPX gaps get no invented connectors. Distance excludes gaps and is measured from the stored beginning even in reverse. Antimeridian-crossing edges remain unselectable, matching overlay limits. Matching scans run cancellably on Default; newer taps cancel older scans. Picker state stays in TrackViewModel across dialog/map transitions. A GPS nearest-start shortcut is not implemented. Full-geometry coverage checks run off the UI thread; missing raster coverage is a warning. Wearable status is phone-only until T7.

Successful Start opens `viewer/{packageId}/track`, reusing `ViewerScreen`/the renderer with a distinct navigation entry. The dedicated screen hides crosshair/center readout and disables map-editing long press. Back retains the session; the ordinary map Track icon returns to the activity's source map even from another open map. Completed live snapshots do not block another preparation or leave an active icon behind.

A bottom translucent, scroll-bounded panel exposes speed, recording time, actual distance, planned-route progress, course, available live heart rate, pause/resume and an overflow menu with explicit Finish confirmation. It stays clear of the right map-control rail. Collapsing retains speed/time, state, pause/menu and location failures. Recording time freezes on pause; details retain elapsed time. Actual travelled distance and planned progress remain separate. Missing/unreliable metrics display unknown; heart rate is omitted without a current measurement. Course requires a fresh non-conflicted map fix, accuracy at most 35 m and speed at least 1 m/s. Actions include explicit interruption recovery, an idempotent manual timeline point, section correction, alert enablement, frozen recorded return and restoration of the original plan. A manual point references a position only when live and changes neither lifecycle nor planned control-point geometry. Route end proposes finish without finishing automatically.

Foreground map location remains subscribed during pause, while the recorder's telemetry consumer stops. These fixes update only the map/arrow. The blue navigator arrow retains the last trustworthy orientation while stopped, begins as a neutral dot without orientation, and becomes neutral when location is unavailable. Shortest-angle smoothing and a 350 ms visual transition apply; north remains up. Lost/conflicted fixes prevent following.

Following uses a central free area (30–70% of width, 25–60% of height). Leaving it starts a cancellable 300 ms eased move at the current scale, biased above the bottom panel. Pan/pinch beyond touch slop enters manual inspection; a tap or zoom button does not permanently disable following. Return to position explicitly restores it; there is no timer recenter. The core adapter reports pan separately from initial touch/callout dismissal without consuming gestures. Outside the package pyramid, a coverage warning is explicit and centering is limited to that pyramid while recording continues. Immersive mode hides controls/dialogs while preserving vectors, recording and navigation.

`TrackMapLayer` projects and clips separate planned, return-guidance, preview, selected-edge and recorded-path strokes to the current package tile rectangle. It never creates connectors between segments or across the date line; unsupported Web Mercator positions split rendering. Route previews retain at most 10,000 vertices under the existing preview contract; matching still uses the complete snapshot. The incremental recording display keeps at most 20,000 points and the latest 2,000 segments, thinning only presentation geometry while retaining each displayed segment's endpoints. Older segments beyond that display budget are omitted; all raw samples remain stored. The insertion-sequence page also returns `lastSequence` on a nonempty final page so polling can advance without repeatedly rereading it; `nextSequence` retains its existing pagination meaning. Committed positions refresh approximately once a second, with catch-up pages read in batches of 500. This is a rendering budget, not a recording limit or measured performance result.

Durable navigation alerts become transient Channel events. While the viewer is RESUMED, meaningful transitions trigger the Compose haptic effect and a compact snackbar; immersive mode retains haptics and suppresses visible messages. New alerts replace earlier transient messages, and none are replayed after leaving/backgrounding. There is no separate background sound/haptic service. Physical alert delivery and phone vibration require acceptance; watch alerts remain T7.

### 8.11 Activity browsing, review and sharing

`feature:viewer.history.presentation` owns HistoryViewModel, ReviewViewModel, immutable list/chart/timeline/action models and ActivityReviewReader. UI lists/items, maps, statistics, charts and dialogs receive display values/callbacks. The shell/library expose callbacks and the umbrella wires destinations; no feature imports another feature. Successful foreground Finish opens details after durable finalization. Opening any list or review never starts recording.

The library top bar exposes an Activities waveform icon beside Settings. The shell emits a navigation callback and the umbrella opens the viewer-owned `activities` destination, without feature-to-feature dependencies. Track-menu entries use the same list; the library FAB contains only Build and Import. `ActivityListScreen` shows completed recordings newest first through the existing catalog keyset query (50 rows/page), supports name search and Load more, and presents the current non-completed recorder owner in a separate card with its name, profile, status and a return-to-controls action. Empty/error/loading states are explicit. Reusable/unassigned route management is reachable through Routes at `saved-routes`; it uses the existing history UI. Opening either list does not acquire location or start recording.

Activity-list loading progress overlays the top of the list area instead of adding a row between search and the list. HistoryViewModel exposes loading visibility separately from operation busy state: show the indicator only after 250 ms of continuous work and retain it for at least 350 ms once shown. Fast loads and refreshes never flash it; results, errors and interaction availability update immediately when work finishes, independently of the minimum display interval. Replacement searches preserve an already visible indicator and cancel pending visibility when work finishes early. Initial loading, search, paging and refresh after returning from details keep list bounds unchanged. Loaded rows remain visible during refresh. The user reports stable list placement; device acceptance of delayed indicator appearance remains pending.

T2 automatic route-free finalization remains authoritative and idempotent. Degenerate recordings remain valid history entries without a navigable derived route. T5 exposes explicit derivation for completed routed activities and handles deliberate deletion of a derived route: a later explicit derive can replace the stale derivation link with a new route ID in one route-store transaction. Repeated derivation returns that replacement. Existing tombstoned IDs are not resurrected, and the historical activity's original derived-route reference is not rewritten. This behavior uses the existing schema-1 tables; it adds no migration.

`activity/{activityId}` and `viewer/{packageId}/activity/{activityId}` open `ActivityDetailsScreen`. Foreground completion opens the same details destination. The screen uses the existing ReviewViewModel/ActivityReviewReader and map engine, with independent map rendering through the navigation-scoped ViewerViewModel. It shows the activity name, UTC start date, profile/status, measured distance, elapsed/recording/moving/interrupted durations, available speed statistics, source-specific heart-rate summaries, telemetry charts and timeline. Average speed is actual distance divided by recording time, converted to km/h; zero recording duration is unavailable. Maximum speed uses all stored speed measurements read by the reader, independent of chart thinning; no speed samples means unavailable. Heart-rate sample means/minima/maxima remain separate per source, with their existing sample-statistics wording and bpm units. Missing sensors and measurement gaps remain explicit.

The detail map renders the immutable planned snapshot and segmented actual recording, with a shared chart/map/time selection cursor and a measured-position marker. It uses the supplied/source map when available and falls back to the existing neutral vector-only map when opening that package fails or no source exists. Fit full path without background map explicitly switches to the global vector view so the whole path can be inspected beyond the original raster coverage. Recorded/planned vectors use green/purple and have a visible legend. Ordinary current-location acquisition, recording controls, annotation editing and center crosshair are not part of this map. Portrait scrolls the map and detail content; landscape windows at least 700 dp wide use a map pane and a 360 dp scrolling details pane. Rotation/session recreation refits available geometry; chart selections center their measured location. No timed replay, automatic virtual travel or interpolation is implemented.

The details actions menu supports Refresh, Rename, Share/export and Delete as allowed by the stored status. Rename/Delete are restricted to completed activities, validate in presentation, check the current revision, keep errors visible and disable duplicate submission. Rename changes only metadata/revision and preserves recorded timestamps, statistics, snapshots and the selected telemetry cursor. Delete requires confirmation and removes the activity through the existing repository; success closes details and the resumed list reloads. Export reuses the existing explicit GPX/full-JSON payload choices and cancellation/native sharing flow. Action/export failures have a separate presentation field so they do not replace an otherwise loaded detail screen with a read-error screen. Reusable route names have independent revisions/CAS. Activity deletion retains independently saved routes; route deletion retains all frozen activity snapshots. Interrupted owner data must first be finished through controls before UI rename/deletion.

Review loads acquisition-ordered telemetry and insertion-ordered positions in pages of 500 and checks the activity revision again before exposing the result. It shows actual distance and stored elapsed/recording/moving/interrupted time, including an estimated-time label where applicable. Speed, heart rate and altitude charts are separate per source; altitude also separates vertical references. Per-series statistics use all read samples: count, minimum, maximum and arithmetic sample mean, with no claimed time-weighted average or mixed-reference ascent. The headline average speed remains the separate distance/recording-time value defined above. Missing heart rate is explicitly unavailable; phone data does not depend on Watch. Chart runs split at pauses, interruptions, position/telemetry loss, source conflicts, clock changes, unknown monotonic continuity and long measurement gaps. Display reduction retains source measurements and extrema rather than creating interpolated samples.

All charts use the same UTC acquisition-time axis. Selecting a plotted sample, accessible sample slider, timeline event or a recorded map stroke sets a shared time cursor. Recorded-path selections retain the selected sample ID/time through display thinning. Position lookup uses raw samples within five seconds; an exact referenced position is preferred, and ambiguous equally close candidates are unavailable. The map highlights and centers the measured position when supported, with separate labels for the selected time and actual position acquisition time. Health-only samples do not acquire invented coordinates. A temporal neighbor is displayed as a nearby recorded measurement, not an interpolated position. Wall-clock rollback remains visible through actual acquisition epochs and split clock runs; monotonic stored duration totals are not rebuilt from that axis.

Review vectors preserve segments and use the existing clipping/date-line limits. The map keeps at most 20,000 displayed positions across at most 2,000 segments, retaining segment boundaries and endpoint IDs. Each chart keeps at most 1,200 display points; statistics still include all processed samples. Reader limits are two million telemetry samples/positions, 10,000 events and 32 source/reference series. Exceeding a review limit fails explicitly without discarding stored data; export remains independently available from history. These are conservative source limits, not measured T6 performance. Dedicated details uses the portrait/landscape layout above; viewing history, fitting a map or closing review/export does not change the current session lifecycle.

`domain:activity.ActivityExporter` streams caller-owned sinks and `core:sharing.SharedDocumentStore` provides private outbound `.gpx`/`.json` files and the existing native share sheet. Export choices disclose their contents before file creation:

- GPX 1.1 contains the route/activity name, 2D coordinates, separate track segments and available point acquisition times. It excludes altitude, speed, heart rate, control-point events and quality/provenance metadata. Reusable-route export uses its complete copied geometry; recorded-path export reads all accepted positions in insertion order, including degenerate segments, without display simplification. A geometry-only file does not claim to include telemetry.
- JSON identifies `format: bmaps.activity` and `version: 1`, with `activity`, `navigationRoute`, `samples` and `events`. The activity includes its frozen planned snapshot and stored statistics; `navigationRoute` is the current planned/private return geometry. Samples preserve canonical measurement values/units, sources, quality fields, phase, acquisition/receipt times and segment identity. Events retain durable sequence/time and navigation/related IDs. Older replaced private return geometries are not embedded; their event identifiers remain in the timeline. JSON import is not implemented.

Export reads records only when completed/interrupted, verifies the revision at the end and aborts rather than sharing a mixed snapshot when data changes. The UTF-8 output limit is 512 MB per file, writes flush in bounded chunks, invalid XML names fail, and no caller sink is closed. Explicit cancellation cancels the export job; existing share-store cleanup removes partial files. The GPX importer still has its separate 32 MB/200,000-vertex limits, so a large exported recording is not promised to be reimportable as a navigable route. Existing outbound-cache retention remains 24 hours with first-use cleanup, independently of activity deletion; previously shared copies cannot be revoked. Completed outbound files can also be purged by the OS. No active recording stops merely because a review/export UI is closed.

The package-free viewer uses a global Web Mercator pyramid (levels 0–18) with zero raster layers on a themed neutral surface. It installs no tile sources or network providers; package-only layers/annotations/DEM tools are inactive. The source implementation of empty-layer rendering does not establish native acceptance. Verification state is recorded in section 9; Apple Watch remains T7.

### 8.12 Map-owned route storage

`PackageRouteStore` is a primitive core interface implemented by `LocalPackageRepository` and bound through Metro. It reads/writes a package's full JSON route asset under the existing package operation/file locks. Writes compare the prior payload, reserve capacity, mark metadata dirty and atomically replace the asset/manifest at the non-cancellable commit boundary. Reconciliation registers a committed route asset after interruption, updates inventory/size and rebuilds package-prefixed Room summaries. A single-package text cache avoids repeated asset decoding; manual full verification clears it. The asset is limited to 64 MB UTF-8 and 1,000 routes per package; per-route geometry limits remain unchanged. These bounds are storage guards, not performance acceptance.

Route IDs encode map ownership as `map:<packageId>:<routeIdentity>`; archive import validates the route document and rebinds IDs to the newly imported map. Full coordinates, segment gaps, control points, heights and source times are preserved; no display preview is written back. `ActivityRouteQuery.packageId` filters through the core Room query without a schema change; package-projection generations survive independent activity reconciliation. Package-owned derived rows in the private spatial store are internal recording-derived snapshots, not independent user routes. Frozen activity snapshots and recorded telemetry never look up the deleted package during historical review/export.

Map objects loads only its map's routes and renders all catalog pages, independently of list paging, with a bounded preview per route. Selection highlights the route; list selection fits it, while tapping an existing route opens the [shared object callout](15_ANNOTATIONS_AND_GEOJSON.md#unified-object-import-and-map-owned-routes) at the clicked coordinate without replacing the camera viewport. Manual lines use the same Start preparation. Unassigned routes remain available through Routes: using one prompts for a target map and copies its full geometry into that map before preparation. Route-free completion retains its automatic recorded-route derivation into the source map. Editing a source route never rewrites an ongoing activity's snapshot.

The active record persists its source package ID with a version-1 default. A shared `PackageUseGuard` reserves that map before starting and releases it on failed start/completion; deletion checks the guard plus durable active catalog metadata, covering paused/interrupted/finalizing sessions and restart. A failed package-start check releases the reservation. Deletion remains allowed after completion, with independent recordings intact. Activity/route spatial schema versions remain 1 and Room remains 4; no migration or Watch implementation is introduced.

## 9. Current state and evidence

This section is the source of truth for track-mode implementation and verification status. Checked deliverables in section 10 mean source present, not passed acceptance. Keep a current evidence summary here; do not append activity implementation/change history. Update behavior and contracts in place. Agents select focused host tests; the owner retains project builds and all device/emulator/simulator runs under the verification policy. Historical unrun results below remain unverified; the new allocation of work is not execution evidence.

| Phase / area | Source implementation | Automated verification / evidence | Device acceptance / next step |
| --- | --- | --- | --- |
| T0 contracts and feasibility | Contracts, lifecycle/validation, wearable DTOs and inactive Apple probes present | Prior owner app build/launch report has no platform or test detail; no passing result for the current feature | Watch feasibility belongs to T7 |
| T1 storage/import | Independent activity/snapshot storage, map-owned route asset, repairable catalogs, detailed GPX and package transfer integration present | Regression source and generated Room schema 4 present; successful current builds/tests unreported | Owner-run schema/migration, IO/recovery, import/transfer and deletion checks |
| T2 phone recording | Shared location distribution, phone session, native background ownership, live Android notification controls/metrics, bounded flush/statistics and recovery present | Notification host tests: 7 passed; session tests: 10 passed, 1 failed (source-conflict availability assertion). Native storage and remaining suites unverified | Owner-run background/lock, permission, pause, interruption and storage acceptance |
| T3 navigation | Full-geometry index, ordered matching, progress/gaps/alerts and frozen recorded return present | Navigation/session/storage regression source present; successful execution unreported | Fixture execution, physical navigation and threshold/performance calibration |
| T4 map/tracking UI | Start dialog, map-position picker, dedicated tracking screen, arrow, follow/free camera and overlays present | Presentation/ViewModel/picker regression source present; static syntax/resource checks reported; builds/tests unreported | Owner-run navigation, camera, layout/accessibility and alerts |
| T5 activity browsing/review/export | Completed list/current-owner card, details, synchronized charts/map, reusable routes and GPX/JSON sharing present | HistoryViewModelTest: 9 host tests passed; viewer source compiled as a test prerequisite. Remaining history/details/review/export/vector suites and application builds unverified | Owner-run mutation, chart/map/share, vector fallback and long-recording checks |
| T6 phone acceptance/performance | Pending | No completed acceptance or measured performance result recorded | Run complete phone workflow before T7 |
| T7 Apple Watch | Production target, sensors, buffering and commands pending; probes inactive | API/local SDK assessment only; probes uncompiled/unrun | Paired physical devices, per-profile capability decisions and final closure |

Prior static checks reported successful Kotlin PSI syntax parsing, resource XML/reference checks, license/provenance checks, documentation/module-boundary checks and whitespace checks. They do not resolve Kotlin/Compose/Metro types, execute native APIs or establish runtime acceptance. The focused command `./gradlew :feature:viewer:testAndroidHostTest --tests '*HistoryViewModelTest'` passed all nine tests with no failures or skips and compiled current viewer source as its prerequisite. These tests cover paging/filtering/search, rename validation and virtual-time loading visibility (fast/slow/long requests, replacements and errors), not native visual appearance. Application builds, other feature suites and complete device acceptance remain unverified.

An unresolved owner report describes a completed route-free activity disappearing from the Activities list after starting another activity from its derived map route; the disappearance persists after application restart. Source inspection has not established whether the authoritative record is missing or the catalog/list omits it. The focused command `./gradlew :feature:viewer:testAndroidHostTest --tests '*TrackViewModelTest.followingRouteAfterFreeRecordingCreatesAnotherActivityIdentity'` passed one test, confirming separate start IDs at the presentation boundary only. `ActivityStorageScenarios.followingRecordedRoutePreservesBothActivitiesAfterReopen` adds checks for the original samples, both completed records and their history entries across reopen, with Android device/iOS wrappers; these native checks remain unrun. An attempted Android host storage wrapper failed before its assertions with `UnsatisfiedLinkError: no sqliteJni in java.library.path` using `./gradlew :domain:activity:testAndroidHostTest --tests '*ActivityStorageHostTest'`; the temporary host wrapper/dependency was removed. Device database inspection and native reproduction remain pending; no fix or data recovery is claimed.

### Authored verification coverage

Android notification verification: `./gradlew :domain:activity:testAndroidHostTest --tests '*ActivityRecordingNotificationTest' --tests '*PhoneActivitySessionTest'` executed 18 tests: all 7 notification tests passed; 10 session tests passed and `cachedDuplicatePoorAndConflictingFixesDoNotAccumulate` failed because availability was `UNRELIABLE` rather than the expected `SOURCE_CONFLICT`. The common recording/location logic was not changed by the notification implementation; the failing assertion remains unresolved. `./gradlew :androidApp:testDebugUnitTest --tests '*ActivityNotificationEntryTest'` passed both host tests for notification intent delivery to an existing Activity and retention of a pending navigation request across recreation. These host tests compile native/shared/application sources as prerequisites and exercise notification resources/actions without an actual recording/storage session. They do not establish full Compose destination navigation, physical notification layout, screen-lock action delivery or background location acceptance; those remain owner checks.


Shared route callout verification: `./gradlew :feature:viewer:testAndroidHostTest --tests '*MapObjectsViewModelTest' --tests '*CalloutLocationViewModelTest' --tests '*AnnotationEditorTest'` passed 15 tests with no failures or skips. New route-action cases cover the clicked anchor/stored height, description display, revision-checked metadata edits preserving detailed segmented geometry and source measurements, and failed-save/delete retry with overlay/list cleanup after success. Existing coordinate/elevation and annotation-editor checks also passed. Viewer source compiled as a prerequisite of these tests; application builds, iOS compilation and native callout appearance/interaction remain unverified.

These sources contain regression scenarios; their presence does not imply execution:

| Sources | Coverage |
| --- | --- |
| `ActivityContractsTest`, `ActivityRouteGpxTest` | Lifecycle/idempotency, full pause stop, clocks, wearable validation, grouped detailed GPX, metadata, normalization and resource limits |
| `ActivityStorageScenarios` (Android device and iOS wrappers) | Single owner/reopen, repeated passage of a derived route preserving both activities/samples/history, atomic rollback, command/sample deduplication, pause boundaries, snapshots, catalogs, cursors, derivation and export fidelity |
| `ActivityRecordingNotificationTest`, `ActivityNotificationEntryTest` | Native summary/metrics, pause state, missing/untrusted data, Russian formatting, immutable control identities and pending native navigation intent delivery/recreation |
| `PhoneActivitySessionTest`, `ActivityRecordingStatisticsTest`, `LocationFixSelectionTest` | Shared distribution, accepted measurements, pause/recovery, overflow/storage failure, monotonic time and segmented movement |
| `RouteNavigatorTest` | Reverse/intermediate starts, crossings/loops/parallel paths, direction/continuity, gaps, deviation/rejoin, alerts, control/end events and bounded large-route search |
| `TrackViewModelTest`, `TrackPresentationTest`, `TrackStartPickingTest` | Preparation/commands, course/metric quality, completed-owner cleanup, overlays, exact start fractions, ambiguous passages and picker cancellation |
| `MapObjectsViewModelTest`, `PackageUseGuardTest`, native package transfer scenarios | Confirmed mixed imports/retry, package-required writes, asset CAS, active-map retention, package ID rebinding and preserved source metadata |
| `HistoryViewModelTest`, `ActivityDetailsTest`, `ReviewBuffersTest`, `ViewerViewModelTest`, `ActivityExportWriterTest` | Paging/search, protected rename/delete, timeline preservation, statistics/chart reduction/gaps, vector fallback, GPX/JSON streaming and caller-owned sinks |

### Verification handoff

Agents choose the relevant host suites for the change; do not run every feature suite for unrelated work:

```sh
./gradlew :core:location:testAndroidHostTest :domain:activity:testAndroidHostTest :feature:viewer:testAndroidHostTest
```

The owner runs project builds and all device/emulator/simulator tests, including:

```sh
./gradlew :domain:activity:iosSimulatorArm64Test :feature:viewer:iosSimulatorArm64Test
./gradlew :domain:activity:connectedAndroidDeviceTest
```

Confirm Android device task naming with the installed Gradle plugin. Review generated Room schema 4 and test the non-destructive schema-3 upgrade. Verify spatial schema creation/reopen, unsupported schemas, journals/cancellation, low storage, atomic snapshot commits and catalog recovery. Package export/import must preserve route geometry/heights/times and rebind ownership; active-map deletion must be blocked, while completed recordings/snapshots survive source-map deletion.

Phone scenarios: start from manual/imported routes and free recording, reverse/intermediate starts, exact map-picked fractions at crossings/gaps, cancellation and failed submissions, pause without telemetry with foreground map updates, lock/background/return, permission/source/owner loss, clock changes, explicit interruption recovery and finalization retry. Check recorded return, section correction without recording mutation, sustained deviation/rejoin, suppressed alerts and no delayed replay.

UI/review scenarios: library top-bar/track-menu and native Back flows, source-map return, repeated recording after Finish, pan/pinch/zoom/recenter, arrow wrap/stop/lost/conflicted fixes, absent heart rate, arrival without automatic Finish, portrait/landscape/large fonts/accessibility. Exercise more than 50 completed records, search/paging, current-owner controls, rename conflicts, confirmed deletion, GPX/JSON sharing/cancellation/native failure and revision/output/storage failures. Compare stored versus exported data; check chart/map/timeline timestamps, source-specific/negative altitude, pauses/gaps and ambiguous/missing measured positions. Test deleted/corrupt/missing source fallback and full-path vector fitting. Measure long recordings, large routes, battery, memory and overlay/matching budgets. No scenario is marked accepted without a reported result.

## 10. Temporary implementation plan

Remove this entire section after implementation and acceptance, moving enduring decisions and actual limits into sections 1–9 first. Phase identifiers below are feature-local and do not renumber the application phases in document 5.

Phase status and verification evidence are maintained only in [section 9](#9-current-state-and-evidence). T1 depends on T0; T2 on T1; T3 on T1–T2; T4 on T2–T3; T5 on T1–T4; T6 accepts the complete phone feature; T7 follows accepted T1–T6 and the T0 API assessment.

### T0 — Contracts and platform feasibility

- [x] Write concrete route, lifecycle, telemetry, quality, query/session/repository, and wearable contracts with independent validation and regression source.
- [x] Define map-owned routes, independent activity/snapshot storage and domain/core/feature ownership; register `domain:activity` and update architecture/contracts/module documents.
- [x] Define start prerequisites, clock/duration semantics, explicit interruption recovery, full telemetry stop on pause, and finalization transitions.
- [x] Complete official API/local SDK assessment of phone background lifecycle, watch platform support, hiking workout mirroring, and motorized-profile capability limits; record proposed OS minima without claiming device verification.
- [x] Author isolated live-heart-rate/mirroring source probes without introducing UI or production capabilities. Keep these existing probes inactive until T7.
- [x] Apply watchOS-only SwiftUI and full telemetry stop on pause; reserve production watch integration and physical feasibility checks for T7.

Acceptance targets: concrete contracts, ownership and phone lifecycle policies; API feasibility constraints remain explicit. Physical watch evidence and unresolved motorized capabilities belong to T7 and do not gate phone phases T1–T6. Source/API assessment does not establish device acceptance; current evidence is in section 9.

### T1 — Durable data and detailed routes

- [x] Implement versioned activity, route snapshot, segmented positions, telemetry, event, and checkpoint storage with bounded IO and custom history queries.
- [x] Define write-loss bounds, flush/finalization behavior, migration, capacity failures, deletion, and orphan recovery.
- [x] Add detailed GPX route import preserving route/track grouping and segment gaps. Enforce explicit byte/point/segment limits.
- [x] Retain source geometry and separate render simplification; adapt existing selected annotation lines to routes without changing package ownership.

Acceptance targets: realistic detailed 5–30 km fixtures round-trip without silent truncation; gaps survive import/storage; interrupted writes recover; deleting an inactive map removes its user routes while preserving activities and frozen snapshots; active-map deletion is refused. User-run verification must establish these runtime targets; current evidence is in section 9.

### T2 — Recording and lifecycle

- [x] Implement one activity owner, shared location distribution, accepted positional recording, telemetry/event recording, and explicit quality states.
- [x] Add Android foreground-service ownership and iOS background-location integration with appropriate permissions and native status.
- [x] Implement start/pause/resume/finish, locked-screen recording, durable interruption recovery, and storage-failure handling.
- [x] Stop all telemetry/path recording during manual pause; retain lifecycle events and elapsed time; start a new path segment on resume.
- [x] Compute elapsed, recording, moving time, and distance without counting duplicate fixes or bridging gaps.

Acceptance targets: routed and route-free activities record through screen lock and leaving the map; ordinary non-recording maps retain lifecycle cleanup; pause records no telemetry; interruption restores committed data with explicit gaps; denied sensors and write failures have truthful states.

### T3 — Complex-track navigation

- [x] Implement indexed/bounded route matching using ordered progress, direction, continuity, and quality rather than nearest-point distance alone.
- [x] Add reverse/intermediate starts, uncertain matching, manual section correction, control points, and planned-versus-actual distance.
- [x] Add sustained-deviation detection, suppression/rejoin behavior, and throttled alert transitions.
- [x] Add return along a recorded path with explicit gap handling.

Acceptance targets: intersection, loop, shared-return-section, parallel-trail, off-route, poor-fix, source-conflict, and segment-gap fixtures preserve sensible progress or report uncertainty. Corrections do not modify recorded samples.

### T4 — Map interface

- [x] Add route selection/Follow, one Track icon, a start-settings dialog and full-geometry map position selection.
- [x] Add the dedicated tracking screen with collapsed/expanded presentation, navigator arrow, primary metrics, pause/resume, overflow actions and finish confirmation.
- [x] Add follow/free camera state, Return to position, route/recording overlays, and compact quality/deviation states.
- [x] Integrate existing coordinate readout, callouts, controls, portrait/landscape insets, accessibility, and immersive gestures.

Acceptance: both start paths are discoverable on the map; normal activity display stays compact; panels do not overlap critical controls; free inspection and immersive mode preserve recording and guidance. Phone functionality requires no watch.

### T5 — History and reusable routes

- [x] Add an accessible activity-history entry point and paginated summaries, with completed/interrupted distinctions.
- [x] Add map/chart-linked review, available phone telemetry statistics, quality gaps, pauses, and control points. Leave absent heart rate unavailable until a source exists.
- [x] Automatically derive a reusable segmented route after route-free completion; support optional derivation from routed activities and degenerate recordings.
- [x] Add renaming, deletion, route export, and recorded-path sharing with explicit payload contents and independently retained health data.

Acceptance: route-free completion produces one valid reusable route in the source map when geometry permits; interrupted finalization cannot duplicate it; chart/map timestamps agree; exports preserve declared geometry and gaps without claiming omitted telemetry. History/export is complete without Apple Watch and can consume its samples later.

### T6 — Phone acceptance and performance

- [ ] Complete agent-run focused host algorithm/persistence checks and owner-run builds/device/emulator/simulator tests with recorded results.
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

### Remaining decisions and calibration

| Area | Open work | Required before |
| --- | --- | --- |
| Phone quality/movement/navigation | Calibrate provisional gates, movement/deviation thresholds and performance/battery budgets with fixtures and representative devices | T6 acceptance |
| Apple Watch capabilities | Resolve continuous motorized heart rate and background alert/display limits without fabricated workouts | T7 production commitment |
| Apple Watch transport | Verify bounded telemetry reconciliation and mirrored live-data budgets on physical paired devices | T7 acceptance |
