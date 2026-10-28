package studio.core.geometry

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.json.*
import studio.core.util.Num

class GeometryFormatException(message: String) : Exception(message)

internal fun jnum(d: Double) = JsonNum(Num.fmt(d))
internal fun jvec(v: Vec3) = JsonArr(listOf(jnum(v.x), jnum(v.y), jnum(v.z)))

internal fun numOf(v: JsonValue?): Double? = (v as? JsonNum)?.raw?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun vecOf(v: JsonValue?): Vec3? {
    val a = v as? JsonArr ?: return null
    if (a.items.size != 3) return null
    val n = a.items.map { numOf(it) ?: return null }
    return Vec3(n[0], n[1], n[2])
}

internal fun vec2Of(v: JsonValue?): Pair<Double, Double>? {
    val a = v as? JsonArr ?: return null
    if (a.items.size != 2) return null
    return (numOf(a.items[0]) ?: return null) to (numOf(a.items[1]) ?: return null)
}

private fun err(code: String, msg: String, file: String?) = Diagnostic(Severity.ERROR, Category.STRUCTURE, code, msg, file)

/** Leitura e escrita do objeto de geometria (formato moderno, "minecraft:geometry"). */
object GeometryJson {
    /** Padrão para geometrias novas. CONFERIR com a documentação do jogo na versão-alvo. */
    const val DEFAULT_FORMAT_VERSION = "1.12.0"

    /** Lê uma geometria. Se houver qualquer erro estrutural, devolve null: o arquivo não é editado pelo editor visual. */
    fun parse(o: JsonObj, file: String? = null): Pair<Geometry?, List<Diagnostic>> {
        val d = ArrayList<Diagnostic>()
        val desc = o.obj("description")
        val ident = desc?.str("identifier")
        if (desc == null || ident == null) {
            d += err("GEO_IDENTIFIER", "A geometria não tem description.identifier.", file)
            return null to d
        }
        fun dim(key: String): Int? {
            val v = desc[key] ?: return null
            val n = numOf(v)
            if (n == null || n != Math.rint(n) || n < 1 || n > 16384) {
                d += err("GEO_TEXTURE_SIZE", "description.$key deve ser um inteiro positivo.", file)
                return null
            }
            return n.toInt()
        }
        val tw = dim("texture_width")
        val th = dim("texture_height")
        val boneList = o["bones"]
        val bones = ArrayList<Bone>()
        if (boneList != null && boneList !is JsonArr) d += err("GEO_BONES", "\"bones\" deve ser uma lista.", file)
        (boneList as? JsonArr)?.items?.forEachIndexed { i, bv ->
            val b = bv as? JsonObj
            if (b == null) {
                d += err("GEO_BONE", "bones[$i] deve ser um objeto.", file)
                return@forEachIndexed
            }
            parseBone(b, i, d, file)?.let { bones.add(it) }
        }
        if (d.any { it.severity == Severity.ERROR }) return null to d
        return Geometry(ident, tw, th, bones, desc, o) to d
    }

    private fun parseBone(b: JsonObj, i: Int, d: MutableList<Diagnostic>, file: String?): Bone? {
        val name = b.str("name")
        if (name.isNullOrEmpty()) {
            d += err("GEO_BONE_NAME", "bones[$i] não tem \"name\".", file)
            return null
        }
        fun vec(key: String): Vec3? {
            val v = b[key] ?: return null
            return vecOf(v) ?: run {
                d += err("GEO_BONE_FIELD", "Osso '$name': \"$key\" deve ser [x, y, z] numérico.", file)
                null
            }
        }
        val pivot = vec("pivot") ?: Vec3.ZERO
        val rotation = vec("rotation")
        val parent = b.str("parent")
        if (b["parent"] != null && parent == null) d += err("GEO_BONE_FIELD", "Osso '$name': \"parent\" deve ser texto.", file)
        val mirror = (b["mirror"] as? JsonBool)?.value
        val inflate = numOf(b["inflate"])
        val cubes = ArrayList<Cube>()
        val cv = b["cubes"]
        if (cv != null && cv !is JsonArr) d += err("GEO_CUBES", "Osso '$name': \"cubes\" deve ser uma lista.", file)
        (cv as? JsonArr)?.items?.forEachIndexed { ci, c ->
            val co = c as? JsonObj
            if (co == null) {
                d += err("GEO_CUBE", "Osso '$name': cubes[$ci] deve ser um objeto.", file)
                return@forEachIndexed
            }
            parseCube(co, name, ci, d, file)?.let { cubes.add(it) }
        }
        return Bone(name, parent, pivot, rotation, mirror, inflate, cubes, b)
    }

    private fun parseCube(c: JsonObj, bone: String, i: Int, d: MutableList<Diagnostic>, file: String?): Cube? {
        val where = "Osso '$bone', cubo $i"
        val origin = vecOf(c["origin"])
        val size = vecOf(c["size"])
        if (origin == null) d += err("GEO_CUBE_ORIGIN", "$where: \"origin\" deve ser [x, y, z] numérico.", file)
        if (size == null) d += err("GEO_CUBE_SIZE", "$where: \"size\" deve ser [x, y, z] numérico.", file)
        val uvv = c["uv"]
        val uv: CubeUv? = when (uvv) {
            is JsonArr -> vec2Of(uvv)?.let { CubeUv.Box(it.first, it.second) }
            is JsonObj -> parseFaces(uvv, where, d, file)
            null -> CubeUv.Box(0.0, 0.0) // sem "uv": o jogo usa a origem; gravado só se o usuário alterar
            else -> null
        }
        if (uv == null && uvv != null && !(uvv is JsonObj)) d += err("GEO_CUBE_UV", "$where: \"uv\" deve ser [u, v] ou um objeto por face.", file)
        fun optVec(k: String): Vec3? = c[k]?.let { vecOf(it) ?: run { d += err("GEO_CUBE_FIELD", "$where: \"$k\" deve ser [x, y, z] numérico.", file); null } }
        val pivot = optVec("pivot")
        val rotation = optVec("rotation")
        if (origin == null || size == null || uv == null) return null
        return Cube(origin, size, uv, pivot, rotation, numOf(c["inflate"]), (c["mirror"] as? JsonBool)?.value, c)
    }

    private fun parseFaces(o: JsonObj, where: String, d: MutableList<Diagnostic>, file: String?): CubeUv? {
        val faces = LinkedHashMap<FaceName, FaceUv>()
        var unknown = JsonObj.empty()
        for ((k, v) in o.fields) {
            val face = FaceName.fromKey(k)
            if (face == null) {
                unknown = unknown.with(k, v)
                continue
            }
            val fo = v as? JsonObj
            val uv = vec2Of(fo?.get("uv"))
            if (fo == null || uv == null) {
                d += err("GEO_FACE_UV", "$where: face \"$k\" precisa de \"uv\": [u, v].", file)
                continue
            }
            val sz = fo["uv_size"]?.let { vec2Of(it) ?: run { d += err("GEO_FACE_UV", "$where: face \"$k\": uv_size inválido.", file); null } }
            faces[face] = FaceUv(uv.first, uv.second, sz?.first, sz?.second, fo)
        }
        if (d.any { it.severity == Severity.ERROR }) return null
        return CubeUv.PerFace(faces, unknown)
    }

    // ---- escrita ----

    fun toJson(g: Geometry): JsonObj {
        var desc = g.descriptionRaw.with("identifier", JsonStr(g.identifier))
        if (g.textureWidth != null) desc = desc.with("texture_width", JsonNum(g.textureWidth.toString()))
        if (g.textureHeight != null) desc = desc.with("texture_height", JsonNum(g.textureHeight.toString()))
        return g.raw.with("description", desc).with("bones", JsonArr(g.bones.map { boneJson(it) }))
    }

    private fun boneJson(b: Bone): JsonObj {
        var o = b.raw.with("name", JsonStr(b.name))
        o = if (b.parent != null) o.with("parent", JsonStr(b.parent)) else o.without("parent")
        o = if (b.pivot.isZero() && o["pivot"] == null) o else o.with("pivot", jvec(b.pivot))
        o = if (b.rotation != null) o.with("rotation", jvec(b.rotation)) else o.without("rotation")
        o = if (b.mirror != null) o.with("mirror", JsonBool(b.mirror)) else o.without("mirror")
        o = if (b.inflate != null) o.with("inflate", jnum(b.inflate)) else o.without("inflate")
        o = if (b.cubes.isEmpty() && o["cubes"] == null) o else o.with("cubes", JsonArr(b.cubes.map { cubeJson(it) }))
        return o
    }

    private fun cubeJson(c: Cube): JsonObj {
        var o = c.raw.with("origin", jvec(c.origin)).with("size", jvec(c.size))
        o = when (val uv = c.uv) {
            is CubeUv.Box -> if (c.raw["uv"] == null && uv.u == 0.0 && uv.v == 0.0) o else o.with("uv", JsonArr(listOf(jnum(uv.u), jnum(uv.v))))
            is CubeUv.PerFace -> o.with("uv", facesJson(uv))
        }
        o = if (c.pivot != null) o.with("pivot", jvec(c.pivot)) else o.without("pivot")
        o = if (c.rotation != null) o.with("rotation", jvec(c.rotation)) else o.without("rotation")
        o = if (c.inflate != null) o.with("inflate", jnum(c.inflate)) else o.without("inflate")
        o = if (c.mirror != null) o.with("mirror", JsonBool(c.mirror)) else o.without("mirror")
        return o
    }

    private fun facesJson(uv: CubeUv.PerFace): JsonObj {
        var o = uv.raw
        for (face in FaceName.values()) {
            val f = uv.faces[face] ?: continue
            var fo = f.raw.with("uv", JsonArr(listOf(jnum(f.u), jnum(f.v))))
            fo = if (f.w != null && f.h != null) fo.with("uv_size", JsonArr(listOf(jnum(f.w), jnum(f.h)))) else fo.without("uv_size")
            o = o.with(face.key, fo)
        }
        return o
    }
}

/** Formato antigo (1.8): geometrias como chaves "geometry.nome" na raiz. Só leitura/conversão. */
object LegacyGeometry {
    private val DROPPED_BONE_FIELDS = listOf("bind_pose_rotation", "reset")

    fun isLegacy(root: JsonObj): Boolean =
        root["minecraft:geometry"] == null && root.fields.keys.any { it.startsWith("geometry.") }

    class Converted(val geometries: List<Geometry>, val diagnostics: List<Diagnostic>)

    fun convert(root: JsonObj, file: String? = null): Converted {
        val d = ArrayList<Diagnostic>()
        val out = ArrayList<Geometry>()
        for ((key, v) in root.fields) {
            if (!key.startsWith("geometry.")) continue
            val o = v as? JsonObj
            if (o == null) {
                d += err("GEO_LEGACY", "\"$key\" deve ser um objeto.", file)
                continue
            }
            val ident = key.substringBefore(':')
            if (key.contains(':')) {
                d += Diagnostic(Severity.WARNING, Category.COMPATIBILITY, "GEO_LEGACY_INHERIT",
                    "'$key' herda de outra geometria; a herança não é convertida (os ossos do pai não são copiados).", file)
            }
            var desc = JsonObj.empty()
            for (k in listOf("visible_bounds_width", "visible_bounds_height", "visible_bounds_offset")) o[k]?.let { desc = desc.with(k, it) }
            val tw = numOf(o["texturewidth"])?.toInt()
            val th = numOf(o["textureheight"])?.toInt()
            val bones = ArrayList<Bone>()
            val dropped = LinkedHashSet<String>()
            (o["bones"] as? JsonArr)?.items?.forEachIndexed { i, bv ->
                var b = bv as? JsonObj ?: return@forEachIndexed
                for (f in DROPPED_BONE_FIELDS) if (b[f] != null) { dropped.add(f); b = b.without(f) }
                val diags = ArrayList<Diagnostic>()
                val bone = parseLegacyBone(b, i, diags, file)
                d += diags
                if (bone != null) bones.add(bone)
            }
            if (dropped.isNotEmpty()) {
                d += Diagnostic(Severity.INFO, Category.COMPATIBILITY, "GEO_LEGACY_DROPPED",
                    "Campos legados descartados na conversão de '$ident': ${dropped.joinToString()}.", file)
            }
            val extra = JsonObj(LinkedHashMap(o.fields).also { m ->
                for (k in listOf("texturewidth", "textureheight", "bones", "visible_bounds_width", "visible_bounds_height", "visible_bounds_offset")) m.remove(k)
            })
            if (extra.fields.isNotEmpty()) {
                d += Diagnostic(Severity.INFO, Category.COMPATIBILITY, "GEO_LEGACY_EXTRA",
                    "Campos de '$ident' mantidos como estão: ${extra.fields.keys.joinToString()} (confira se existem no formato novo).", file)
            }
            out.add(Geometry(ident, tw, th, bones, desc, extra))
        }
        return Converted(out, d)
    }

    // O osso legado tem o mesmo formato do moderno para os campos que o estúdio edita.
    private fun parseLegacyBone(b: JsonObj, i: Int, d: MutableList<Diagnostic>, file: String?): Bone? {
        val wrapper = jsonObj("description" to jsonObj("identifier" to JsonStr("x")), "bones" to JsonArr(listOf(b)))
        val (g, diags) = GeometryJson.parse(wrapper, file)
        d += diags.map { it.copy(message = it.message.replace("bones[0]", "bones[$i]")) }
        return g?.bones?.firstOrNull()
    }
}

/** Arquivo de geometria (um ou mais modelos). Preserva geometrias que a interface não consegue ler. */
class GeometryFile private constructor(val root: JsonObj, val legacy: Boolean) {
    class Entry(val identifier: String, val geometry: Geometry?, val diagnostics: List<Diagnostic>)

    val formatVersion: String? get() = root.str("format_version")

    fun entries(file: String? = null): List<Entry> {
        if (legacy) {
            val c = LegacyGeometry.convert(root, file)
            return c.geometries.map { Entry(it.identifier, it, c.diagnostics) }
        }
        val arr = root.arr("minecraft:geometry")?.items ?: return emptyList()
        return arr.mapIndexed { i, v ->
            val o = v as? JsonObj
            if (o == null) {
                Entry("#$i", null, listOf(err("GEO_ENTRY", "minecraft:geometry[$i] deve ser um objeto.", file)))
            } else {
                val (g, d) = GeometryJson.parse(o, file)
                Entry(g?.identifier ?: o.obj("description")?.str("identifier") ?: "#$i", g, d)
            }
        }
    }

    fun identifiers(): List<String> = entries().map { it.identifier }

    fun find(identifier: String, file: String? = null): Entry? = entries(file).firstOrNull { it.identifier == identifier }

    private fun requireModern() {
        if (legacy) throw GeometryFormatException("Arquivo no formato antigo (1.8): converta para o formato novo antes de salvar com o editor.")
    }

    private fun items(): List<JsonValue> = root.arr("minecraft:geometry")?.items ?: emptyList()

    private fun indexOf(identifier: String): Int =
        items().indexOfFirst { (it as? JsonObj)?.obj("description")?.str("identifier") == identifier }

    /** Substitui a geometria de mesmo identificador (ou, com [previousIdentifier], renomeia). Demais geometrias ficam intactas. */
    fun replace(g: Geometry, previousIdentifier: String = g.identifier): GeometryFile {
        requireModern()
        val idx = indexOf(previousIdentifier)
        if (idx < 0) throw GeometryFormatException("Geometria '$previousIdentifier' não encontrada no arquivo.")
        if (g.identifier != previousIdentifier && indexOf(g.identifier) >= 0) {
            throw GeometryFormatException("Já existe uma geometria '${g.identifier}' neste arquivo.")
        }
        val list = items().toMutableList()
        list[idx] = GeometryJson.toJson(g)
        return GeometryFile(root.with("minecraft:geometry", JsonArr(list)), false)
    }

    fun add(g: Geometry): GeometryFile {
        requireModern()
        if (indexOf(g.identifier) >= 0) throw GeometryFormatException("Já existe uma geometria '${g.identifier}' neste arquivo.")
        return GeometryFile(root.with("minecraft:geometry", JsonArr(items() + GeometryJson.toJson(g))), false)
    }

    fun remove(identifier: String): GeometryFile {
        requireModern()
        val idx = indexOf(identifier)
        if (idx < 0) throw GeometryFormatException("Geometria '$identifier' não encontrada no arquivo.")
        return GeometryFile(root.with("minecraft:geometry", JsonArr(items().filterIndexed { i, _ -> i != idx })), false)
    }

    /** Converte um arquivo antigo para o formato novo. Diagnósticos informam o que mudou. */
    fun toModern(version: String = GeometryJson.DEFAULT_FORMAT_VERSION, file: String? = null): Pair<GeometryFile, List<Diagnostic>> {
        if (!legacy) return this to emptyList()
        val c = LegacyGeometry.convert(root, file)
        val modern = jsonObj(
            "format_version" to JsonStr(version),
            "minecraft:geometry" to JsonArr(c.geometries.map { GeometryJson.toJson(it) }),
        )
        return GeometryFile(modern, false) to c.diagnostics
    }

    fun toText(): String = Json.write(root) + "\n"

    companion object {
        fun parse(text: String): GeometryFile {
            val o = try {
                Json.parseObject(text)
            } catch (e: JsonParseException) {
                throw GeometryFormatException("JSON inválido: ${e.message}")
            }
            return from(o)
        }

        fun from(root: JsonObj): GeometryFile {
            if (root["minecraft:geometry"] != null) return GeometryFile(root, false)
            if (LegacyGeometry.isLegacy(root)) return GeometryFile(root, true)
            throw GeometryFormatException("O arquivo não contém \"minecraft:geometry\" nem geometrias no formato antigo.")
        }

        fun create(g: Geometry, version: String = GeometryJson.DEFAULT_FORMAT_VERSION): GeometryFile =
            GeometryFile(jsonObj("format_version" to JsonStr(version), "minecraft:geometry" to JsonArr(listOf(GeometryJson.toJson(g)))), false)
    }
}
