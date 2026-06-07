import UIKit

/// Maps the physical device orientation at shutter time to the clockwise quarter-turns needed to make
/// the back camera's native-landscape stacked result upright. The constants are validated on a
/// physical device (the back wide-camera buffer is landscape-native); see the plan's device-verify step.
enum CaptureOrientation {
    static func quarterTurns(for orientation: UIDeviceOrientation) -> Int {
        switch orientation {
        case .portrait:           return 1
        case .portraitUpsideDown: return 3
        case .landscapeLeft:      return 2
        case .landscapeRight:     return 0
        default:                  return 1   // faceUp/faceDown/unknown → assume portrait
        }
    }
}
