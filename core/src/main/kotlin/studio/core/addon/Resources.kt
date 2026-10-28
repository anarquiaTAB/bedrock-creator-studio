package studio.core.addon

import studio.core.json.*

/** Atlas de texturas (terrain_texture.json / item_texture.json): adiciona entradas preservando o restante. */
object Atlas {
    private val SHORT = Regex("^[a-z0-9_]+\$")

    private fun merge(existing: String?, packName: String, atlasName: String, shortName: String, path: String, extra: List<Pair<String, JsonValue>>): String {
        require(SHORT.matches(shortName)) { "Nome curto inválido '$shortName' (use a-z, 0-9 e _)" }
        require(path.isNotBlank() && !path.endsWith(".png") && !path.endsWith(".tga")) { "O caminho da textura não deve incluir a extensão" }
        var root: JsonObj = if (existing == null) {
            jsonObj("resource_pack_name" to js(packName), "texture_name" to js(atlasName)).let { b ->
                extra.fold(b) { acc, (k, v) -> acc.with(k, v) }
            }
        } else {
            Json.parseObject(existing)
        }
        val data = root.obj("texture_data") ?: JsonObj.empty()
        root = root.with("texture_data", data.with(shortName, jsonObj("textures" to js(path))))
        return Json.write(root) + "\n"
    }

    fun mergeTerrain(existing: String?, packName: String, shortName: String, path: String): String =
        merge(existing, packName, "atlas.terrain", shortName, path, listOf("padding" to jn(8), "num_mip_levels" to jn(4)))

    fun mergeItem(existing: String?, packName: String, shortName: String, path: String): String =
        merge(existing, packName, "atlas.items", shortName, path, emptyList())
}

/**
 * Arquivos .lang: substitui chaves existentes, preserva comentários, linhas desconhecidas e ordem,
 * e acrescenta as chaves novas no fim.
 */
object LangFile {
    fun merge(existing: String?, entries: Map<String, String>): String {
        for ((k, v) in entries) {
            require(k.isNotBlank() && !k.contains('=') && !k.contains('\n')) { "Chave de tradução inválida: '$k'" }
            require(!v.contains('\n') && !v.contains('\r')) { "O texto de '$k' não pode ter quebra de linha" }
        }
        val pending = LinkedHashMap(entries)
        val lines = (existing ?: "").lines().toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("##")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val replacement = pending.remove(key)
            if (replacement != null) lines[i] = "$key=$replacement"
        }
        for ((k, v) in pending) lines.add("$k=$v")
        return lines.joinToString("\n") + "\n"
    }

    fun blockKey(identifier: String) = "tile.$identifier.name"
    fun itemKey(identifier: String) = "item.$identifier"
    fun entityKey(identifier: String) = "entity.$identifier.name"
    fun spawnEggKey(identifier: String) = "item.spawn_egg.entity.$identifier.name"
}
