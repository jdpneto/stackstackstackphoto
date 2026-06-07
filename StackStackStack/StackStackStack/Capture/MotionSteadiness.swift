import CoreMotion
import CoreGraphics
import Combine
import Foundation

/// Pure steadiness math (testable without CoreMotion): map an attitude deviation from the reference
/// pose to a normalized on-screen offset and a steady/unsteady verdict. (design 2026-06-07 §8)
enum SteadinessMath {
    static func evaluate(deltaPitch: Double, deltaRoll: Double,
                         tolerance: Double, fullScale: Double) -> (offset: CGPoint, steady: Bool) {
        let mag = (deltaPitch * deltaPitch + deltaRoll * deltaRoll).squareRoot()
        let nx = max(min(deltaRoll / fullScale, 1), -1)
        let ny = max(min(deltaPitch / fullScale, 1), -1)
        return (CGPoint(x: nx, y: ny), mag <= tolerance)
    }
}

/// Tracks handheld steadiness during a long-exposure burst. On `start()` it snapshots the reference
/// attitude (the "glued" big circle); each update yields a normalized `offset` (for the moving small
/// circle) and a thread-safe `isSteady` flag the capture gate reads. Device-only: with no device
/// motion (Simulator), `isSteady` stays true so capture is never blocked. (design 2026-06-07 §8)
final class MotionSteadiness: ObservableObject, @unchecked Sendable {
    @Published private(set) var offset: CGPoint = .zero       // updated on the main queue (for the UI)
    @Published private(set) var isWithinTolerance = true

    private let manager = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 1   // serial delivery → `reference` is race-free
        return q
    }()
    private var reference: CMAttitude?                        // touched only on `queue`
    private let lock = NSLock()
    private var steadyFlag = true
    private let toleranceRadians = 0.05                       // ~2.9° = "steady"
    private let fullScaleRadians = 0.12                       // offset reaches the ring edge at ~6.9°

    /// Thread-safe; read from the capture's state queue.
    var isSteady: Bool { lock.lock(); defer { lock.unlock() }; return steadyFlag }

    func start() {
        manager.stopDeviceMotionUpdates()   // idempotent; ensures no prior stream/handler races a restart
        setSteady(true)
        offset = .zero
        isWithinTolerance = true
        guard manager.isDeviceMotionAvailable else { return }   // Simulator / no sensor → always steady
        manager.deviceMotionUpdateInterval = 1.0 / 60.0
        queue.addOperation { self.reference = nil }   // serialized before any new motion callbacks
        manager.startDeviceMotionUpdates(to: queue) { [weak self] motion, _ in
            guard let self, let m = motion else { return }
            if self.reference == nil { self.reference = m.attitude.copy() as? CMAttitude }
            guard let ref = self.reference, let a = m.attitude.copy() as? CMAttitude else { return }
            a.multiply(byInverseOf: ref)                        // attitude relative to the reference pose
            let result = SteadinessMath.evaluate(deltaPitch: a.pitch, deltaRoll: a.roll,
                                                 tolerance: self.toleranceRadians,
                                                 fullScale: self.fullScaleRadians)
            self.setSteady(result.steady)
            DispatchQueue.main.async {
                self.offset = result.offset
                self.isWithinTolerance = result.steady
            }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
        setSteady(true)
    }

    private func setSteady(_ v: Bool) { lock.lock(); steadyFlag = v; lock.unlock() }
}
