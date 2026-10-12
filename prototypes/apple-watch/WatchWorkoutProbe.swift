/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

#if os(watchOS)
import Foundation
import HealthKit

@available(watchOS 10.0, *)
final class WatchWorkoutProbe: NSObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate {
    var onHeartRate: ((ProbeHeartRateBatch) -> Void)?
    var onRemoteData: ((Data) -> Void)?
    var onState: ((HKWorkoutSessionState) -> Void)?
    var onFailure: ((Error) -> Void)?

    private let healthStore = HKHealthStore()
    private let sourceId: String
    private var operationId: UUID?
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var activityId = ""
    private var sessionGeneration = ""
    private var sourceSessionId = ""
    private var lastSampleId: String?

    init(sourceId: String) {
        self.sourceId = sourceId
        super.init()
    }

    func startHiking(activityId: String, sessionGeneration: String) {
        precondition(Thread.isMainThread)
        guard operationId == nil else { onFailure?(WorkoutProbeFailure.alreadyRunning); return }
        guard HKHealthStore.isHealthDataAvailable() else { onFailure?(WorkoutProbeFailure.healthDataUnavailable); return }
        let token = UUID()
        operationId = token
        let heartRate = HKQuantityType.quantityType(forIdentifier: .heartRate)!
        healthStore.requestAuthorization(toShare: [HKObjectType.workoutType()], read: [heartRate]) { [weak self] success, error in
            DispatchQueue.main.async {
                guard let self, self.operationId == token else { return }
                guard success else {
                    self.operationId = nil
                    self.onFailure?(error ?? WorkoutProbeFailure.authorizationRequestFailed)
                    return
                }
                self.begin(activityId: activityId, sessionGeneration: sessionGeneration, token: token)
            }
        }
    }

    private func begin(activityId: String, sessionGeneration: String, token: UUID) {
        do {
            let configuration = HKWorkoutConfiguration()
            configuration.activityType = .hiking
            configuration.locationType = .outdoor
            let session = try HKWorkoutSession(healthStore: healthStore, configuration: configuration)
            let builder = session.associatedWorkoutBuilder()
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: healthStore, workoutConfiguration: configuration)
            session.delegate = self
            builder.delegate = self
            self.session = session
            self.builder = builder
            self.activityId = activityId
            self.sessionGeneration = sessionGeneration
            self.sourceSessionId = UUID().uuidString
            lastSampleId = nil
            let startedAt = Date()
            session.startActivity(with: startedAt)
            builder.beginCollection(withStart: startedAt) { [weak self] success, error in
                DispatchQueue.main.async {
                    guard let self, self.operationId == token else { return }
                    guard success else {
                        self.onFailure?(error ?? WorkoutProbeFailure.collectionFailed)
                        self.stop()
                        return
                    }
                    session.startMirroringToCompanionDevice { [weak self] success, error in
                        DispatchQueue.main.async {
                            guard let self, self.operationId == token else { return }
                            if !success { self.onFailure?(error ?? WorkoutProbeFailure.mirroringFailed) }
                        }
                    }
                }
            }
        } catch {
            operationId = nil
            onFailure?(error)
        }
    }

    func stop() {
        precondition(Thread.isMainThread)
        operationId = nil
        let endingSession = session
        let endingBuilder = builder
        session = nil
        builder = nil
        endingSession?.delegate = nil
        endingBuilder?.delegate = nil
        endingSession?.end()
        endingBuilder?.endCollection(withEnd: Date()) { _, _ in endingBuilder?.discardWorkout() }
    }

    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        let type = HKQuantityType.quantityType(forIdentifier: .heartRate)!
        guard collectedTypes.contains(type),
              let statistics = workoutBuilder.statistics(for: type),
              let quantity = statistics.mostRecentQuantity(),
              let interval = statistics.mostRecentQuantityDateInterval() else { return }
        let bpm = quantity.doubleValue(for: HKUnit.count().unitDivided(by: .minute()))
        let start = Int64(interval.start.timeIntervalSince1970 * 1_000)
        let end = Int64(interval.end.timeIntervalSince1970 * 1_000)
        guard bpm.isFinite, bpm > 0, start >= 0, end >= start else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.builder === workoutBuilder, let session = self.session else { return }
            let sampleId = "\(self.sourceSessionId):\(start):\(end)"
            guard sampleId != self.lastSampleId else { return }
            self.lastSampleId = sampleId
            let batch = ProbeHeartRateBatch(activityId: self.activityId, sessionGeneration: self.sessionGeneration,
                sourceId: self.sourceId, sourceSessionId: self.sourceSessionId,
                samples: [ProbeHeartRateSample(sampleId: sampleId, acquiredAtEpochMillis: start,
                    endedAtEpochMillis: end, beatsPerMinute: bpm)], protocolVersion: 1)
            self.onHeartRate?(batch)
            do {
                let data = try JSONEncoder().encode(batch)
                guard data.count <= 65_536 else { throw WorkoutProbeFailure.packetTooLarge }
                session.sendToRemoteWorkoutSession(data: data) { [weak self] success, error in
                    DispatchQueue.main.async {
                        guard let self, self.session === session else { return }
                        if !success { self.onFailure?(error ?? WorkoutProbeFailure.mirroringFailed) }
                    }
                }
            } catch { self.onFailure?(error) }
        }
    }

    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}

    func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
                        from fromState: HKWorkoutSessionState, date: Date) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.session === workoutSession else { return }
            self.onState?(toState)
            if toState == .ended { self.stop() }
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.session === workoutSession else { return }
            self.onFailure?(error)
            self.stop()
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession data: [Data]) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.session === workoutSession else { return }
            for packet in data where packet.count <= 65_536 { self.onRemoteData?(packet) }
        }
    }
}
#endif
