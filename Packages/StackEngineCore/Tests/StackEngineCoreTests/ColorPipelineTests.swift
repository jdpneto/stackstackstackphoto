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

    func testBilinearDemosaicNonUniformNeighbors() {
        // 4x4 RGGB, deliberately non-linear field so the exact neighbor sets matter.
        let w = 4, h = 4
        let lin: [Float] = [
            10, 25, 30, 12,
            40, 60, 55, 18,
            70, 100, 90, 33,
            22, 44, 66, 88,
        ]
        let img = demosaic(lin, width: w, height: h, pattern: .rggb)
        // Green site (1,2): horizontal neighbors are R, vertical neighbors are B.
        XCTAssertEqual(img[1, 2].x, 80, accuracy: 1e-3)    // r = (70+90)/2
        XCTAssertEqual(img[1, 2].y, 100, accuracy: 1e-3)   // g = site value
        XCTAssertEqual(img[1, 2].z, 52, accuracy: 1e-3)    // b = (60+44)/2
        // Red site (2,2): g = 4-neighbor avg, b = 4-diagonal avg.
        XCTAssertEqual(img[2, 2].x, 90, accuracy: 1e-3)    // r = site
        XCTAssertEqual(img[2, 2].y, 63.5, accuracy: 1e-3)  // g = (100+33+55+66)/4
        XCTAssertEqual(img[2, 2].z, 52.5, accuracy: 1e-3)  // b = (60+18+44+88)/4
    }

    func testProcessAppliesColorMatrix() {
        // 4x4 RGGB uniform raw -> linear 0.5 at every site; gains=1.
        // Color matrix swaps R and B channels.
        let w = 4, h = 4
        let mosaic = [UInt16](repeating: 544, count: w * h) // (544-64)/(1024-64)=0.5
        let swapRB = simd_float3x3(columns: (
            SIMD3<Float>(0, 0, 1),   // out.x from in.z
            SIMD3<Float>(0, 1, 0),
            SIMD3<Float>(1, 0, 0)))  // out.z from in.x
        let frame = RawSensorFrame(width: w, height: h, mosaic: mosaic,
            blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
            wbGains: SIMD3<Float>(1, 1, 1), colorMatrix: swapRB)
        let img = ColorPipeline.process(frame)
        // After demosaic every interior pixel ~ (0.5,0.5,0.5); swap keeps it (0.5,0.5,0.5).
        XCTAssertEqual(img[2, 2].x, 0.5, accuracy: 1e-5)
        // Now verify the matrix actually runs: use a non-symmetric input via gains.
        let frame2 = RawSensorFrame(width: w, height: h, mosaic: mosaic,
            blackLevel: 64, whiteLevel: 1024, cfa: .rggb,
            wbGains: SIMD3<Float>(0.2, 0.5, 0.8), colorMatrix: swapRB)
        let img2 = ColorPipeline.process(frame2)
        // Pre-matrix interior ~ (0.2*0.5, 0.5*0.5, 0.8*0.5)=(0.1,0.25,0.4); swapRB -> (0.4,0.25,0.1)
        XCTAssertEqual(img2[2, 2].x, 0.4, accuracy: 1e-5)
        XCTAssertEqual(img2[2, 2].y, 0.25, accuracy: 1e-5)
        XCTAssertEqual(img2[2, 2].z, 0.1, accuracy: 1e-5)
    }
}
