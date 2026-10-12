/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

import Foundation

struct ProbeHeartRateSample: Codable {
    let sampleId: String
    let acquiredAtEpochMillis: Int64
    let endedAtEpochMillis: Int64
    let beatsPerMinute: Double
}

struct ProbeHeartRateBatch: Codable {
    let activityId: String
    let sessionGeneration: String
    let sourceId: String
    let sourceSessionId: String
    let samples: [ProbeHeartRateSample]
    let protocolVersion: Int
}

enum WorkoutProbeFailure: Error {
    case healthDataUnavailable
    case alreadyRunning
    case authorizationRequestFailed
    case collectionFailed
    case mirroringFailed
    case packetTooLarge
    case invalidPacket
}
