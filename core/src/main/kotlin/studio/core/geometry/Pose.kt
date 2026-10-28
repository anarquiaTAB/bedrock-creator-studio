package studio.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Transformação de um osso numa animação, em relação à pose de repouso: rotação (graus) e posição somam; escala multiplica. */
data class BonePose(val rotation: Vec3 = Vec3.ZERO, val position: Vec3 = Vec3.ZERO, val scale: Vec3 = Vec3.ONE)

/**
 * Sinais da convenção de rotação/posição do Bedrock em relação a um sistema destro comum. Os valores padrão vêm de
 * ferramentas de modelagem do Bedrock (rotação em X e Y e posição em X invertidas), escritos de memória:
 * é só para a PRÉVIA e precisa ser confirmado no jogo. Está isolado aqui para ser corrigido num único lugar.
 */
data class PoseConvention(val negateRotX: Boolean = true, val negateRotY: Boolean = true, val negatePosX: Boolean = true) {
    fun rot(r: Vec3) = Vec3(if (negateRotX) -r.x else r.x, if (negateRotY) -r.y else r.y, r.z)
    fun pos(p: Vec3) = Vec3(if (negatePosX) -p.x else p.x, p.y, p.z)

    companion object {
        val BEDROCK = PoseConvention()
    }
}

data class Aabb(val min: Vec3, val max: Vec3) {
    val center get() = (min + max) * 0.5
    val size get() = max - min
    val radius get() = size.length() * 0.5
}

class Ray(val origin: Vec3, val dir: Vec3)

class Hit(val bone: String, val index: Int, val face: FaceName, val distance: Double, val point: Vec3)

/** Cubo já posicionado no espaço do modelo. [matrix] leva o espaço local do cubo (origem + tamanho) ao espaço do modelo. */
class PosedCube(val bone: String, val index: Int, val cube: Cube, val matrix: Mat4, val inflate: Double, val boneMirror: Boolean?) {
    val localMin: Vec3 get() = cube.origin - Vec3(inflate, inflate, inflate)
    val localMax: Vec3 get() = cube.origin + cube.size + Vec3(inflate, inflate, inflate)

    fun corners(): List<Vec3> {
        val a = localMin; val b = localMax
        return listOf(
            Vec3(a.x, a.y, a.z), Vec3(b.x, a.y, a.z), Vec3(a.x, b.y, a.z), Vec3(b.x, b.y, a.z),
            Vec3(a.x, a.y, b.z), Vec3(b.x, a.y, b.z), Vec3(a.x, b.y, b.z), Vec3(b.x, b.y, b.z),
        ).map { matrix.transformPoint(it) }
    }

    /** Quatro cantos da face no espaço do modelo, na ordem (sup.-esq., sup.-dir., inf.-dir., inf.-esq.) da textura vista de fora. */
    fun faceQuad(face: FaceName): List<Vec3> {
        val a = localMin; val b = localMax
        val q = when (face) {
            FaceName.NORTH -> listOf(Vec3(b.x, b.y, a.z), Vec3(a.x, b.y, a.z), Vec3(a.x, a.y, a.z), Vec3(b.x, a.y, a.z))
            FaceName.SOUTH -> listOf(Vec3(a.x, b.y, b.z), Vec3(b.x, b.y, b.z), Vec3(b.x, a.y, b.z), Vec3(a.x, a.y, b.z))
            FaceName.EAST -> listOf(Vec3(b.x, b.y, b.z), Vec3(b.x, b.y, a.z), Vec3(b.x, a.y, a.z), Vec3(b.x, a.y, b.z))
            FaceName.WEST -> listOf(Vec3(a.x, b.y, a.z), Vec3(a.x, b.y, b.z), Vec3(a.x, a.y, b.z), Vec3(a.x, a.y, a.z))
            FaceName.UP -> listOf(Vec3(b.x, b.y, b.z), Vec3(a.x, b.y, b.z), Vec3(a.x, b.y, a.z), Vec3(b.x, b.y, a.z))
            FaceName.DOWN -> listOf(Vec3(b.x, a.y, a.z), Vec3(a.x, a.y, a.z), Vec3(a.x, a.y, b.z), Vec3(b.x, a.y, b.z))
        }
        return q.map { matrix.transformPoint(it) }
    }
}

object Posing {
    private fun rotation(r: Vec3): Mat4 = Mat4.rotZ(r.z) * Mat4.rotY(r.y) * Mat4.rotX(r.x)

    /** Matriz de cada osso (espaço do osso -> espaço do modelo), com a pose aplicada em torno do pivô. */
    fun boneMatrices(g: Geometry, poses: Map<String, BonePose> = emptyMap(), conv: PoseConvention = PoseConvention.BEDROCK): Map<String, Mat4> {
        val world = HashMap<String, Mat4>()
        for (b in g.orderedBones()) {
            val parent = b.parent?.let { world[it] } ?: Mat4.IDENTITY
            val p = poses[b.name] ?: BonePose()
            val rot = conv.rot((b.rotation ?: Vec3.ZERO) + p.rotation)
            val local = Mat4.translation(b.pivot + conv.pos(p.position)) * rotation(rot) * Mat4.scale(p.scale) * Mat4.translation(-b.pivot)
            world[b.name] = parent * local
        }
        return world
    }

    fun compute(g: Geometry, poses: Map<String, BonePose> = emptyMap(), conv: PoseConvention = PoseConvention.BEDROCK): List<PosedCube> {
        val mats = boneMatrices(g, poses, conv)
        val out = ArrayList<PosedCube>()
        for (b in g.bones) {
            val bm = mats[b.name] ?: Mat4.IDENTITY
            b.cubes.forEachIndexed { i, c ->
                var m = bm
                if (c.rotation != null) {
                    val pv = c.pivot ?: Vec3.ZERO
                    m = bm * Mat4.translation(pv) * rotation(conv.rot(c.rotation)) * Mat4.translation(-pv)
                }
                out.add(PosedCube(b.name, i, c, m, c.inflate ?: b.inflate ?: 0.0, b.mirror))
            }
        }
        return out
    }

    fun bounds(cubes: List<PosedCube>): Aabb? {
        if (cubes.isEmpty()) return null
        var mn = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var mx = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (c in cubes) for (p in c.corners()) {
            mn = Vec3(min(mn.x, p.x), min(mn.y, p.y), min(mn.z, p.z))
            mx = Vec3(max(mx.x, p.x), max(mx.y, p.y), max(mx.z, p.z))
        }
        return Aabb(mn, mx)
    }

    /** Cubo e face mais próximos atingidos pelo raio (para selecionar partes com o toque). */
    fun pick(cubes: List<PosedCube>, ray: Ray): Hit? {
        var best: Hit? = null
        for (pc in cubes) {
            val inv = pc.matrix.inverse() ?: continue
            val o = inv.transformPoint(ray.origin)
            val d = inv.transformDir(ray.dir)
            val lo = pc.localMin; val hi = pc.localMax
            var tmin = Double.NEGATIVE_INFINITY
            var tmax = Double.POSITIVE_INFINITY
            var entryAxis = -1
            var entryAtMin = true
            var ok = true
            val os = doubleArrayOf(o.x, o.y, o.z); val ds = doubleArrayOf(d.x, d.y, d.z)
            val los = doubleArrayOf(lo.x, lo.y, lo.z); val his = doubleArrayOf(hi.x, hi.y, hi.z)
            for (ax in 0 until 3) {
                if (abs(ds[ax]) < 1e-12) {
                    if (os[ax] < los[ax] || os[ax] > his[ax]) { ok = false; break }
                    continue
                }
                var t1 = (los[ax] - os[ax]) / ds[ax]
                var t2 = (his[ax] - os[ax]) / ds[ax]
                var atMin = true
                if (t1 > t2) { val t = t1; t1 = t2; t2 = t; atMin = false }
                if (t1 > tmin) { tmin = t1; entryAxis = ax; entryAtMin = atMin }
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) { ok = false; break }
            }
            if (!ok || tmax < 0 || entryAxis < 0) continue
            val t = if (tmin >= 0) tmin else continue // origem dentro do cubo não conta como acerto
            if (best == null || t < best.distance) {
                val face = when (entryAxis) {
                    0 -> if (entryAtMin) FaceName.WEST else FaceName.EAST
                    1 -> if (entryAtMin) FaceName.DOWN else FaceName.UP
                    else -> if (entryAtMin) FaceName.NORTH else FaceName.SOUTH
                }
                best = Hit(pc.bone, pc.index, face, t, ray.origin + ray.dir * t)
            }
        }
        return best
    }
}

class ScreenPoint(val x: Double, val y: Double, val depth: Double)

/**
 * Câmera orbital controlada por toque. yaw = 0 e pitch = 0 olham para a face norte (-Z) do modelo.
 * Pura matemática: serve tanto ao renderizador por software deste módulo quanto a uma tela OpenGL/Compose.
 */
class OrbitCamera(
    var target: Vec3 = Vec3.ZERO,
    var yaw: Double = 0.0,
    var pitch: Double = 15.0,
    var distance: Double = 60.0,
    var fovDeg: Double = 45.0,
) {
    fun eye(): Vec3 {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        return target + Vec3(sin(y) * cos(p), sin(p), -cos(y) * cos(p)) * distance
    }

    /** (direita, cima, frente) da câmera. */
    fun basis(): Triple<Vec3, Vec3, Vec3> {
        val f = (target - eye()).normalized()
        val r = f.cross(Vec3(0.0, 1.0, 0.0)).normalized()
        val u = r.cross(f).normalized()
        return Triple(r, u, f)
    }

    private fun focal(h: Int) = (h / 2.0) / tan(Math.toRadians(fovDeg) / 2)

    fun project(p: Vec3, w: Int, h: Int, near: Double = 0.05): ScreenPoint? {
        val (r, u, f) = basis()
        val rel = p - eye()
        val z = rel.dot(f)
        if (z <= near) return null
        val k = focal(h)
        return ScreenPoint(w / 2.0 + rel.dot(r) / z * k, h / 2.0 - rel.dot(u) / z * k, z)
    }

    fun ray(px: Double, py: Double, w: Int, h: Int): Ray {
        val (r, u, f) = basis()
        val k = focal(h)
        val dir = (r * ((px - w / 2.0) / k) + u * (-(py - h / 2.0) / k) + f).normalized()
        return Ray(eye(), dir)
    }

    /** Arrastar com um dedo: gira em torno do alvo. */
    fun orbit(dxPixels: Double, dyPixels: Double, degreesPerPixel: Double = 0.4) {
        yaw += dxPixels * degreesPerPixel
        pitch = (pitch + dyPixels * degreesPerPixel).coerceIn(-89.0, 89.0)
    }

    fun zoom(factor: Double) {
        require(factor > 0)
        distance = (distance * factor).coerceIn(0.5, 5000.0)
    }

    /** Arrastar com dois dedos: desloca o alvo no plano da tela. */
    fun pan(dxPixels: Double, dyPixels: Double, h: Int) {
        val (r, u, _) = basis()
        val perPixel = distance / focal(h)
        target = target - r * (dxPixels * perPixel) + u * (dyPixels * perPixel)
    }

    /** Enquadra a caixa [box] inteira na vista. */
    fun frame(box: Aabb, margin: Double = 1.25) {
        target = box.center
        val radius = max(box.radius, 0.5)
        distance = (radius / sin(Math.toRadians(fovDeg) / 2)) * margin
    }
}
