package studio.core

import studio.core.json.*
import studio.core.manifest.*
import kotlin.test.*

class ManifestTest {
    @Test fun buildsAndReadsBack() {
        val rp = Uuids.random()
        val spec = ManifestSpec(
            PackKind.BEHAVIOR, "Meu BP", "desc", minEngineVersion = listOf(1, 21, 0),
            dependencies = listOf(PackDependency.OnPack(rp, listOf(1, 0, 0)), PackDependency.OnModule("@minecraft/server", "1.0.0")),
            scriptEntry = "scripts/main.js",
        )
        val obj = Json.parseObject(Json.write(ManifestBuilder.build(spec)))
        assertEquals(2, obj.int("format_version"))
        assertEquals("Meu BP", obj.obj("header")!!.str("name"))
        val parsed = ManifestReader.parse(obj)
        assertEquals(spec.headerUuid, parsed.headerUuid)
        assertEquals(listOf("data", "script"), parsed.moduleTypes)
        assertEquals(2, parsed.dependencies.size)
        assertEquals(listOf(1, 0, 0), parsed.version)
        assertEquals(parsed.moduleUuids.size, parsed.moduleUuids.toSet().size)
        assertFalse(parsed.moduleUuids.contains(parsed.headerUuid))
    }

    @Test fun uuidsAreValidDistinctAndDeterministicWhenNamed() {
        val a = Uuids.random()
        val b = Uuids.random()
        assertTrue(Uuids.isValid(a))
        assertNotEquals(a, b)
        assertEquals(Uuids.nameBased("x"), Uuids.nameBased("x"))
        assertFalse(Uuids.isValid("não-é-uuid"))
    }

    @Test fun invalidSpecsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ManifestSpec(PackKind.RESOURCES, "x", version = listOf(1, 0)) }
        assertFailsWith<IllegalArgumentException> { ManifestSpec(PackKind.RESOURCES, "x", scriptEntry = "a.js") }
        assertFailsWith<IllegalArgumentException> { ManifestSpec(PackKind.RESOURCES, " ") }
        assertFailsWith<IllegalArgumentException> { ManifestSpec(PackKind.RESOURCES, "x", headerUuid = "abc") }
        val same = Uuids.random()
        assertFailsWith<IllegalArgumentException> { ManifestSpec(PackKind.RESOURCES, "x", headerUuid = same, moduleUuid = same) }
    }
}
