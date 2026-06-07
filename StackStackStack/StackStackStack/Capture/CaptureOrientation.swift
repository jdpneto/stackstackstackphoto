import UIKit

/// Maps the physical device orientation at shutter time to the clockwise quarter-turns needed to make
/// the back camera's native-landscape stacked result upright. Device-verified: portrait shows upright,
/// and the two landscape cases are *swapped* relative to the naive guess — `UIDeviceOrientation`'s
/// `.landscapeLeft`/`.landscapeRight` correspond to the opposite video orientations (the classic
/// AVFoundation device-vs-video landscape inversion), so a landscape shot otherwise saves upside-down.
enum CaptureOrientation {
    static func quarterTurns(for orientation: UIDeviceOrientation) -> Int {
        switch orientation {
        case .portrait:           return 1
        case .portraitUpsideDown: return 3
        case .landscapeLeft:      return 0
        case .landscapeRight:     return 2
        default:                  return 1   // faceUp/faceDown/unknown → assume portrait
        }
    }
}
