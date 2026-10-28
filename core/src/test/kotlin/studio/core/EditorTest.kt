package studio.core

import studio.core.image.*
import kotlin.test.*

class EditorTest {
    private val RED = 0xFFFF0000.toInt()
    private val GREEN = 0xFF00FF00.toInt()
    private val BLUE = 0xFF0000FF.toInt()

    private fun editor(w: Int = 8, h: Int = 8): Editor {
        val d = Document(w, h)
        d.addLayer("Base")
        return Editor(d)
    }

    private fun Editor.px(x: Int, y: Int) = activeLayer.bitmap[x, y]
    private fun Editor.painted() = activeLayer.bitmap.pixels.count { it != 0 }

    @Test fun strokeIsOneUndoableStep() {
        val e = editor()
        val s = e.beginStroke(RED)
        s.moveTo(1, 1)
        s.lineTo(4, 1)
        s.end()
        assertEquals(4, e.painted())
        assertTrue(e.undo())
        assertEquals(0, e.painted())
        assertFalse(e.canUndo)
        assertTrue(e.redo())
        assertEquals(RED, e.px(4, 1))
        assertFalse(e.redo())
    }

    @Test fun eraserMakesPixelsTransparent() {
        val e = editor()
        e.rect(0, 0, 3, 3, RED, true)
        val s = e.beginStroke(0, erase = true)
        s.moveTo(1, 1)
        s.end()
        assertEquals(0, e.px(1, 1))
        assertEquals(15, e.painted())
    }

    @Test fun shapesDrawExpectedPixels() {
        val e = editor(10, 10)
        e.rect(1, 1, 3, 2, RED, true)
        assertEquals(6, e.painted())
        val e2 = editor(10, 10)
        e2.rect(0, 0, 3, 3, RED, false)
        assertEquals(12, e2.painted())
        val e3 = editor(10, 10)
        e3.line(0, 0, 9, 0, BLUE)
        assertEquals(10, e3.painted())
        val e4 = editor(10, 10)
        e4.ellipse(0, 0, 6, 6, GREEN, true)
        assertEquals(GREEN, e4.px(3, 3))
        assertEquals(0, e4.px(0, 0))
        for (y in 0..6) for (x in 0..6) assertEquals(e4.px(x, y), e4.px(6 - x, y), "simetria em ($x,$y)")
    }

    @Test fun brushSizeAndRoundStamp() {
        val e = editor(10, 10)
        val s = e.beginStroke(RED, size = 3)
        s.moveTo(5, 5)
        s.end()
        assertEquals(9, e.painted())
        val e2 = editor(10, 10)
        val s2 = e2.beginStroke(RED, size = 5, round = true)
        s2.moveTo(5, 5)
        s2.end()
        assertTrue(e2.painted() in 15..21)
        assertEquals(0, e2.px(3, 3))
    }

    @Test fun symmetryMirrorsAndUndoesInOneStep() {
        val e = editor()
        e.symmetryHorizontal = true
        e.symmetryVertical = true
        e.rect(1, 2, 1, 2, RED, true)
        assertEquals(RED, e.px(1, 2))
        assertEquals(RED, e.px(6, 2))
        assertEquals(RED, e.px(1, 5))
        assertEquals(RED, e.px(6, 5))
        assertEquals(4, e.painted())
        e.undo()
        assertEquals(0, e.painted())
    }

    @Test fun floodFillRespectsBarriersToleranceAndContiguity() {
        val e = editor()
        e.line(4, 0, 4, 7, BLUE)
        assertEquals(32, e.floodFill(0, 0, GREEN))
        assertEquals(GREEN, e.px(3, 7))
        assertEquals(0, e.px(5, 0))

        val t = editor(4, 1)
        t.activeLayer.bitmap[0, 0] = 0xFF000000.toInt()
        t.activeLayer.bitmap[1, 0] = 0xFF010101.toInt()
        t.activeLayer.bitmap[2, 0] = 0xFFFFFFFF.toInt()
        t.activeLayer.bitmap[3, 0] = 0xFF000000.toInt()
        assertEquals(2, t.floodFill(0, 0, RED, tolerance = 1))
        assertEquals(1, t.floodFill(3, 0, GREEN, tolerance = 0, contiguous = false))
    }

    @Test fun selectionLimitsPainting() {
        val e = editor()
        e.selectRect(0, 0, 3, 3)
        e.rect(0, 0, 7, 7, RED, true)
        assertEquals(16, e.painted())
        assertEquals(0, e.floodFill(6, 6, GREEN))
        e.clearSelection()
        e.rect(0, 0, 7, 7, RED, true)
        assertEquals(64, e.painted())
    }

    @Test fun copyPasteMoveCutDuplicate() {
        val e = editor()
        e.rect(1, 1, 2, 2, RED, true)
        e.selectRect(1, 1, 2, 2)
        val clip = e.copy()!!
        e.paste(clip, 5, 5)
        assertEquals(RED, e.px(6, 6))
        assertTrue(e.selection!!.contains(5, 5))
        e.undo()
        assertEquals(4, e.painted())

        e.selectRect(1, 1, 2, 2)
        e.moveSelection(2, 0)
        assertEquals(0, e.px(1, 1))
        assertEquals(RED, e.px(3, 1))
        assertEquals(RED, e.px(4, 2))
        e.undo()
        assertEquals(RED, e.px(1, 1))

        e.selectRect(1, 1, 2, 2)
        e.duplicateSelection(0, 4)
        assertEquals(8, e.painted())

        e.selectRect(1, 1, 2, 2)
        val cut = e.cut()!!
        assertEquals(4, cut.mask.count { it })
        assertEquals(4, e.painted())
    }

    @Test fun layersCompositeMergeAndUndo() {
        val e = editor(2, 2)
        e.rect(0, 0, 0, 0, RED, true)
        val top = e.addLayer("Topo")
        assertEquals(top.id, e.activeLayerId)
        e.rect(0, 0, 0, 0, GREEN, true)
        e.setLayerOpacity(top.id, 128)
        val flat = e.doc.flatten()[0, 0]
        assertEquals(255, Colors.alpha(flat))
        assertTrue(Colors.red(flat) in 125..129 && Colors.green(flat) in 126..130, Colors.toHex(flat))
        e.setLayerVisible(top.id, false)
        assertEquals(RED, e.doc.flatten()[0, 0])
        e.setLayerVisible(top.id, true)

        val before = e.doc.flatten()
        e.mergeDown(top.id)
        assertEquals(1, e.doc.layers.size)
        assertEquals(before, e.doc.flatten())
        e.undo()
        assertEquals(2, e.doc.layers.size)
        val single = editor()
        assertFailsWith<IllegalArgumentException> { single.removeLayer(single.activeLayerId) }
    }

    @Test fun addAndRemoveLayerUndo() {
        val e = editor()
        val l = e.addLayer("X")
        assertEquals(2, e.doc.layers.size)
        e.removeLayer(l.id)
        assertEquals(1, e.doc.layers.size)
        e.undo()
        assertEquals(2, e.doc.layers.size)
        e.undo()
        assertEquals(1, e.doc.layers.size)
        val dup = e.duplicateLayer(e.activeLayerId)
        assertEquals(2, e.doc.layers.size)
        assertNotEquals(dup.id, e.doc.layers.first().id)
    }

    @Test fun transformationsAndTheirUndo() {
        val e = editor(4, 2)
        e.activeLayer.bitmap[0, 0] = RED
        e.rotateDocument90(true)
        assertEquals(2, e.doc.width)
        assertEquals(4, e.doc.height)
        assertEquals(RED, e.px(1, 0))
        e.undo()
        assertEquals(4, e.doc.width)
        assertEquals(RED, e.px(0, 0))
        e.flipActiveLayer(true)
        assertEquals(RED, e.px(3, 0))
        e.undo()
        e.resizeCanvas(6, 6, 1, 1)
        assertEquals(RED, e.px(1, 1))
        e.undo()
        e.scale(8, 4)
        assertEquals(RED, e.px(1, 1))
        assertEquals(4, e.painted())
        e.undo()
        assertEquals(4, e.doc.width)
    }

    @Test fun historyIsCapped() {
        val e = Editor(Document(4, 4).also { it.addLayer("L") }, maxHistory = 3)
        for (i in 0 until 5) e.rect(i % 4, 0, i % 4, 0, if (i % 2 == 0) RED else BLUE, true)
        var undone = 0
        while (e.undo()) undone++
        assertEquals(3, undone)
    }

    @Test fun pickAndMagicWand() {
        val e = editor()
        e.rect(0, 0, 1, 1, RED, true)
        assertEquals(RED, e.pick(1, 1))
        assertNull(e.pick(99, 99))
        e.magicWand(0, 0)
        assertEquals(4, e.selection!!.count())
    }

    @Test fun skinMaskKeepsPaintInsideOverlayAreas() {
        val d = Document(64, 64)
        d.addLayer("Skin")
        val e = Editor(d)
        e.selectMask(studio.core.skin.SkinLayout.mask(slim = false, overlay = true))
        e.rect(0, 0, 63, 63, RED, true)
        assertEquals(1632, e.painted())
        assertEquals(0, e.px(8, 8)) // frente da cabeça (base) permanece intacta
        assertEquals(RED, e.px(40, 8)) // frente do chapéu
    }
}
