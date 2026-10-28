package studio.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-12) this else this * (1.0 / l)
    }
    fun isZero() = x == 0.0 && y == 0.0 && z == 0.0
    fun map(f: (Double) -> Double) = Vec3(f(x), f(y), f(z))
    fun toList() = listOf(x, y, z)

    /** true se todos os componentes diferem de [o] por menos de [eps]. */
    fun near(o: Vec3, eps: Double = 1e-6) = abs(x - o.x) < eps && abs(y - o.y) < eps && abs(z - o.z) < eps

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
        fun of(l: List<Double>) = Vec3(l[0], l[1], l[2])
    }
}

/** Matriz 4x4 (linha-maior) para pontos como colunas: p' = M * p. */
class Mat4(val m: DoubleArray) {
    init {
        require(m.size == 16)
    }

    operator fun get(r: Int, c: Int) = m[r * 4 + c]

    operator fun times(o: Mat4): Mat4 {
        val out = DoubleArray(16)
        for (r in 0 until 4) for (c in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += this[r, k] * o[k, c]
            out[r * 4 + c] = s
        }
        return Mat4(out)
    }

    fun transformPoint(p: Vec3) = Vec3(
        this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2] * p.z + this[0, 3],
        this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2] * p.z + this[1, 3],
        this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2] * p.z + this[2, 3],
    )

    fun transformDir(p: Vec3) = Vec3(
        this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2] * p.z,
        this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2] * p.z,
        this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2] * p.z,
    )

    /** Inversa geral por Gauss-Jordan; null se a matriz for singular (ex.: escala zero). */
    fun inverse(): Mat4? {
        val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) this[r, c] else if (c - 4 == r) 1.0 else 0.0 } }
        for (col in 0 until 4) {
            var piv = col
            for (r in col + 1 until 4) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-12) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val d = a[col][col]
            for (c in 0 until 8) a[col][c] /= d
            for (r in 0 until 4) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (c in 0 until 8) a[r][c] -= f * a[col][c]
            }
        }
        return Mat4(DoubleArray(16) { a[it / 4][4 + it % 4] })
    }

    companion object {
        val IDENTITY = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun translation(v: Vec3) = Mat4(doubleArrayOf(1.0, 0.0, 0.0, v.x, 0.0, 1.0, 0.0, v.y, 0.0, 0.0, 1.0, v.z, 0.0, 0.0, 0.0, 1.0))
        fun scale(v: Vec3) = Mat4(doubleArrayOf(v.x, 0.0, 0.0, 0.0, 0.0, v.y, 0.0, 0.0, 0.0, 0.0, v.z, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun rotX(deg: Double): Mat4 {
            val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            return Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
        fun rotY(deg: Double): Mat4 {
            val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            return Mat4(doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
        fun rotZ(deg: Double): Mat4 {
            val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            return Mat4(doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
    }
}
