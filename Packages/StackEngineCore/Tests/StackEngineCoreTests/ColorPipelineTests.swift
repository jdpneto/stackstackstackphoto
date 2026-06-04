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
}
