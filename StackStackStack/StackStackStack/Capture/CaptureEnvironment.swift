import Foundation
import UIKit

/// System conditions consulted at shutter press (spec 2026-06-11 §2). Closures so tests inject
/// states the simulator can't produce (thermal, battery, full disk).
struct CaptureEnvironment {
    var thermalState: () -> ProcessInfo.ThermalState
    var batteryLevel: () -> Float          // 0…1; -1 = unknown (simulator)
    var batteryCharging: () -> Bool
    var freeDiskBytes: () -> Int64

    /// Real system probes. Battery monitoring is enabled once here in the factory body (MainActor);
    /// the closures are MainActor-only by contract — the coordinator (the sole caller) is @MainActor —
    /// so they may safely read UIDevice without re-enabling monitoring on each call. (Fix 5)
    @MainActor
    static func live() -> CaptureEnvironment {
        UIDevice.current.isBatteryMonitoringEnabled = true
        return CaptureEnvironment(
            thermalState: { ProcessInfo.processInfo.thermalState },
            batteryLevel: {
                UIDevice.current.batteryLevel
            },
            batteryCharging: {
                UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
            },
            freeDiskBytes: {
                // A failed probe must never wrongly block the shutter — report "plenty".
                let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let cap = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                    .volumeAvailableCapacityForImportantUsage
                return cap ?? .max
            })
    }

    /// Free space below which capture is blocked (a 20-frame stack + result can need ~150 MB).
    static let minimumFreeBytes: Int64 = 200_000_000
    /// Battery fraction below which the UI warns (capture is never blocked on battery).
    static let lowBatteryThreshold: Float = 0.10
}
