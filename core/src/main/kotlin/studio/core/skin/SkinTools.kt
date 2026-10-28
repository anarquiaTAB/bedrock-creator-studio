package studio.core.skin

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.image.Bitmap
import studio.core.image.Png
import studio.core.image.PngException
import studio.core.manifest.ManifestBuilder
import studio.core.manifest.ManifestSpec
import studio.core.manifest.PackKind
import studio.core.manifest.Uuids
import studio.core.json.*
import studio.core.storage.AtomicFiles
import java.nio.file.Path

object SkinValidator {
    private val STANDARD = setOf(64 to 32, 64 to 64, 128 to 128)

    /** Tamanhos comuns: 64x32 (antigo), 64x64 e 128x128. Outros geram aviso (não confirmado com o jogo). */
    fun checkSize(w: Int, h: Int, file: String? = null): Diagnostic? =
        if ((w to h) in STANDARD) null else Diagnostic(
            Severity.WARNING, Category.COMPATIBILITY, "SKIN_SIZE_UNUSUAL",
            "Tamanho de skin incomum (${w}x$h). Os tamanhos usuais são 64x32, 64x64 e 128x128; teste no jogo.", file,
        )

    fun validate(bmp: Bitmap, file: String? = null): List<Diagnostic> = listOfNotNull(checkSize(bmp.width, bmp.height, file))
}

object SkinConvert {
    /** Converte uma skin antiga 64x32 para 64x64, espelhando perna e braço direitos nos esquerdos. */
    fun legacyToModern(src: Bitmap): Bitmap {
        require(src.width == 64 && src.height == 32) { "Esperada uma skin 64x32" }
        val out = Bitmap(64, 64)
        out.blit(src, 0, 0)
        mirrorBox(src, out, SkinBox("rl", false, 0, 16, 4, 12, 4), SkinBox("ll", false, 16, 48, 4, 12, 4))
        mirrorBox(src, out, SkinBox("ra", false, 40, 16, 4, 12, 4), SkinBox("la", false, 32, 48, 4, 12, 4))
        return out
    }

    private fun mirrorBox(src: Bitmap, dst: Bitmap, from: SkinBox, to: SkinBox) {
        val f = from.faces()
        val t = to.faces()
        val pairs = listOf(
            Face.TOP to Face.TOP, Face.BOTTOM to Face.BOTTOM, Face.FRONT to Face.FRONT,
            Face.BACK to Face.BACK, Face.RIGHT to Face.LEFT, Face.LEFT to Face.RIGHT,
        )
        for ((a, b) in pairs) {
            val s = f.getValue(a)
            val d = t.getValue(b)
            for (j in 0 until s.h) for (i in 0 until s.w) dst[d.x + (s.w - 1 - i), d.y + j] = src[s.x + i, s.y + j]
        }
    }
}

class SkinEntry(val id: String, val displayName: String, val slim: Boolean, val texture: Bitmap)

class SkinPackSpec(
    val packId: String,
    val name: String,
    val description: String,
    val skins: List<SkinEntry>,
    val headerUuid: String = Uuids.random(),
    val moduleUuid: String = Uuids.random(),
    val version: List<Int> = listOf(1, 0, 0),
    val minEngineVersion: List<Int>? = null,
)

/**
 * Gera um pacote de skins: manifest.json, skins.json, texts/xx_XX.lang e as texturas PNG.
 * A estrutura segue o que o autor conhece do formato; NÃO foi conferida com a documentação oficial
 * atual nem testada no jogo.
 */
object SkinPackBuilder {
    private val ID = Regex("^[a-z0-9_]+$")

    fun build(spec: SkinPackSpec): Map<String, ByteArray> {
        require(ID.matches(spec.packId)) { "packId deve usar apenas a-z, 0-9 e _" }
        require(spec.skins.isNotEmpty()) { "O pacote precisa de ao menos uma skin" }
        require(spec.skins.map { it.id }.toSet().size == spec.skins.size) { "Há skins com id repetido" }
        for (s in spec.skins) {
            require(ID.matches(s.id)) { "Id de skin inválido: '${s.id}' (use a-z, 0-9 e _)" }
            require(s.displayName.isNotBlank() && !s.displayName.contains('\n')) { "Nome de exibição inválido" }
            val sizeWarning = SkinValidator.checkSize(s.texture.width, s.texture.height)
            require(sizeWarning == null) { "Skin '${s.id}': ${sizeWarning!!.message}" }
        }
        val files = LinkedHashMap<String, ByteArray>()
        val manifest = ManifestBuilder.build(
            ManifestSpec(
                PackKind.SKIN, spec.name, spec.description, spec.headerUuid, spec.moduleUuid,
                spec.version, spec.minEngineVersion,
            ),
        )
        files["manifest.json"] = (Json.write(manifest) + "\n").toByteArray()

        val skins = spec.skins.map { s ->
            jsonObj(
                "localization_name" to JsonStr(s.id),
                "geometry" to JsonStr(if (s.slim) "minecraft:geometry.humanoid.customSlim" else "minecraft:geometry.humanoid.custom"),
                "texture" to JsonStr("${s.id}.png"),
                "type" to JsonStr("free"),
            )
        }
        val skinsJson = jsonObj(
            "serialize_name" to JsonStr(spec.packId),
            "localization_name" to JsonStr(spec.packId),
            "skins" to JsonArr(skins),
        )
        files["skins.json"] = (Json.write(skinsJson) + "\n").toByteArray()

        val lang = StringBuilder("skinpack.${spec.packId}=${spec.name}\n")
        for (s in spec.skins) lang.append("skin.${spec.packId}.${s.id}=${s.displayName}\n")
        files["texts/en_US.lang"] = lang.toString().toByteArray()
        files["texts/pt_BR.lang"] = lang.toString().toByteArray()
        files["texts/languages.json"] = "[\"en_US\", \"pt_BR\"]\n".toByteArray()
        for (s in spec.skins) files["${s.id}.png"] = Png.encode(s.texture)
        return files
    }

    fun writeTo(dir: Path, spec: SkinPackSpec) {
        for ((rel, bytes) in build(spec)) AtomicFiles.writeBytes(dir.resolve(rel), bytes)
    }

    /** Lê uma textura de skin existente (PNG) para edição. */
    fun readTexture(png: ByteArray): Bitmap = try {
        Png.decode(png)
    } catch (e: PngException) {
        throw IllegalArgumentException("Imagem de skin inválida: ${e.message}")
    }
}
