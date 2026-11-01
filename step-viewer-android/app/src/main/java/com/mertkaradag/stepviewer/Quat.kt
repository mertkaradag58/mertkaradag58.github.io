package com.mertkaradag.stepviewer

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Birim dördey (quaternion): yumuşak kamera animasyonları için. */
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-9f) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    /** Sütun öncelikli 4x4 dönüş matrisi (android.opengl.Matrix biçimi). */
    fun toMatrix(m: FloatArray) {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        m[0] = 1 - 2 * (yy + zz); m[1] = 2 * (xy + wz); m[2] = 2 * (xz - wy); m[3] = 0f
        m[4] = 2 * (xy - wz); m[5] = 1 - 2 * (xx + zz); m[6] = 2 * (yz + wx); m[7] = 0f
        m[8] = 2 * (xz + wy); m[9] = 2 * (yz - wx); m[10] = 1 - 2 * (xx + yy); m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        fun axisAngle(ax: Float, ay: Float, az: Float, angleDeg: Float): Quat {
            val n = sqrt(ax * ax + ay * ay + az * az)
            if (n < 1e-9f) return IDENTITY
            val half = Math.toRadians(angleDeg.toDouble()).toFloat() * 0.5f
            val s = sin(half) / n
            return Quat(cos(half), ax * s, ay * s, az * s)
        }

        /**
         * Satırları görünüm eksenleri (sağ, yukarı, arka) olan dönüş matrisinden dördey.
         * Vektörler dünya koordinatlarında verilir.
         */
        fun fromBasis(right: FloatArray, up: FloatArray, back: FloatArray): Quat {
            // R satır öncelikli: r[i][j]
            val m00 = right[0]; val m01 = right[1]; val m02 = right[2]
            val m10 = up[0]; val m11 = up[1]; val m12 = up[2]
            val m20 = back[0]; val m21 = back[1]; val m22 = back[2]
            val trace = m00 + m11 + m22
            val q = if (trace > 0) {
                val s = sqrt(trace + 1f) * 2f
                Quat(0.25f * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quat((m21 - m12) / s, 0.25f * s, (m01 + m10) / s, (m02 + m20) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quat((m02 - m20) / s, (m01 + m10) / s, 0.25f * s, (m12 + m21) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25f * s)
            }
            return q.normalized()
        }

        fun slerp(a: Quat, bIn: Quat, t: Float): Quat {
            var b = bIn
            var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            if (dot < 0f) {
                b = Quat(-b.w, -b.x, -b.y, -b.z)
                dot = -dot
            }
            if (dot > 0.9995f) {
                return Quat(
                    a.w + t * (b.w - a.w), a.x + t * (b.x - a.x),
                    a.y + t * (b.y - a.y), a.z + t * (b.z - a.z),
                ).normalized()
            }
            val theta = acos(dot.coerceIn(-1f, 1f))
            val sinT = sin(theta)
            val wa = sin((1 - t) * theta) / sinT
            val wb = sin(t * theta) / sinT
            return Quat(
                wa * a.w + wb * b.w, wa * a.x + wb * b.x,
                wa * a.y + wb * b.y, wa * a.z + wb * b.z,
            ).normalized()
        }
    }
}
