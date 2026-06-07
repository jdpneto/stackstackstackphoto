import SwiftUI
import UIKit

/// Pinch-to-zoom + pan + double-tap-to-zoom for a single image, backed by `UIScrollView` (correct
/// zoom bounds / pan / inertia that hand-rolled SwiftUI gestures don't give).
struct ZoomableScrollView: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> CenteringScrollView {
        let scroll = CenteringScrollView()
        scroll.delegate = context.coordinator
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.backgroundColor = .black
        let iv = context.coordinator.imageView
        iv.contentMode = .scaleAspectFit
        iv.image = image
        scroll.addSubview(iv)
        scroll.imageView = iv
        let doubleTap = UITapGestureRecognizer(target: context.coordinator,
                                               action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(doubleTap)
        return scroll
    }

    func updateUIView(_ scroll: CenteringScrollView, context: Context) {
        let iv = context.coordinator.imageView
        // Only swap the image when it actually changes — otherwise an unrelated SwiftUI update
        // (toolbar tap, sheet toggle) would snap the user out of a pinch. The new image is re-fit on
        // the next layout pass (sizing lives in CenteringScrollView.layoutSubviews, not here).
        if iv.image !== image {
            iv.image = image
            scroll.zoomScale = 1
            scroll.setNeedsLayout()
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    /// Sizes the image view to fill the scroll view in `layoutSubviews`. This must NOT live in
    /// `updateUIView`: SwiftUI may call `updateUIView` before the scroll view has its final non-zero
    /// bounds and does not call it again on layout, which would leave the image at zero size and the
    /// viewer blank. `layoutSubviews` runs whenever UIKit lays the scroll view out (first appearance,
    /// rotation, split-view), so the image is always sized once real bounds exist.
    final class CenteringScrollView: UIScrollView {
        weak var imageView: UIImageView?

        override func layoutSubviews() {
            super.layoutSubviews()
            guard let iv = imageView, !bounds.isEmpty else { return }
            // Re-fit only when not zoomed: while zoomed in, UIScrollView reports the image view's frame
            // as bounds×scale, so re-fitting here would collapse the active zoom.
            if zoomScale == 1, iv.frame.size != bounds.size {
                iv.frame = CGRect(origin: .zero, size: bounds.size)
                contentSize = bounds.size
            }
            // Keep the content centred when it's smaller than the scroll view (zoomed back to fit, or a
            // non-filling aspect ratio) so it doesn't pin to the top-left with black gutters.
            let x = max((bounds.width - iv.frame.width) / 2, 0)
            let y = max((bounds.height - iv.frame.height) / 2, 0)
            let inset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
            if contentInset != inset { contentInset = inset }   // avoid a redundant layout pass
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        let imageView = UIImageView()
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
        func scrollViewDidZoom(_ scrollView: UIScrollView) { scrollView.setNeedsLayout() }

        @objc func handleDoubleTap(_ g: UITapGestureRecognizer) {
            guard let scroll = g.view as? UIScrollView else { return }
            if scroll.zoomScale > scroll.minimumZoomScale {
                scroll.setZoomScale(scroll.minimumZoomScale, animated: true)
            } else {
                let p = g.location(in: imageView)
                // Zoom to a rect sized for the max scale, centred on the tap (clamped by the scroll view).
                let w = scroll.bounds.width / scroll.maximumZoomScale
                let h = scroll.bounds.height / scroll.maximumZoomScale
                scroll.zoom(to: CGRect(x: p.x - w / 2, y: p.y - h / 2, width: w, height: h), animated: true)
            }
        }
    }
}
