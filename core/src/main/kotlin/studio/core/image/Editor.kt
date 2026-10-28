package studio.core.image

/**
 * Motor do editor de texturas/skins: ferramentas de pintura, seleção, camadas, transformações,
 * simetria e histórico (desfazer/refazer). Não depende de interface; a UI só chama estas funções.
 *
 * Observações: o histórico guarda só os pixels alterados em cada pintura, mas guarda cópias completas
 * do documento em operações estruturais (camadas, redimensionar). Alterações de seleção não entram no histórico.
 * Pintar com cor semitransparente substitui o pixel (não mistura); colar e mover usam composição "over".
 */
class Editor(val doc: Document, private val maxHistory: Int = 100) {
    var activeLayerId: Int = doc.layers.lastOrNull()?.id ?: doc.addLayer("Camada 1").id
    var selection: Selection? = null
    var symmetryHorizontal = false
    var symmetryVertical = false

    val activeLayer: Layer
        get() = doc.layerById(activeLayerId) ?: throw IllegalStateException("Nenhuma camada ativa")

    // ---------------- Histórico ----------------

    private sealed class Entry
    private class PixelEntry(val layerId: Int, val idx: IntArray, val old: IntArray, val new: IntArray) : Entry()
    private class StructEntry(val before: DocState, val after: DocState, val activeBefore: Int, val activeAfter: Int) : Entry()

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun push(e: Entry) {
        undoStack.addLast(e)
        redoStack.clear()
        while (undoStack.size > maxHistory) undoStack.removeFirst()
    }

    fun undo(): Boolean {
        val e = undoStack.removeLastOrNull() ?: return false
        when (e) {
            is PixelEntry -> doc.layerById(e.layerId)?.let { l -> for (k in e.idx.indices) l.bitmap.pixels[e.idx[k]] = e.old[k] }
            is StructEntry -> {
                doc.restore(e.before)
                activeLayerId = e.activeBefore
                selection = null
            }
        }
        redoStack.addLast(e)
        return true
    }

    fun redo(): Boolean {
        val e = redoStack.removeLastOrNull() ?: return false
        when (e) {
            is PixelEntry -> doc.layerById(e.layerId)?.let { l -> for (k in e.idx.indices) l.bitmap.pixels[e.idx[k]] = e.new[k] }
            is StructEntry -> {
                doc.restore(e.after)
                activeLayerId = e.activeAfter
                selection = null
            }
        }
        undoStack.addLast(e)
        return true
    }

    // ---------------- Escrita de pixels ----------------

    private inner class Writer(val layer: Layer, val sel: Selection?, val symH: Boolean, val symV: Boolean) {
        val old = HashMap<Int, Int>()

        fun put(x: Int, y: Int, color: Int, blend: Boolean = false) {
            val w = layer.bitmap.width
            val h = layer.bitmap.height
            put1(x, y, color, blend)
            if (symH) put1(w - 1 - x, y, color, blend)
            if (symV) put1(x, h - 1 - y, color, blend)
            if (symH && symV) put1(w - 1 - x, h - 1 - y, color, blend)
        }

        private fun put1(x: Int, y: Int, color: Int, blend: Boolean) {
            val bmp = layer.bitmap
            if (!bmp.inBounds(x, y)) return
            if (sel != null && !sel.contains(x, y)) return
            val i = y * bmp.width + x
            if (!old.containsKey(i)) old[i] = bmp.pixels[i]
            bmp.pixels[i] = if (blend) Colors.over(color, bmp.pixels[i]) else color
        }

        fun commit() {
            val ids = old.keys.filter { layer.bitmap.pixels[it] != old[it]!! }.sorted()
            if (ids.isEmpty()) return
            val idx = ids.toIntArray()
            val oldV = IntArray(idx.size) { old[idx[it]]!! }
            val newV = IntArray(idx.size) { layer.bitmap.pixels[idx[it]] }
            push(PixelEntry(layer.id, idx, oldV, newV))
        }
    }

    private fun edit(block: (Writer) -> Unit) {
        val w = Writer(activeLayer, selection, symmetryHorizontal, symmetryVertical)
        block(w)
        w.commit()
    }

    /** Escrita ignorando seleção e simetria (colar, mover, apagar seleção). */
    private fun editRaw(block: (Writer) -> Unit) {
        val w = Writer(activeLayer, null, false, false)
        block(w)
        w.commit()
    }

    // ---------------- Ferramentas de desenho ----------------

    /** Traço livre: um único passo no histórico, confirmado em [Stroke.end]. */
    inner class Stroke internal constructor(
        private val color: Int,
        private val size: Int,
        private val round: Boolean,
        private val erase: Boolean,
    ) {
        private val writer = Writer(activeLayer, selection, symmetryHorizontal, symmetryVertical)
        private var lastX = 0
        private var lastY = 0
        private var started = false
        private var done = false

        private fun stampAt(x: Int, y: Int) {
            Shapes.stamp(size, round, x, y) { sx, sy -> writer.put(sx, sy, if (erase) 0 else color) }
        }

        fun moveTo(x: Int, y: Int) {
            check(!done) { "Traço já finalizado" }
            stampAt(x, y)
            lastX = x
            lastY = y
            started = true
        }

        fun lineTo(x: Int, y: Int) {
            check(!done) { "Traço já finalizado" }
            if (!started) {
                moveTo(x, y)
                return
            }
            Shapes.line(lastX, lastY, x, y) { px, py -> stampAt(px, py) }
            lastX = x
            lastY = y
        }

        fun end() {
            if (done) return
            done = true
            writer.commit()
        }
    }

    fun beginStroke(color: Int, size: Int = 1, round: Boolean = false, erase: Boolean = false) =
        Stroke(color, size, round, erase)

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, size: Int = 1, round: Boolean = false) = edit { w ->
        Shapes.line(x0, y0, x1, y1) { px, py -> Shapes.stamp(size, round, px, py) { sx, sy -> w.put(sx, sy, color) } }
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, filled: Boolean) = edit { w ->
        Shapes.rect(x0, y0, x1, y1, filled) { x, y -> w.put(x, y, color) }
    }

    fun ellipse(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, filled: Boolean) = edit { w ->
        Shapes.ellipse(x0, y0, x1, y1, filled) { x, y -> w.put(x, y, color) }
    }

    /** Cor da camada ativa (ou da composição) em (x, y); null fora da imagem. */
    fun pick(x: Int, y: Int, fromComposite: Boolean = false): Int? {
        val bmp = if (fromComposite) doc.flatten() else activeLayer.bitmap
        return if (bmp.inBounds(x, y)) bmp[x, y] else null
    }

    private fun region(x: Int, y: Int, tolerance: Int, contiguous: Boolean): BooleanArray? {
        val bmp = activeLayer.bitmap
        if (!bmp.inBounds(x, y)) return null
        val sel = selection
        if (sel != null && !sel.contains(x, y)) return null
        val w = bmp.width
        val target = bmp[x, y]
        val out = BooleanArray(bmp.pixels.size)
        fun ok(px: Int, py: Int) =
            (sel == null || sel.contains(px, py)) && Colors.distance(bmp[px, py], target) <= tolerance
        if (contiguous) {
            val stack = ArrayDeque<Int>()
            out[y * w + x] = true
            stack.addLast(y * w + x)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val cx = i % w
                val cy = i / w
                for ((nx, ny) in listOf(cx - 1 to cy, cx + 1 to cy, cx to cy - 1, cx to cy + 1)) {
                    if (!bmp.inBounds(nx, ny)) continue
                    val ni = ny * w + nx
                    if (out[ni] || !ok(nx, ny)) continue
                    out[ni] = true
                    stack.addLast(ni)
                }
            }
        } else {
            for (py in 0 until bmp.height) for (px in 0 until w) if (ok(px, py)) out[py * w + px] = true
        }
        return out
    }

    /** Preenche a área de cor semelhante. Retorna quantos pixels casaram. */
    fun floodFill(x: Int, y: Int, color: Int, tolerance: Int = 0, contiguous: Boolean = true): Int {
        val r = region(x, y, tolerance, contiguous) ?: return 0
        val w = activeLayer.bitmap.width
        var n = 0
        edit { wr ->
            for (i in r.indices) if (r[i]) {
                wr.put(i % w, i / w, color)
                n++
            }
        }
        return n
    }

    // ---------------- Seleção ----------------

    fun selectRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        selection = Selection.rect(doc.width, doc.height, x0, y0, x1, y1)
    }

    fun selectEllipse(x0: Int, y0: Int, x1: Int, y1: Int) {
        selection = Selection.ellipse(doc.width, doc.height, x0, y0, x1, y1)
    }

    fun selectAll() {
        selection = Selection.all(doc.width, doc.height)
    }

    fun clearSelection() {
        selection = null
    }

    fun invertSelection() {
        selection = (selection ?: Selection.all(doc.width, doc.height)).inverted()
    }

    fun selectMask(mask: Selection) {
        require(mask.width == doc.width && mask.height == doc.height) { "A máscara deve ter o tamanho do documento" }
        selection = mask
    }

    /** Varinha mágica: seleciona pixels de cor semelhante. */
    fun magicWand(x: Int, y: Int, tolerance: Int = 0, contiguous: Boolean = true) {
        val r = region(x, y, tolerance, contiguous) ?: return
        selection = Selection(doc.width, doc.height, r)
    }

    fun copy(): Clip? {
        val sel = selection ?: return null
        val b = sel.bounds() ?: return null
        val bmp = activeLayer.bitmap
        val w = b[2] - b[0] + 1
        val h = b[3] - b[1] + 1
        val px = IntArray(w * h)
        val mask = BooleanArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            val x = b[0] + i
            val y = b[1] + j
            if (sel.contains(x, y)) {
                mask[j * w + i] = true
                px[j * w + i] = bmp[x, y]
            }
        }
        return Clip(w, h, px, mask)
    }

    fun deleteSelection() {
        val sel = selection ?: return
        editRaw { w ->
            for (y in 0 until doc.height) for (x in 0 until doc.width) if (sel.contains(x, y)) w.put(x, y, 0)
        }
    }

    fun cut(): Clip? {
        val c = copy() ?: return null
        deleteSelection()
        return c
    }

    /** Cola [clip] com o canto superior esquerdo em (x, y) e seleciona a área colada. */
    fun paste(clip: Clip, x: Int, y: Int) {
        editRaw { w -> drawClip(w, clip, x, y) }
        selection = clipSelection(clip, x, y)
    }

    private fun drawClip(w: Writer, clip: Clip, x: Int, y: Int) {
        for (j in 0 until clip.height) for (i in 0 until clip.width) {
            if (clip.mask[j * clip.width + i]) w.put(x + i, y + j, clip.pixels[j * clip.width + i], blend = true)
        }
    }

    private fun clipSelection(clip: Clip, x: Int, y: Int): Selection {
        val m = BooleanArray(doc.width * doc.height)
        for (j in 0 until clip.height) for (i in 0 until clip.width) {
            val px = x + i
            val py = y + j
            if (clip.mask[j * clip.width + i] && px in 0 until doc.width && py in 0 until doc.height) m[py * doc.width + px] = true
        }
        return Selection(doc.width, doc.height, m)
    }

    /** Move o conteúdo selecionado (um passo no histórico). */
    fun moveSelection(dx: Int, dy: Int) {
        val sel = selection ?: return
        val clip = copy() ?: return
        val b = sel.bounds() ?: return
        editRaw { w ->
            for (y in 0 until doc.height) for (x in 0 until doc.width) if (sel.contains(x, y)) w.put(x, y, 0)
            drawClip(w, clip, b[0] + dx, b[1] + dy)
        }
        selection = sel.translated(dx, dy)
    }

    fun duplicateSelection(dx: Int, dy: Int) {
        val sel = selection ?: return
        val clip = copy() ?: return
        val b = sel.bounds() ?: return
        paste(clip, b[0] + dx, b[1] + dy)
    }

    // ---------------- Camadas e transformações (estruturais) ----------------

    private fun structural(resetSelection: Boolean, change: () -> Unit) {
        val before = doc.snapshot()
        val activeBefore = activeLayerId
        change()
        if (resetSelection) selection = null
        push(StructEntry(before, doc.snapshot(), activeBefore, activeLayerId))
    }

    fun addLayer(name: String): Layer {
        lateinit var created: Layer
        structural(false) {
            created = doc.addLayer(name, doc.indexOf(activeLayerId) + 1)
            activeLayerId = created.id
        }
        return created
    }

    fun duplicateLayer(id: Int): Layer {
        val src = doc.layerById(id) ?: throw IllegalArgumentException("Camada inexistente")
        lateinit var created: Layer
        structural(false) {
            created = doc.addLayer(src.name + " cópia", doc.indexOf(id) + 1, src.bitmap.copy())
            created.visible = src.visible
            created.opacity = src.opacity
            activeLayerId = created.id
        }
        return created
    }

    fun removeLayer(id: Int) {
        require(doc.layers.size > 1) { "O documento precisa ter ao menos uma camada" }
        require(doc.layerById(id) != null) { "Camada inexistente" }
        structural(false) {
            val i = doc.indexOf(id)
            doc.removeLayer(id)
            if (activeLayerId == id) activeLayerId = doc.layers[minOf(i, doc.layers.size - 1)].id
        }
    }

    fun moveLayer(from: Int, to: Int) = structural(false) { doc.moveLayer(from, to) }

    fun mergeDown(id: Int) {
        val i = doc.indexOf(id)
        require(i > 0) { "Não há camada abaixo para mesclar" }
        structural(false) {
            val upper = doc.layers[i]
            val lower = doc.layers[i - 1]
            if (upper.visible) {
                for (k in upper.bitmap.pixels.indices) {
                    val c = upper.bitmap.pixels[k]
                    if (Colors.alpha(c) != 0) lower.bitmap.pixels[k] = Colors.over(Colors.withOpacity(c, upper.opacity), lower.bitmap.pixels[k])
                }
            }
            doc.removeLayer(id)
            activeLayerId = lower.id
        }
    }

    private fun layerOrFail(id: Int) = doc.layerById(id) ?: throw IllegalArgumentException("Camada inexistente")

    fun setLayerVisible(id: Int, visible: Boolean) {
        val l = layerOrFail(id)
        structural(false) { l.visible = visible }
    }

    fun setLayerOpacity(id: Int, opacity: Int) {
        val l = layerOrFail(id)
        structural(false) { l.opacity = opacity.coerceIn(0, 255) }
    }

    fun renameLayer(id: Int, name: String) {
        val l = layerOrFail(id)
        structural(false) { l.name = name }
    }

    fun flipActiveLayer(horizontal: Boolean) = structural(false) {
        val l = activeLayer
        l.bitmap = l.bitmap.flipped(horizontal)
    }

    private fun mapLayers(f: (Bitmap) -> Bitmap): List<Layer> =
        doc.layers.map { Layer(it.id, it.name, f(it.bitmap), it.visible, it.opacity) }

    fun rotateDocument90(clockwise: Boolean) = structural(true) {
        doc.replaceAll(doc.height, doc.width, mapLayers { it.rotated90(clockwise) })
    }

    /** Muda o tamanho da tela; o conteúdo antigo fica em ([offsetX], [offsetY]) da nova área. */
    fun resizeCanvas(newW: Int, newH: Int, offsetX: Int = 0, offsetY: Int = 0) {
        Bitmap.checkedSize(newW, newH)
        structural(true) { doc.replaceAll(newW, newH, mapLayers { it.crop(-offsetX, -offsetY, newW, newH) }) }
    }

    /** Escala todas as camadas sem suavização. */
    fun scale(newW: Int, newH: Int) {
        Bitmap.checkedSize(newW, newH)
        structural(true) { doc.replaceAll(newW, newH, mapLayers { it.scaledNearest(newW, newH) }) }
    }
}
