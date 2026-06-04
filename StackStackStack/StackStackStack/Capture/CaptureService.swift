import StackEngineCore

enum CaptureMode { case noiseReduction } // skeleton supports one mode

protocol CaptureService {
    func captureBurst(mode: CaptureMode, frameCount: Int) async throws -> [RawSensorFrame]
}
