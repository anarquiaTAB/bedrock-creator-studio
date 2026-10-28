package studio.core.diagnostics

import studio.core.animation.*
import studio.core.geometry.*
import studio.core.json.*

/** Verificações de geometria, animações e controladores, e das ligações entre eles e as entidades de cliente. */
object ModelChecks {
    private fun d(sev: Severity, cat: Category, code: String, msg: String, file: String?) = Diagnostic(sev, cat, code, msg, file)

    fun checkGeometryFile(root: JsonObj, file: String?): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val gf = try { GeometryFile.from(root) } catch (e: GeometryFormatException) { return emptyList() }
        if (gf.legacy()) {
            out += d(Severity.INFO, Category.COMPATIBILITY, "GEO_LEGACY_FORMAT", "Formato antigo de geometria (1.8): o editor visual o converte ao salvar.", file)
        } else if (root["format_version"] == null) {
            out += d(Severity.ERROR, Category.STRUCTURE, "GEO_FORMAT_VERSION", "Falta format_version na geometria.", file)
        }
        val entries = gf.entries(file)
        val dup = entries.groupBy { it.identifier }.filter { it.value.size > 1 }.keys
        for (id in dup) out += d(Severity.ERROR, Category.STRUCTURE, "GEO_DUPLICATE", "Geometria '$id' definida mais de uma vez no arquivo.", file)
        for (e in entries) {
            out += e.diagnostics.distinct()
            e.geometry?.let { out += checkGeometry(it, file) }
        }
        return out
    }

    fun checkGeometry(g: Geometry, file: String?): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        fun w(code: String, msg: String, cat: Category = Category.STRUCTURE) = out.add(d(Severity.WARNING, cat, code, "${g.identifier}: $msg", file))
        fun e(code: String, msg: String) = out.add(d(Severity.ERROR, Category.STRUCTURE, code, "${g.identifier}: $msg", file))
        if (!g.identifier.startsWith("geometry.")) w("GEO_ID_PREFIX", "o identificador deveria começar com 'geometry.'.")
        if (g.bones.isEmpty()) w("GEO_NO_BONES", "a geometria não tem ossos.")
        if (g.textureWidth == null || g.textureHeight == null) {
            w("GEO_TEXTURE_SIZE_MISSING", "texture_width/texture_height ausentes; o tamanho padrão assumido pelo jogo não é verificado aqui.")
        }
        val lower = g.bones.groupBy { it.name.lowercase() }.filter { it.value.size > 1 }
        for ((_, list) in lower) e("GEO_BONE_DUPLICATE", "osso repetido: ${list.joinToString { "'${it.name}'" }} (nomes não diferenciam maiúsculas).")
        for (b in g.bones) {
            if (b.parent != null && !g.hasBone(b.parent)) e("GEO_PARENT_MISSING", "o osso '${b.name}' tem pai '${b.parent}' que não existe.")
            if (g.inCycle(b.name)) e("GEO_CYCLE", "o osso '${b.name}' participa de um ciclo de parentesco.")
            b.cubes.forEachIndexed { i, c ->
                val where = "osso '${b.name}', cubo $i"
                if (c.size.x == 0.0 && c.size.y == 0.0 && c.size.z == 0.0) w("GEO_CUBE_EMPTY", "$where tem tamanho zero.")
                if (c.size.x < 0 || c.size.y < 0 || c.size.z < 0) w("GEO_CUBE_NEGATIVE", "$where tem tamanho negativo.", Category.COMPATIBILITY)
                if (c.rotation != null && c.pivot == null) w("GEO_CUBE_NO_PIVOT", "$where tem rotation sem pivot; o ponto de giro assumido pelo jogo não é verificado aqui.", Category.COMPATIBILITY)
                if (c.uv is CubeUv.Box && (c.size.x != Math.rint(c.size.x) || c.size.y != Math.rint(c.size.y) || c.size.z != Math.rint(c.size.z))) {
                    w("GEO_FRACTIONAL_BOX_UV", "$where tem tamanho fracionário com UV de caixa; o desdobramento é arredondado na prévia (confirmar no jogo).", Category.COMPATIBILITY)
                }
                val tw = g.textureWidth; val th = g.textureHeight
                if (tw != null && th != null) {
                    for ((face, r) in CubeUvs.faceRects(c, b.mirror)) {
                        if (r.x < 0 || r.y < 0 || r.x + r.w > tw + 1e-9 || r.y + r.h > th + 1e-9) {
                            w("GEO_UV_BOUNDS", "$where: a face ${face.key} sai da textura ${tw}x$th.")
                            break
                        }
                    }
                }
            }
        }
        for (o in UvLayout.overlaps(g)) {
            w("GEO_UV_OVERLAP", "os UVs de '${o.a.bone}'[${o.a.index}] e '${o.b.bone}'[${o.b.index}] se sobrepõem em ${o.pixels} pixel(s).")
        }
        return out
    }

    private fun GeometryFile.legacy(): Boolean = LegacyGeometry.isLegacy(root)

    fun checkAnimationFile(root: JsonObj, file: String?): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val af = try { AnimationFile.from(root) } catch (e: GeometryFormatException) { return emptyList() }
        if (root["format_version"] == null) out += d(Severity.ERROR, Category.STRUCTURE, "ANIM_FORMAT_VERSION", "Falta format_version no arquivo de animações.", file)
        for (en in af.entries(file)) {
            out += en.diagnostics
            if (!en.name.startsWith("animation.")) out += d(Severity.WARNING, Category.STRUCTURE, "ANIM_NAME", "'${en.name}': o nome deveria começar com 'animation.'.", file)
            val a = en.animation ?: continue
            val len = a.duration
            for ((bone, ba) in a.bones) for (k in ChannelKind.values()) {
                val ch = ba.channel(k) ?: continue
                if (a.length != null) for (key in ch.keys) if (key.time > a.length + 1e-6) {
                    out += d(Severity.WARNING, Category.STRUCTURE, "ANIM_KEY_AFTER_END", "'${en.name}': '$bone'/${k.key} tem quadro-chave em ${key.time} depois do fim (${a.length}).", file)
                    break
                }
                for (key in ch.keys) if (key.lerpMode != null && key.lerpMode != LerpMode.CATMULLROM) {
                    out += d(Severity.WARNING, Category.COMPATIBILITY, "ANIM_LERP_MODE", "'${en.name}': modo de interpolação '${key.lerpMode}' não é interpretado na prévia (mantido no arquivo).", file)
                    break
                }
                val exprs = (ch.keys.flatMap { listOf(it.pre, it.post) } + listOfNotNull(ch.constant)).flatMap { listOf(it.x, it.y, it.z) }.filterIsInstance<Scalar.Expr>()
                for (x in exprs.map { it.src }.distinct()) {
                    val ex = Molang.check(x) ?: continue
                    if (ex.unsupported) out += d(Severity.INFO, Category.COMPATIBILITY, "MOLANG_PREVIEW", "'${en.name}': '$bone': ${ex.message} (pode funcionar no jogo; a prévia usa 0).", file)
                    else out += d(Severity.ERROR, Category.SYNTAX, "MOLANG_SYNTAX", "'${en.name}': '$bone': expressão \"$x\" inválida: ${ex.message}", file)
                }
            }
            if (a.loop == Loop.LOOP && len <= 0.0) out += d(Severity.WARNING, Category.STRUCTURE, "ANIM_LOOP_NO_LENGTH", "'${en.name}': animação em loop sem duração.", file)
        }
        return out
    }

    fun checkControllerFile(root: JsonObj, file: String?): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val cf = try { ControllerFile.from(root) } catch (e: GeometryFormatException) { return emptyList() }
        if (root["format_version"] == null) out += d(Severity.ERROR, Category.STRUCTURE, "CTRL_FORMAT_VERSION", "Falta format_version no arquivo de controladores.", file)
        for (en in cf.entries(file)) {
            out += en.diagnostics
            val c = en.controller ?: continue
            if (!en.name.startsWith("controller.animation.")) out += d(Severity.WARNING, Category.STRUCTURE, "CTRL_NAME", "'${en.name}': o nome deveria começar com 'controller.animation.'.", file)
            val start = c.initialState ?: "default"
            if (c.state(start) == null) {
                out += d(Severity.ERROR, Category.STRUCTURE, "CTRL_INITIAL_MISSING", "'${en.name}': o estado inicial '$start' não existe.", file)
            }
            for (s in c.states) {
                for (t in s.transitions) {
                    if (c.state(t.target) == null) out += d(Severity.ERROR, Category.STRUCTURE, "CTRL_TARGET_MISSING", "'${en.name}': '${s.name}' tem transição para '${t.target}', que não existe.", file)
                    checkMolang(t.condition, "'${en.name}'/${s.name}", file, out)
                }
                for (a in s.animations) a.blend?.let { checkMolang(it, "'${en.name}'/${s.name}/${a.name}", file, out) }
            }
            if (c.state(start) != null) {
                val reach = c.reachable()
                for (s in c.states) if (s.name !in reach) out += d(Severity.WARNING, Category.STRUCTURE, "CTRL_UNREACHABLE", "'${en.name}': o estado '${s.name}' nunca é alcançado.", file)
            }
        }
        return out
    }

    private fun checkMolang(src: String, where: String, file: String?, out: MutableList<Diagnostic>) {
        val ex = Molang.check(src) ?: return
        if (ex.unsupported) out += d(Severity.INFO, Category.COMPATIBILITY, "MOLANG_PREVIEW", "$where: ${ex.message}", file)
        else out += d(Severity.ERROR, Category.SYNTAX, "MOLANG_SYNTAX", "$where: expressão \"$src\" inválida: ${ex.message}", file)
    }

    /**
     * [json] = arquivos JSON do pacote por caminho relativo; [shown] formata o nome de arquivo exibido.
     * Verifica cada arquivo de modelo/animação/controlador e as referências das entidades de cliente.
     */
    fun check(json: Map<String, JsonObj>, shown: (String) -> String): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val geos = HashMap<String, Geometry>()
        val anims = HashMap<String, Animation>()
        val animNames = HashSet<String>()
        val ctrls = HashMap<String, Controller>()
        val ctrlNames = HashSet<String>()
        for ((rel, root) in json) {
            val f = shown(rel)
            when {
                rel.startsWith("models/") && (root["minecraft:geometry"] != null || LegacyGeometry.isLegacy(root)) -> {
                    out += checkGeometryFile(root, f)
                    runCatching { GeometryFile.from(root).entries() }.getOrNull()?.forEach { en -> en.geometry?.let { geos.putIfAbsent(it.identifier, it) } }
                }
                rel.startsWith("animations/") && root.obj("animations") != null -> {
                    out += checkAnimationFile(root, f)
                    runCatching { AnimationFile.from(root).entries() }.getOrNull()?.forEach { en -> animNames.add(en.name); en.animation?.let { anims.putIfAbsent(it.name, it) } }
                }
                rel.startsWith("animation_controllers/") && root.obj("animation_controllers") != null -> {
                    out += checkControllerFile(root, f)
                    runCatching { ControllerFile.from(root).entries() }.getOrNull()?.forEach { en -> ctrlNames.add(en.name); en.controller?.let { ctrls.putIfAbsent(it.name, it) } }
                }
            }
        }
        for ((rel, root) in json) {
            if (!rel.startsWith("entity/")) continue
            val desc = root.obj("minecraft:client_entity")?.obj("description") ?: continue
            val f = shown(rel)
            val declared = LinkedHashMap<String, String>()
            desc.obj("animations")?.fields?.forEach { (short, v) -> (v as? JsonStr)?.value?.let { declared[short] = it } }
            for ((short, id) in declared) {
                if (id !in animNames && id !in ctrlNames) {
                    out += d(Severity.WARNING, Category.STRUCTURE, "REF_ANIMATION_MISSING", "Animação '$id' (\"$short\") não está definida neste pacote. Se não for do jogo base, está faltando.", f)
                }
            }
            val animate = desc.obj("scripts")?.arr("animate")?.items ?: emptyList()
            for (item in animate) {
                val names = when (item) { is JsonStr -> listOf(item.value); is JsonObj -> item.fields.keys.toList(); else -> emptyList() }
                for (n in names) if (n !in declared) out += d(Severity.WARNING, Category.STRUCTURE, "REF_ANIMATE_UNDEFINED", "scripts.animate usa '$n', que não está em \"animations\".", f)
            }
            for ((_, id) in declared) {
                val c = ctrls[id] ?: continue
                for (s in c.states) for (a in s.animations) if (a.name !in declared) {
                    out += d(Severity.WARNING, Category.STRUCTURE, "REF_CONTROLLER_ANIM_UNDEFINED", "O controlador '$id', estado '${s.name}', usa '${a.name}', que a entidade não declara em \"animations\".", f)
                }
            }
            val entityGeos = desc.obj("geometry")?.fields?.values?.mapNotNull { (it as? JsonStr)?.value?.substringBefore(':') }?.mapNotNull { geos[it] } ?: emptyList()
            for (g in entityGeos) {
                val bones = g.bones.map { it.name.lowercase() }.toSet()
                for ((short, id) in declared) {
                    val a = anims[id] ?: continue
                    val missing = a.bones.keys.filter { it.lowercase() !in bones }
                    if (missing.isNotEmpty()) {
                        out += d(Severity.WARNING, Category.STRUCTURE, "ANIM_BONE_MISSING",
                            "A animação \"$short\" ($id) mexe em ossos que a geometria ${g.identifier} não tem: ${missing.joinToString { "'$it'" }}.", f)
                    }
                }
            }
        }
        return out
    }
}
