package studio.core

import studio.core.archive.ArchiveException
import studio.core.archive.SafeZip
import studio.core.storage.AtomicFiles
import studio.core.storage.StorageException
import studio.core.storage.walkFiles
import java.nio.file.Files
import kotlin.test.*

class StorageTest {
    @Test fun atomicWriteCreatesFileWithoutLeftovers() = withTempDir { d ->
        val f = d.resolve("a/b/c.txt")
        AtomicFiles.writeText(f, "olá")
        assertEquals("olá", String(Files.readAllBytes(f), Charsets.UTF_8))
        assertTrue(walkFiles(d).none { it.fileName.toString().endsWith(AtomicFiles.TMP_SUFFIX) })
    }

    @Test fun failureMidWriteKeepsOriginal() = withTempDir { d ->
        val f = d.resolve("x.txt")
        AtomicFiles.writeText(f, "old")
        assertFailsWith<IllegalStateException> {
            AtomicFiles.writeWith(f) { it.write("partial".toByteArray()); throw IllegalStateException("boom") }
        }
        assertEquals("old", String(Files.readAllBytes(f)))
        assertTrue(walkFiles(d).none { it.fileName.toString().endsWith(AtomicFiles.TMP_SUFFIX) })
    }

    @Test fun storageErrorBecomesStorageException() = withTempDir { d ->
        val blocker = d.resolve("file")
        Files.write(blocker, byteArrayOf(1))
        assertFailsWith<StorageException> { AtomicFiles.writeText(blocker.resolve("child.txt"), "x") }
    }

    @Test fun cleanupStaleRemovesInterruptedTemps() = withTempDir { d ->
        writeFile(d.resolve("sub/a.json.studio-tmp"), "half")
        writeFile(d.resolve("sub/a.json"), "{}")
        assertEquals(1, AtomicFiles.cleanupStale(d))
        assertTrue(Files.exists(d.resolve("sub/a.json")))
        assertFalse(Files.exists(d.resolve("sub/a.json.studio-tmp")))
    }

    @Test fun zipRejectsTraversalAbsoluteAndDuplicates() = withTempDir { d ->
        val bad = listOf("../evil.txt", "/abs.txt", "..\\evil.txt", "a/../../evil.txt", "C:/win.txt")
        for ((i, name) in bad.withIndex()) {
            val z = d.resolve("bad$i.zip")
            makeZip(z, listOf(name to byteArrayOf(1)))
            val out = d.resolve("out$i")
            assertFailsWith<ArchiveException>("deveria recusar $name") { SafeZip.extract(z, out) }
            assertFalse(Files.exists(d.resolve("evil.txt")))
            assertFalse(Files.exists(d.resolve("abs.txt")))
        }
        val dup = d.resolve("dup.zip")
        makeZip(dup, listOf("a.txt" to byteArrayOf(1), "a.txt" to byteArrayOf(2)))
        assertFailsWith<ArchiveException> { SafeZip.extract(dup, d.resolve("outdup")) }
    }

    @Test fun zipEnforcesSizeLimitsAndRejectsCorruptFiles() = withTempDir { d ->
        val z = d.resolve("big.zip")
        makeZip(z, listOf("a.bin" to ByteArray(100)))
        assertFailsWith<ArchiveException> { SafeZip.extract(z, d.resolve("o1"), SafeZip.Limits(maxEntryBytes = 10)) }
        assertFailsWith<ArchiveException> { SafeZip.extract(z, d.resolve("o2"), SafeZip.Limits(maxEntries = 0)) }
        val garbage = d.resolve("garbage.zip")
        Files.write(garbage, ByteArray(64) { it.toByte() })
        assertFailsWith<ArchiveException> { SafeZip.extract(garbage, d.resolve("o3")) }
    }

    @Test fun zipRoundTrip() = withTempDir { d ->
        val src = d.resolve("src")
        writeFile(src.resolve("a.txt"), "A")
        Files.createDirectories(src.resolve("sub"))
        Files.write(src.resolve("sub/b.bin"), byteArrayOf(0, 1, 2, -1))
        writeFile(src.resolve(".studio/skip.txt"), "no")
        val z = d.resolve("o.zip")
        SafeZip.create(src, z) { !it.startsWith(".studio/") }
        val dest = d.resolve("dest")
        val names = SafeZip.extract(z, dest)
        assertEquals(listOf("a.txt", "sub/b.bin"), names.sorted())
        assertContentEquals(byteArrayOf(0, 1, 2, -1), Files.readAllBytes(dest.resolve("sub/b.bin")))
        assertFalse(Files.exists(dest.resolve(".studio")))
    }
}
