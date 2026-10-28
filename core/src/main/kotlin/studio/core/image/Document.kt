package studio.core.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

class Layer(val id: Int, var name: String, var bitmap: Bitmap, var visible: Boolean = true, var opacity: Int = 255) {
    fun copy() = Layer(id, name, bitmap.copy(), visible, opacity)
}

/** Cópia profunda do estado de um documento (usada pelo histórico e pelo salvamento). */
class DocState(val width: Int, val height: Int, val layers: List<Layer>, val nextId: Int)

class Document(width: Int, height: Int) {
    var width: Int = width
        private set
    var height: Int = height
        private set
    private val _layers = mutableListOf<Layer>()
    private var nextId = 1

    /** Da camada mais ao fundo para a mais acima. */
    val layers: List<Layer> get() = _layers

    init {
        Bitmap.checkedSize(width, height)
    }

    fun addLayer(name: String, index: Int = _layers.size, bitmap: Bitmap? = null): Layer {
        require(bitmap == null || (bitmap.width == width && bitmap.height == height)) { "A camada deve ter o tamanho do documento" }
        val layer = Layer(nextId++, name, bitmap ?: Bitmap(width, height))
        _layers.add(index.coerceIn(0, _layers.size), layer)
        return layer
    }

    fun layerById(id: Int): Layer? = _layers.firstOrNull { it.id == id }
    fun indexOf(id: Int): Int = _layers.indexOfFirst { it.id == id }

    fun removeLayer(id: Int) {
        val i = indexOf(id)
        require(i >= 0) { "Camada inexistente" }
        _layers.removeAt(i)
    }

    fun moveLayer(from: Int, to: Int) {
        require(from in _layers.indices && to in _layers.indices) { "Índice de camada inválido" }
        _layers.add(to, _layers.removeAt(from))
    }

    /** Composição de todas as camadas visíveis, respeitando a opacidade de cada uma. */
    fun flatten(): Bitmap {
        val out = Bitmap(width, height)
        for (layer in _layers) {
            if (!layer.visible || layer.opacity <= 0) continue
            val src = layer.bitmap.pixels
            for (i in src.indices) {
                val c = src[i]
                if (Colors.alpha(c) == 0) continue
                out.pixels[i] = Colors.over(Colors.withOpacity(c, layer.opacity), out.pixels[i])
            }
        }
        return out
    }

    fun snapshot(): DocState = DocState(width, height, _layers.map { it.copy() }, nextId)

    fun restore(s: DocState) {
        width = s.width
        height = s.height
        nextId = s.nextId
        _layers.clear()
        _layers.addAll(s.layers.map { it.copy() })
    }

    /** Substitui as dimensões e todas as camadas (usado por transformações). */
    internal fun replaceAll(newWidth: Int, newHeight: Int, newLayers: List<Layer>) {
        Bitmap.checkedSize(newWidth, newHeight)
        width = newWidth
        height = newHeight
        _layers.clear()
        _layers.addAll(newLayers)
    }

    companion object {
        fun fromBitmap(bmp: Bitmap, layerName: String = "Camada 1"): Document {
            val d = Document(bmp.width, bmp.height)
            d.addLayer(layerName, bitmap = bmp.copy())
            return d
        }
    }
}

class ImageFormatException(message: String) : Exception(message)

/** Formato próprio (.bcsimg) para guardar as camadas editáveis; o PNG exportado é sempre achatado. */
object DocumentIO {
    private const val MAGIC = 0x42435349 // "BCSI"
    private const val VERSION = 1
    private const val MAX_LAYERS = 1024

    fun write(doc: Document): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(doc.width)
            out.writeInt(doc.height)
            out.writeInt(doc.layers.size)
            for (l in doc.layers) {
                out.writeInt(l.id)
                out.writeUTF(l.name)
                out.writeBoolean(l.visible)
                out.writeInt(l.opacity)
                val buf = ByteBuffer.allocate(l.bitmap.pixels.size * 4)
                buf.asIntBuffer().put(l.bitmap.pixels)
                out.write(buf.array())
            }
        }
        return bytes.toByteArray()
    }

    fun read(data: ByteArray): Document {
        try {
            DataInputStream(InflaterInputStream(ByteArrayInputStream(data))).use { inp ->
                if (inp.readInt() != MAGIC) throw ImageFormatException("Não é um arquivo de camadas do estúdio")
                val version = inp.readInt()
                if (version != VERSION) throw ImageFormatException("Versão de arquivo não suportada: $version")
                val w = inp.readInt()
                val h = inp.readInt()
                if (w < 1 || h < 1 || w.toLong() * h > Bitmap.MAX_PIXELS) throw ImageFormatException("Dimensões inválidas")
                val count = inp.readInt()
                if (count < 0 || count > MAX_LAYERS) throw ImageFormatException("Quantidade de camadas inválida")
                val layers = mutableListOf<Layer>()
                var maxId = 0
                for (i in 0 until count) {
                    val id = inp.readInt()
                    val name = inp.readUTF()
                    val visible = inp.readBoolean()
                    val opacity = inp.readInt().coerceIn(0, 255)
                    val buf = ByteArray(w * h * 4)
                    inp.readFully(buf)
                    val px = IntArray(w * h)
                    ByteBuffer.wrap(buf).asIntBuffer().get(px)
                    layers.add(Layer(id, name, Bitmap(w, h, px), visible, opacity))
                    if (id > maxId) maxId = id
                }
                val doc = Document(w, h)
                doc.restore(DocState(w, h, layers, maxId + 1))
                return doc
            }
        } catch (e: ZipException) {
            throw ImageFormatException("Arquivo de camadas corrompido")
        } catch (e: IOException) {
            throw ImageFormatException("Arquivo de camadas incompleto ou corrompido")
        }
    }
}
