import SwiftUI
import UIKit
import AVFoundation

/// Hosts the camera preview `CALayer` (from `AVCaptureService`) behind the capture controls, and
/// forwards taps / long-presses on the preview as normalized device focus points. A nil layer (or a
/// non-`AVCaptureVideoPreviewLayer`, e.g. the Simulator) leaves it transparent and inert.
struct CameraPreviewView: UIViewRepresentable {
    let previewLayer: CALayer?
    /// When false, taps/long-presses are ignored (e.g. manual Pro mode, or shutter busy).
    var enabled: Bool = false
    /// (normalized device point 0…1, tap location in view coords, lock=true for long-press)
    var onFocus: ((_ devicePoint: CGPoint, _ viewPoint: CGPoint, _ lock: Bool) -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PreviewHostView {
        let view = PreviewHostView()
        let tap = UITapGestureRecognizer(target: context.coordinator,
                                         action: #selector(Coordinator.handleTap(_:)))
        let long = UILongPressGestureRecognizer(target: context.coordinator,
                                                action: #selector(Coordinator.handleLongPress(_:)))
        tap.require(toFail: long)   // a hold fires the long-press, not also a tap
        view.addGestureRecognizer(tap)
        view.addGestureRecognizer(long)
        context.coordinator.host = view
        return view
    }

    func updateUIView(_ uiView: PreviewHostView, context: Context) {
        uiView.previewLayer = previewLayer
        context.coordinator.enabled = enabled
        context.coordinator.onFocus = onFocus
    }

    final class Coordinator: NSObject {
        weak var host: PreviewHostView?
        var enabled = false
        var onFocus: ((CGPoint, CGPoint, Bool) -> Void)?

        @objc func handleTap(_ g: UITapGestureRecognizer) { fire(g, lock: false) }
        @objc func handleLongPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began else { return }   // fire once when the hold is recognized
            fire(g, lock: true)
        }

        private func fire(_ g: UIGestureRecognizer, lock: Bool) {
            guard enabled, let host,
                  let layer = host.previewLayer as? AVCaptureVideoPreviewLayer else { return }
            let viewPoint = g.location(in: host)   // layer.frame == host.bounds, so this is also the layer point
            // captureDevicePointConverted maps layer point → normalized device point, accounting for the
            // preview's videoGravity (.resizeAspectFill cropping) and orientation — the reason to use it.
            let devicePoint = layer.captureDevicePointConverted(fromLayerPoint: viewPoint)
            onFocus?(devicePoint, viewPoint, lock)
        }
    }
}

final class PreviewHostView: UIView {
    var previewLayer: CALayer? {
        didSet {
            guard previewLayer !== oldValue else { return }
            oldValue?.removeFromSuperlayer()
            if let previewLayer {
                previewLayer.frame = bounds
                layer.addSublayer(previewLayer)
            }
            setNeedsLayout()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds   // keep the preview filling the view on rotation/resize
    }
}
