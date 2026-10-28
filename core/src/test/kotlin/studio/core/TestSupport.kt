package studio.core

import studio.core.json.Json
import studio.core.manifest.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

inline fun withTempDir(block: (Path) -> Unit) {
    val d = Files.createTempDirectory("bcs-test")
    try {
        block(d)
    } finally {
        d.toFile().deleteRecursively()
    }
}

fun writeFile(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.write(path, text.toByteArray(Charsets.UTF_8))
}

fun makeZip(out: Path, entries: List<Pair<String, ByteArray>>) {
    Files.createDirectories(out.parent)
    // java.util.zip recusa nomes duplicados; grava com um nome provisório de mesmo tamanho e corrige os bytes depois.
    val seen = HashSet<String>()
    val patches = ArrayList<Pair<ByteArray, ByteArray>>()
    ZipOutputStream(Files.newOutputStream(out)).use { z ->
        for ((n, b) in entries) {
            var name = n
            if (!seen.add(n)) {
                name = n.dropLast(1) + '\u0001'
                patches += name.toByteArray(Charsets.UTF_8) to n.toByteArray(Charsets.UTF_8)
            }
            z.putNextEntry(ZipEntry(name))
            z.write(b)
            z.closeEntry()
        }
    }
    if (patches.isNotEmpty()) {
        val bytes = Files.readAllBytes(out)
        for ((from, to) in patches) {
            var i = 0
            while (i <= bytes.size - from.size) {
                if (from.indices.all { bytes[i + it] == from[it] }) { to.copyInto(bytes, i); i += from.size } else i++
            }
        }
        Files.write(out, bytes)
    }
}

class SamplePacks(val rp: Path, val bp: Path, val rpUuid: String, val bpUuid: String)

/** Add-on mínimo e coerente: RP (textura, geometria, entidade de cliente) + BP (entidade) dependente do RP. */
object Samples {
    fun create(root: Path, withScript: Boolean = false): SamplePacks {
        val rp = root.resolve("RP")
        val bp = root.resolve("BP")
        val rpSpec = ManifestSpec(PackKind.RESOURCES, "Ruby RP", "Recursos", minEngineVersion = listOf(1, 21, 0))
        val bpSpec = ManifestSpec(
            PackKind.BEHAVIOR, "Ruby BP", "Comportamento", minEngineVersion = listOf(1, 21, 0),
            dependencies = listOf(PackDependency.OnPack(rpSpec.headerUuid, rpSpec.version)),
            scriptEntry = if (withScript) "scripts/main.js" else null,
        )
        writeFile(rp.resolve("manifest.json"), Json.write(ManifestBuilder.build(rpSpec)))
        writeFile(bp.resolve("manifest.json"), Json.write(ManifestBuilder.build(bpSpec)))
        if (withScript) writeFile(bp.resolve("scripts/main.js"), "// script\n")

        writeFile(rp.resolve("textures/item_texture.json"), """{"resource_pack_name":"ruby","texture_name":"atlas.items","texture_data":{"ruby":{"textures":"textures/items/ruby"}}}""")
        writePng(rp.resolve("textures/items/ruby.png"))
        writePng(rp.resolve("textures/entity/ruby_golem.png"))
        writeFile(rp.resolve("models/entity/ruby_golem.geo.json"), """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.ruby_golem","texture_width":16,"texture_height":16},"bones":[{"name":"body","pivot":[0,8,0],"cubes":[{"origin":[-2,0,-2],"size":[4,8,4],"uv":[0,0]}]}]}]}""")
        writeFile(rp.resolve("entity/ruby_golem.entity.json"), """{"format_version":"1.10.0","minecraft:client_entity":{"description":{"identifier":"studio:ruby_golem","textures":{"default":"textures/entity/ruby_golem"},"geometry":{"default":"geometry.ruby_golem"}}}}""")
        writeFile(bp.resolve("entities/ruby_golem.json"), """{"format_version":"1.21.0","minecraft:entity":{"description":{"identifier":"studio:ruby_golem","is_spawnable":true,"is_summonable":true},"components":{"minecraft:health":{"value":20}}}}""")
        return SamplePacks(rp, bp, rpSpec.headerUuid, bpSpec.headerUuid)
    }

    fun writePng(path: Path) {
        Files.createDirectories(path.parent)
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0xFFFF0000.toInt())
        ImageIO.write(img, "png", path.toFile())
    }
}
