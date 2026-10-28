package studio.core

import studio.core.json.*
import studio.core.project.ProjectStore
import studio.core.storage.StorageException
import java.nio.file.Files
import kotlin.test.*

class ProjectStoreTest {
    @Test fun createSaveReopen() = withTempDir { base ->
        val p = ProjectStore(base).create("Meu Add-on", "1.21.0")
        p.writeText("packs/BP/manifest.json", "{}")
        val reopened = ProjectStore(base).open(p.meta.id).project
        assertEquals("Meu Add-on", reopened.meta.name)
        assertEquals("1.21.0", reopened.meta.targetMinecraft)
        assertEquals("{}", reopened.readText("packs/BP/manifest.json"))
        assertEquals(listOf(p.meta.id), ProjectStore(base).list().map { it.id })
    }

    @Test fun unknownFilesAndFieldsArePreserved() = withTempDir { base ->
        val p = ProjectStore(base).create("X")
        p.writeText("packs/BP/manifest.json", """{"format_version":1,"x_unknown":{"a":[1,2]}}""")
        val blob = byteArrayOf(9, 8, 7, 0, -1)
        p.writeBytes("packs/BP/weird.xyz", blob)
        p.updateJson("packs/BP/manifest.json") { it.with("format_version", JsonNum("2")) }
        val after = Json.parseObject(p.readText("packs/BP/manifest.json"))
        assertEquals("2", (after["format_version"] as JsonNum).raw)
        assertNotNull(after.obj("x_unknown"))
        assertContentEquals(blob, p.readBytes("packs/BP/weird.xyz"))
        assertTrue(p.listFiles().contains("packs/BP/weird.xyz"))
    }

    @Test fun updateJsonOnInvalidFileChangesNothing() = withTempDir { base ->
        val p = ProjectStore(base).create("X")
        p.writeText("a.json", "{ broken")
        assertFailsWith<StorageException> { p.updateJson("a.json") { it } }
        assertEquals("{ broken", p.readText("a.json"))
    }

    @Test fun draftsSurviveRestartAndTempFilesAreCleaned() = withTempDir { base ->
        val p = ProjectStore(base).create("X")
        val rel = "packs/BP/entities/a.json"
        p.saveDraft(rel, "{\"draft\":true}")
        writeFile(p.root.resolve("packs/BP/.a.json.1.studio-tmp"), "meio")
        // "queda" do app: um novo ProjectStore reabre o projeto
        val r = ProjectStore(base).open(p.meta.id)
        assertEquals(listOf(rel), r.recovery.pendingDrafts)
        assertEquals(1, r.recovery.removedTempFiles)
        assertFalse(r.project.exists(rel))
        r.project.commitDraft(rel)
        assertEquals("{\"draft\":true}", r.project.readText(rel))
        assertTrue(r.project.pendingDrafts().isEmpty())
    }

    @Test fun protectsAgainstAccidentalOverwriteAndBadPaths() = withTempDir { base ->
        val p = ProjectStore(base).create("X")
        p.createFile("a.txt", "1".toByteArray())
        assertFailsWith<StorageException> { p.createFile("a.txt", "2".toByteArray()) }
        assertEquals("1", p.readText("a.txt"))
        for (bad in listOf("../x.txt", "/abs.txt", "project.json", ".studio/x", "a/../../x", "")) {
            assertFailsWith<StorageException>("deveria recusar '$bad'") { p.writeText(bad, "x") }
        }
    }

    @Test fun moveDeleteAndDuplicate() = withTempDir { base ->
        val store = ProjectStore(base)
        val p = store.create("Orig")
        p.writeText("a/one.txt", "1")
        p.move("a/one.txt", "b/two.txt")
        assertFalse(p.exists("a/one.txt"))
        val copy = store.duplicate(p.meta.id, "Cópia")
        assertNotEquals(p.meta.id, copy.meta.id)
        assertEquals("Cópia", copy.meta.name)
        assertEquals("1", copy.readText("b/two.txt"))
        p.delete("b")
        assertEquals("1", copy.readText("b/two.txt"))
        store.delete(p.meta.id)
        assertEquals(listOf(copy.meta.id), store.list().map { it.id })
    }

    @Test fun backupRoundTrip() = withTempDir { d ->
        val store = ProjectStore(d.resolve("projects"))
        val p = store.create("Backup")
        p.writeText("packs/RP/data.bin.txt", "dados")
        p.saveDraft("packs/RP/x.json", "rascunho")
        val zip = d.resolve("backup.zip")
        store.exportBackup(p.meta.id, zip)
        val restored = store.importArchive(zip)
        assertNotEquals(p.meta.id, restored.meta.id)
        assertEquals("Backup", restored.meta.name)
        assertEquals(p.listFiles(), restored.listFiles())
        assertTrue(restored.pendingDrafts().isEmpty())
    }

    @Test fun storageErrorsAreReportedNotCrashed() = withTempDir { d ->
        val file = d.resolve("not-a-dir")
        Files.write(file, byteArrayOf(1))
        assertFailsWith<StorageException> { ProjectStore(file).create("X") }
        assertFailsWith<StorageException> { ProjectStore(d).open("não existe") }
        assertFailsWith<StorageException> { ProjectStore(d).open("../etc") }
    }

    @Test fun invalidArchiveIsRejectedAndLeavesNoTrace() = withTempDir { d ->
        val base = d.resolve("projects")
        val zip = d.resolve("nothing.zip")
        makeZip(zip, listOf("readme.txt" to "oi".toByteArray()))
        assertFailsWith<studio.core.archive.ArchiveException> { ProjectStore(base).importArchive(zip) }
        assertTrue(ProjectStore(base).list().isEmpty())
        assertTrue(Files.list(base).use { it.count() } == 0L)
    }
}
