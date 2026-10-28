package studio.core.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Todos os arquivos regulares sob [root], em ordem alfabética estável. */
fun walkFiles(root: Path): List<Path> {
    if (!Files.isDirectory(root)) return emptyList()
    return Files.walk(root).use { stream ->
        stream.iterator().asSequence().filter { Files.isRegularFile(it) }.toList()
    }.sortedBy { it.toString() }
}

/** Caminho relativo com '/' como separador, independente do sistema. */
fun relativeName(root: Path, file: Path): String =
    root.relativize(file).toString().replace(File.separatorChar, '/')
