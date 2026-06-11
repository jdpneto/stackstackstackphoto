package com.jdpneto.stackstackstack

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Pure steadiness math (testable without SensorManager): map an attitude deviation from the
 * reference pose to a normalized on-screen offset and a steady/unsteady verdict.
 *
 * Mirrors iOS [SteadinessMath] exactly — same formula, same tolerance/fullScale semantics.
 */
object SteadinessMath {
    /**
     * @param deltaPitch  Pitch deviation from the reference pose (radians).
     * @param deltaRoll   Roll deviation from the reference pose (radians).
     * @param tolerance   Threshold in radians for the steady verdict (~2.9°).
     * @param fullScale   Radians at which the offset reaches the ring edge (~6.9°).
     * @return Pair of (normalised offset -1…1 in (x=roll, y=pitch), steady verdict).
     */
    fun evaluate(
        deltaPitch: Double,
        deltaRoll: Double,
        tolerance: Double,
        fullScale: Double
    ): Pair<Pair<Double, Double>, Boolean> {
        val mag = sqrt(deltaPitch * deltaPitch + deltaRoll * deltaRoll)
        val nx = (deltaRoll / fullScale).coerceIn(-1.0, 1.0)
        val ny = (deltaPitch / fullScale).coerceIn(-1.0, 1.0)
        return Pair(Pair(nx, ny), mag <= tolerance)
    }
}

/**
 * Interface extracted from [MotionSteadiness] so tests can fake it. Mirrors the iOS pattern
 * where `isSteady` is read from the capture state queue. (design 2026-06-07 §8)
 */
interface SteadinessSource {
    /** Thread-safe; read from the capture's state executor. */
    val isSteady: Boolean

    /** Snapshot the reference attitude and start delivering steadiness updates. */
    fun start()

    /** Stop delivering updates. Resets [isSteady] to true. */
    fun stop()
}

/**
 * Tracks handheld steadiness during a long-exposure burst via the rotation-vector sensor.
 * On `start()` it snapshots the reference attitude (the "glued" big circle); each update
 * yields a normalised offset (for the moving small circle) and a thread-safe `isSteady` flag
 * the capture gate reads.
 *
 * With no sensor (emulator), `isSteady` stays true so capture is never blocked.
 * (design 2026-06-07 §8)
 *
 * Mirrors iOS [MotionSteadiness] 1:1.
 */
class MotionSteadiness(context: Context) : SteadinessSource {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Tolerance/fullScale constants match the iOS implementation verbatim.
    private val toleranceRadians = 0.05   // ~2.9° = "steady"
    private val fullScaleRadians = 0.12   // offset reaches the ring edge at ~6.9°

    @Volatile private var _isSteady: Boolean = true
    override val isSteady: Boolean get() = _isSteady

    /** Normalised offset (x=roll, y=pitch) in -1…1, for the UI overlay. */
    @Volatile var offset: Pair<Double, Double> = Pair(0.0, 0.0)
        private set

    /** Reference rotation vector (4 or 5 floats); null until the first event after start(). */
    @Volatile private var referenceQuaternion: FloatArray? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val q = referenceQuaternion
            if (q == null) {
                // First event: snapshot the reference attitude.
                referenceQuaternion = event.values.copyOf()
                return
            }
            // Compute the relative rotation between the reference and the current pose.
            // Use rotation matrix approach: convert both quaternions to rotation matrices,
            // then compute the relative matrix and extract pitch/roll.
            val refMatrix = FloatArray(9)
            val curMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(refMatrix, q)
            SensorManager.getRotationMatrixFromVector(curMatrix, event.values)
            // Relative rotation R = R_current * R_reference^T
            val relMatrix = multiplyMatrixTranspose(curMatrix, refMatrix)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(relMatrix, orientation)
            val pitch = orientation[1].toDouble()   // pitch (index 1)
            val roll  = orientation[2].toDouble()   // roll  (index 2)
            val (off, steady) = SteadinessMath.evaluate(pitch, roll, toleranceRadians, fullScaleRadians)
            _isSteady = steady
            offset = off
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { /* unused */ }
    }

    override fun start() {
        stop()  // idempotent; ensures no prior stream races a restart
        _isSteady = true
        offset = Pair(0.0, 0.0)
        referenceQuaternion = null
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return   // no sensor → always steady
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        sensorManager.unregisterListener(listener)
        _isSteady = true
        referenceQuaternion = null
    }

    /**
     * Multiply A × B^T (B transposed). Used to compute the relative rotation matrix.
     * Both A and B are 3×3 rotation matrices stored row-major (as from [SensorManager.getRotationMatrixFromVector]).
     */
    private fun multiplyMatrixTranspose(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += a[row * 3 + k] * b[col * 3 + k]  // B^T: swap col/k indices
                }
                result[row * 3 + col] = sum
            }
        }
        return result
    }
}

/** A fake [SteadinessSource] for tests: always reports steady. */
class AlwaysSteadySource : SteadinessSource {
    override val isSteady: Boolean = true
    override fun start() { }
    override fun stop() { }
}
