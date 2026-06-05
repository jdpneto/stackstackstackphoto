import SwiftUI
import UIKit

/// Hosts the camera preview `CALayer` (from `AVCaptureService`) behind the capture controls.
/// A nil layer leaves the host transparent, so the neutral background shows (e.g. the Simulator).
struct CameraPreviewView: UIViewRepresentable {
    let previewLayer: CALayer?

    func makeUIView(context: Context) -> PreviewHostView { PreviewHostView() }
    func updateUIView(_ uiView: PreviewHostView, context: Context) { uiView.previewLayer = previewLayer }
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
