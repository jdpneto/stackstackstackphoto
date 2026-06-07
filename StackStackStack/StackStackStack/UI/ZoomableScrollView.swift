import SwiftUI
import UIKit

/// Pinch-to-zoom + pan + double-tap-to-zoom for a single image, backed by `UIScrollView` (correct
/// zoom bounds / pan / inertia that hand-rolled SwiftUI gestures don't give).
struct ZoomableScrollView: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
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
        let doubleTap = UITapGestureRecognizer(target: context.coordinator,
                                               action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(doubleTap)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        let iv = context.coordinator.imageView
        // Only reset zoom when the image actually changes — otherwise an unrelated SwiftUI update
        // (toolbar tap, sheet toggle) would snap the user out of a pinch.
        if iv.image !== image {
            iv.image = image
            scroll.zoomScale = 1
        }
        // Resize to fill the scroll view only on a real bounds change (skip the zero-bounds first pass).
        if !scroll.bounds.isEmpty, iv.frame.size != scroll.bounds.size {
            iv.frame = CGRect(origin: .zero, size: scroll.bounds.size)
            scroll.contentSize = scroll.bounds.size
            context.coordinator.centerContent(scroll)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        let imageView = UIImageView()
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        /// Keep the image centred when it's smaller than the scroll view (e.g. zoomed back to fit, or a
        /// non-filling aspect ratio) so it doesn't pin to the top-left with black gutters.
        func centerContent(_ scroll: UIScrollView) {
            let x = max((scroll.bounds.width - imageView.frame.width) / 2, 0)
            let y = max((scroll.bounds.height - imageView.frame.height) / 2, 0)
            scroll.contentInset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
        }
        func scrollViewDidZoom(_ scrollView: UIScrollView) { centerContent(scrollView) }

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
