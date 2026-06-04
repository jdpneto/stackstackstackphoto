import XCTest
import simd
@testable import StackEngineCore

final class ColorPipelineTests: XCTestCase {
    func testLinearizeAndBalanceAppliesPerChannelGain() {
        // 2x2 RGGB, all raw=544 -> linear 0.5 each, with gains R=2, G=1, B=4
        let frame = RawSensorFrame(
            width: 2, height: 2,
            mosaic: [544, 544, 544, 544],
            blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
            wbGains: SIMD3<Float>(2, 1, 4))
        let lin = linearizeAndBalance(frame)
        // sites: (0,0)=R*2=1.0, (1,0)=G*1=0.5, (0,1)=G*1=0.5, (1,1)=B*4=2.0
        XCTAssertEqual(lin[0], 1.0, accuracy: 1e-6)
        XCTAssertEqual(lin[1], 0.5, accuracy: 1e-6)
        XCTAssertEqual(lin[2], 0.5, accuracy: 1e-6)
        XCTAssertEqual(lin[3], 2.0, accuracy: 1e-6)
    }

    func testBilinearDemosaicInteriorUniformColor() {
        // 4x4 RGGB. Set every R site=0.8, G site=0.5, B site=0.2 (already linear+balanced).
        let w = 4, h = 4
        var lin = [Float](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w {
            switch cfaColor(.rggb, x, y) {
            case .red: lin[y*w+x] = 0.8
            case .green: lin[y*w+x] = 0.5
            case .blue: lin[y*w+x] = 0.2
            }
        }}
        let img = demosaic(lin, width: w, height: h, pattern: .rggb)
        // Interior pixels (1,1), (2,2) must reconstruct the constant color exactly.
        for (x, y) in [(1, 1), (2, 2), (2, 1), (1, 2)] {
            XCTAssertEqual(img[x, y].x, 0.8, accuracy: 1e-5, "R at \(x),\(y)")
            XCTAssertEqual(img[x, y].y, 0.5, accuracy: 1e-5, "G at \(x),\(y)")
            XCTAssertEqual(img[x, y].z, 0.2, accuracy: 1e-5, "B at \(x),\(y)")
        }
    }
}
