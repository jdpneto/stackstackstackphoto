// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "StackEngineCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "StackEngineCore", targets: ["StackEngineCore"])
    ],
    targets: [
        .target(name: "StackEngineCore"),
        .testTarget(name: "StackEngineCoreTests", dependencies: ["StackEngineCore"])
    ]
)
