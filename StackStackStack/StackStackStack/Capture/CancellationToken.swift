import Foundation

/// A thread-safe one-way cancellation flag. `Task.detached` does NOT inherit Swift-concurrency
/// cancellation, so the coordinator uses this to signal the off-actor stacking work to stop.
final class CancellationToken: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }
        return cancelled
    }
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
}
