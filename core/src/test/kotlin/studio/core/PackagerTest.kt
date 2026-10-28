package studio.core

import studio.core.diagnostics.Category
import studio.core.diagnostics.Severity
import studio.core.pack.Packager
import studio.core.project.ProjectStore
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.*

class PackagerTest {
    private fun entryNames(zip: java.nio.file.Path): List<String> =
        ZipFile(zip.toFile()).use { z -> z.entries().asSequence().map { it.name }.sorted().toList() }

    @Test fun mcaddonHasCorrectInternalStructure() = withTempDir { d ->
        val s = Samples.create(d)
        val out = d.resolve("out/test.mcaddon")
        val r = Packager.exportMcaddon(listOf("RP" to s.rp, "BP" to s.bp), out)
        assertNotNull(r.output, r.report.items.joinToString())
        assertFalse(r.report.hasErrors)
        val names = entryNames(out)
        assertTrue("RP/manifest.json" in names && "BP/manifest.json" in names)
        assertTrue("RP/textures/items/ruby.png" in names)
        assertTrue(names.none { it.contains(".studio") || it.endsWith(".studio-tmp") })
        val insp = Packager.inspect(out)
        assertEquals("mcaddon", insp.kind)
        assertEquals(2, insp.packs.size)
        assertFalse(insp.hasErrors)
        ZipFile(out.toFile()).use { z ->
            val inZip = z.getInputStream(z.getEntry("BP/manifest.json")).readBytes()
            assertContentEquals(Files.readAllBytes(s.bp.resolve("manifest.json")), inZip)
        }
    }

    @Test fun mcpackHasManifestAtRoot() = withTempDir { d ->
        val s = Samples.create(d, withScript = true)
        val out = d.resolve("bp.mcpack")
        val r = Packager.exportMcpack(s.bp, out)
        assertNotNull(r.output)
        assertTrue("manifest.json" in entryNames(out))
        assertTrue("scripts/main.js" in entryNames(out))
        assertEquals("mcpack", Packager.inspect(out).kind)
        // dependência do RP fica fora do arquivo: deve aparecer como aviso, não como erro
        assertTrue(r.report.warnings.any { it.code == "DEP_UNRESOLVED" })
    }

    @Test fun refusesWhenErrorsExistUnlessForced() = withTempDir { d ->
        val s = Samples.create(d)
        writeFile(s.bp.resolve("entities/bad.json"), "{ \"a\": ")
        val out = d.resolve("bad.mcaddon")
        val r = Packager.exportMcaddon(listOf("RP" to s.rp, "BP" to s.bp), out)
        assertNull(r.output)
        assertFalse(Files.exists(out))
        val e = r.report.errors.first { it.code == "JSON_SYNTAX" }
        assertEquals("BP/entities/bad.json", e.file)
        assertNotNull(e.line)
        assertEquals(Category.SYNTAX, e.category)
        val forced = Packager.exportMcaddon(listOf("RP" to s.rp, "BP" to s.bp), out, force = true)
        assertNotNull(forced.output)
    }

    @Test fun neverExportsWithoutManifest() = withTempDir { d ->
        val dir = d.resolve("empty")
        Files.createDirectories(dir)
        writeFile(dir.resolve("a.txt"), "x")
        val r = Packager.exportMcpack(dir, d.resolve("e.mcpack"), force = true)
        assertNull(r.output)
        assertTrue(r.report.errors.any { it.code == "MANIFEST_MISSING" })
    }

    @Test fun inspectFlagsNonPackages() = withTempDir { d ->
        val z = d.resolve("x.mcaddon")
        makeZip(z, listOf("readme.txt" to "x".toByteArray()))
        val r = Packager.inspect(z)
        assertEquals("unknown", r.kind)
        assertTrue(r.hasErrors)
        val garbage = d.resolve("g.mcpack")
        Files.write(garbage, ByteArray(40))
        assertTrue(Packager.inspect(garbage).hasErrors)
    }

    @Test fun exportedAddonImportsBackIntoAProject() = withTempDir { d ->
        val s = Samples.create(d)
        val out = d.resolve("a.mcaddon")
        assertNotNull(Packager.exportMcaddon(listOf("RP" to s.rp, "BP" to s.bp), out).output)
        val p = ProjectStore(d.resolve("projects")).importArchive(out)
        assertEquals(listOf("BP", "RP"), p.packDirs().map { it.first })
        assertFalse(p.diagnose().hasErrors)
        assertContentEquals(Files.readAllBytes(s.rp.resolve("textures/items/ruby.png")), p.readBytes("packs/RP/textures/items/ruby.png"))
    }

    @Test fun mcaddonWithNestedMcpacksImports() = withTempDir { d ->
        val s = Samples.create(d)
        assertNotNull(Packager.exportMcpack(s.rp, d.resolve("RP.mcpack")).output)
        assertNotNull(Packager.exportMcpack(s.bp, d.resolve("BP.mcpack")).output)
        val outer = d.resolve("nested.mcaddon")
        makeZip(outer, listOf("RP.mcpack" to Files.readAllBytes(d.resolve("RP.mcpack")), "BP.mcpack" to Files.readAllBytes(d.resolve("BP.mcpack"))))
        val p = ProjectStore(d.resolve("projects")).importArchive(outer)
        assertEquals(listOf("BP", "RP"), p.packDirs().map { it.first })
        assertTrue(p.exists("packs/BP/manifest.json"))
    }

    @Test fun singleMcpackImportsAsOnePack() = withTempDir { d ->
        val s = Samples.create(d)
        assertNotNull(Packager.exportMcpack(s.rp, d.resolve("only.mcpack")).output)
        val p = ProjectStore(d.resolve("projects")).importArchive(d.resolve("only.mcpack"))
        assertEquals(1, p.packDirs().size)
        assertTrue(p.exists("packs/only/manifest.json"))
        assertEquals(Severity.INFO, p.diagnose().items.last().severity)
    }
}
