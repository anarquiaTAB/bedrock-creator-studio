package studio.core

import studio.core.diagnostics.*
import studio.core.json.*
import studio.core.manifest.*
import java.nio.file.Files
import kotlin.test.*

class DiagnosticsTest {
    private fun check(s: SamplePacks) = ProjectChecker.check(listOf("RP" to s.rp, "BP" to s.bp))
    private fun codes(r: DiagnosticReport) = r.items.map { it.code }

    @Test fun cleanSampleHasNoErrorsOrWarningsButAlwaysAsksForGameTest() = withTempDir { d ->
        val r = check(Samples.create(d, withScript = false))
        assertTrue(r.errors.isEmpty(), r.errors.joinToString())
        assertTrue(r.warnings.isEmpty(), r.warnings.joinToString())
        assertTrue(r.items.any { it.category == Category.NEEDS_GAME_TEST })
    }

    @Test fun missingManifest() = withTempDir { d ->
        Files.createDirectories(d.resolve("p"))
        val r = PackChecker(d.resolve("p"), "p").check()
        assertEquals("MANIFEST_MISSING", r.diagnostics.single().code)
    }

    @Test fun missingTextureAndGeometryAreWarnings() = withTempDir { d ->
        val s = Samples.create(d)
        Files.delete(s.rp.resolve("textures/items/ruby.png"))
        Files.delete(s.rp.resolve("models/entity/ruby_golem.geo.json"))
        val r = check(s)
        assertTrue("REF_TEXTURE_MISSING" in codes(r))
        assertTrue("REF_GEOMETRY_MISSING" in codes(r))
        assertTrue(r.errors.isEmpty())
    }

    @Test fun duplicateAndInvalidIdentifiers() = withTempDir { d ->
        val s = Samples.create(d)
        Files.copy(s.bp.resolve("entities/ruby_golem.json"), s.bp.resolve("entities/copy.json"))
        writeFile(s.bp.resolve("entities/noprefix.json"), """{"minecraft:entity":{"description":{"identifier":"golem"}}}""")
        val r = check(s)
        assertTrue("IDENT_DUPLICATE" in codes(r))
        assertTrue("IDENT_INVALID" in codes(r))
    }

    @Test fun duplicateUuidAcrossPacks() = withTempDir { d ->
        val s = Samples.create(d)
        val bpM = Json.parseObject(String(Files.readAllBytes(s.bp.resolve("manifest.json"))))
        val header = bpM.obj("header")!!.with("uuid", JsonStr(s.rpUuid))
        writeFile(s.bp.resolve("manifest.json"), Json.write(bpM.with("header", header)))
        assertTrue("UUID_DUPLICATE_ACROSS_PACKS" in codes(check(s)))
    }

    @Test fun unresolvedDependencyIsWarning() = withTempDir { d ->
        val s = Samples.create(d)
        val r = ProjectChecker.check(listOf("BP" to s.bp))
        assertTrue(r.warnings.any { it.code == "DEP_UNRESOLVED" })
        assertTrue(r.errors.isEmpty())
    }

    @Test fun scriptEntryMissingAndBetaModule() = withTempDir { d ->
        val s = Samples.create(d, withScript = true)
        Files.delete(s.bp.resolve("scripts/main.js"))
        val m = Json.parseObject(String(Files.readAllBytes(s.bp.resolve("manifest.json"))))
        val deps = JsonArr(
            m.arr("dependencies")!!.items +
                jsonObj("module_name" to JsonStr("@minecraft/server"), "version" to JsonStr("1.0.0-beta")),
        )
        writeFile(s.bp.resolve("manifest.json"), Json.write(m.with("dependencies", deps)))
        val r = check(s)
        assertTrue("SCRIPT_ENTRY_MISSING" in codes(r))
        val beta = r.warnings.first { it.code == "DEPENDENCY_BETA" }
        assertEquals(Category.COMPATIBILITY, beta.category)
    }

    @Test fun malformedManifestFieldsAreErrors() = withTempDir { d ->
        val dir = d.resolve("p")
        writeFile(dir.resolve("manifest.json"), """{"format_version":2,"header":{"name":"","uuid":"x","version":[1,0]},"modules":[]}""")
        val c = PackChecker(dir, "p").check().diagnostics.map { it.code }
        assertTrue("MANIFEST_NAME" in c && "UUID_INVALID" in c && "MANIFEST_VERSION" in c && "MANIFEST_MODULES" in c)
    }

    @Test fun skinPackTextureMustExist() = withTempDir { d ->
        val dir = d.resolve("sp")
        val spec = ManifestSpec(PackKind.SKIN, "Skins")
        writeFile(dir.resolve("manifest.json"), Json.write(ManifestBuilder.build(spec)))
        writeFile(dir.resolve("skins.json"), """{"skins":[{"localization_name":"a","geometry":"geometry.humanoid.custom","texture":"a.png","type":"free"}]}""")
        assertTrue(PackChecker(dir, "sp").check().diagnostics.any { it.code == "SKIN_TEXTURE_MISSING" })
        Samples.writePng(dir.resolve("a.png"))
        assertTrue(PackChecker(dir, "sp").check().diagnostics.none { it.severity == Severity.ERROR })
    }
}
