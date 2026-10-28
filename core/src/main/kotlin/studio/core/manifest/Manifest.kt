package studio.core.manifest

import studio.core.json.*
import java.nio.charset.StandardCharsets
import java.util.UUID

object Uuids {
    private val REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun random(): String = UUID.randomUUID().toString()

    /** UUID estável derivado de um texto (útil para ids reproduzíveis). */
    fun nameBased(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

    fun isValid(value: String): Boolean = REGEX.matches(value)
}

enum class PackKind(val moduleType: String) {
    RESOURCES("resources"),
    BEHAVIOR("data"),
    SKIN("skin_pack"),
}

sealed class PackDependency {
    data class OnPack(val uuid: String, val version: List<Int>) : PackDependency()

    /** Ex.: módulo "@minecraft/server". A versão é informada pelo chamador; o núcleo não presume qual é a atual. */
    data class OnModule(val moduleName: String, val version: String) : PackDependency()
}

data class ManifestSpec(
    val kind: PackKind,
    val name: String,
    val description: String = "",
    val headerUuid: String = Uuids.random(),
    val moduleUuid: String = Uuids.random(),
    val version: List<Int> = listOf(1, 0, 0),
    val minEngineVersion: List<Int>? = null,
    val dependencies: List<PackDependency> = emptyList(),
    /** Só para pacotes de comportamento: gera um módulo "script" com este ponto de entrada. */
    val scriptEntry: String? = null,
) {
    init {
        require(name.isNotBlank()) { "O nome do pacote não pode ficar vazio" }
        require(Uuids.isValid(headerUuid)) { "UUID do cabeçalho inválido" }
        require(Uuids.isValid(moduleUuid)) { "UUID do módulo inválido" }
        require(headerUuid != moduleUuid) { "UUID do cabeçalho e do módulo devem ser diferentes" }
        require(version.size == 3 && version.all { it >= 0 }) { "A versão deve ter 3 números não negativos" }
        require(minEngineVersion == null || (minEngineVersion.size == 3 && minEngineVersion.all { it >= 0 })) {
            "min_engine_version deve ter 3 números não negativos"
        }
        require(scriptEntry == null || kind == PackKind.BEHAVIOR) { "Scripts só são permitidos em pacotes de comportamento" }
        for (d in dependencies) {
            if (d is PackDependency.OnPack) {
                require(Uuids.isValid(d.uuid) && d.version.size == 3) { "Dependência de pacote inválida" }
            }
        }
    }
}

object ManifestBuilder {
    /** Gera o manifest.json (format_version 2). */
    fun build(spec: ManifestSpec): JsonObj {
        val header = LinkedHashMap<String, JsonValue>()
        header["description"] = JsonStr(spec.description)
        header["name"] = JsonStr(spec.name)
        header["uuid"] = JsonStr(spec.headerUuid)
        header["version"] = jsonInts(spec.version)
        if (spec.minEngineVersion != null) header["min_engine_version"] = jsonInts(spec.minEngineVersion)

        val modules = mutableListOf<JsonValue>(
            jsonObj(
                "type" to JsonStr(spec.kind.moduleType),
                "uuid" to JsonStr(spec.moduleUuid),
                "version" to jsonInts(spec.version),
            ),
        )
        if (spec.scriptEntry != null) {
            modules.add(
                jsonObj(
                    "type" to JsonStr("script"),
                    "language" to JsonStr("javascript"),
                    "uuid" to JsonStr(Uuids.nameBased(spec.moduleUuid + ":script")),
                    "version" to jsonInts(spec.version),
                    "entry" to JsonStr(spec.scriptEntry),
                ),
            )
        }

        val root = LinkedHashMap<String, JsonValue>()
        root["format_version"] = JsonNum("2")
        root["header"] = JsonObj(header)
        root["modules"] = JsonArr(modules)
        if (spec.dependencies.isNotEmpty()) {
            root["dependencies"] = JsonArr(
                spec.dependencies.map { d ->
                    when (d) {
                        is PackDependency.OnPack -> jsonObj("uuid" to JsonStr(d.uuid), "version" to jsonInts(d.version))
                        is PackDependency.OnModule -> jsonObj("module_name" to JsonStr(d.moduleName), "version" to JsonStr(d.version))
                    }
                },
            )
        }
        return JsonObj(root)
    }
}

data class ParsedManifest(
    val formatVersion: Int?,
    val name: String?,
    val headerUuid: String?,
    val version: List<Int>?,
    val moduleUuids: List<String>,
    val moduleTypes: List<String>,
    val dependencies: List<PackDependency>,
)

object ManifestReader {
    /** Leitura tolerante: campos ausentes ou malformados viram null/vazio (quem reclama é o diagnóstico). */
    fun parse(obj: JsonObj): ParsedManifest {
        val header = obj.obj("header")
        val modules = obj.arr("modules")?.items?.mapNotNull { it as? JsonObj } ?: emptyList()
        val deps = mutableListOf<PackDependency>()
        for (d in obj.arr("dependencies")?.items?.mapNotNull { it as? JsonObj } ?: emptyList()) {
            val uuid = d.str("uuid")
            val ver = intList(d["version"])
            val moduleName = d.str("module_name")
            val verText = d.str("version")
            if (uuid != null && ver != null && ver.size == 3) {
                deps.add(PackDependency.OnPack(uuid, ver))
            } else if (moduleName != null && verText != null) {
                deps.add(PackDependency.OnModule(moduleName, verText))
            }
        }
        return ParsedManifest(
            formatVersion = obj.int("format_version"),
            name = header?.str("name"),
            headerUuid = header?.str("uuid"),
            version = intList(header?.get("version")),
            moduleUuids = modules.mapNotNull { it.str("uuid") },
            moduleTypes = modules.mapNotNull { it.str("type") },
            dependencies = deps,
        )
    }

    private fun intList(v: JsonValue?): List<Int>? {
        val arr = v as? JsonArr ?: return null
        val ints = arr.items.mapNotNull { (it as? JsonNum)?.toIntOrNull() }
        return if (ints.size == arr.items.size) ints else null
    }
}
