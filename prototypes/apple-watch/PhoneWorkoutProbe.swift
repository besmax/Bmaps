/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

#if os(iOS)
import Foundation
import HealthKit

@available(iOS 18.2, *)
final class PhoneWorkoutProbe: NSObject, HKWorkoutSessionDelegate {
    var onHeartRate: ((ProbeHeartRateBatch) -> Void)?
    var onState: ((HKWorkoutSessionState) -> Void)?
    var onFailure: ((Error) -> Void)?

    private let healthStore = HKHealthStore()
    private var session: HKWorkoutSession?
    private var activityId: String?
    private var sessionGeneration: String?

    func install(activityId: String, sessionGeneration: String) {
        precondition(Thread.isMainThread)
        self.activityId = activityId
        self.sessionGeneration = sessionGeneration
        healthStore.workoutSessionMirroringStartHandler = { [weak self] session in
            DispatchQueue.main.async {
                guard let self else { return }
                self.session?.delegate = nil
                self.session = session
                session.delegate = self
            }
        }
    }

    func sendProbeData(_ data: Data) {
        precondition(Thread.isMainThread)
        guard data.count <= 65_536 else { onFailure?(WorkoutProbeFailure.packetTooLarge); return }
        guard let session else { onFailure?(WorkoutProbeFailure.mirroringFailed); return }
        session.sendToRemoteWorkoutSession(data: data) { [weak self] success, error in
            DispatchQueue.main.async {
                if !success { self?.onFailure?(error ?? WorkoutProbeFailure.mirroringFailed) }
            }
        }
    }

    func uninstall() {
        precondition(Thread.isMainThread)
        healthStore.workoutSessionMirroringStartHandler = nil
        session?.delegate = nil
        session = nil
        activityId = nil
        sessionGeneration = nil
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession data: [Data]) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.session === workoutSession else { return }
            for packet in data {
                do {
                    guard packet.count <= 65_536 else { throw WorkoutProbeFailure.packetTooLarge }
                    let batch = try JSONDecoder().decode(ProbeHeartRateBatch.self, from: packet)
                    guard batch.protocolVersion == 1, batch.activityId == self.activityId,
                          batch.sessionGeneration == self.sessionGeneration,
                          !batch.samples.isEmpty, batch.samples.count <= 128,
                          Set(batch.samples.map { $0.sampleId }).count == batch.samples.count,
                          batch.samples.allSatisfy({ $0.beatsPerMinute.isFinite && $0.beatsPerMinute > 0 &&
                              $0.acquiredAtEpochMillis >= 0 && $0.endedAtEpochMillis >= $0.acquiredAtEpochMillis })
                    else { throw WorkoutProbeFailure.invalidPacket }
                    self.onHeartRate?(batch)
                } catch { self.onFailure?(error) }
            }
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
                        from fromState: HKWorkoutSessionState, date: Date) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.session === workoutSession else { return }
            self.onState?(toState)
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.session === workoutSession else { return }
            self.onFailure?(error)
            self.uninstall()
        }
    }
}
#endif
