package studio.core.util

/** Erro de edição com mensagem pronta para o usuário; a operação que falhou não altera o estado. */
class EditException(message: String) : IllegalArgumentException(message)

/**
 * Desfazer/refazer por instantâneos de um estado imutável. Como os modelos de geometria e animação são
 * dados imutáveis, guardar o estado anterior é barato (os objetos não alterados são compartilhados).
 */
class History<T>(initial: T, private val maxEntries: Int = 200) {
    private class Entry<T>(val label: String, val state: T)

    var state: T = initial
        private set
    private val undoStack = ArrayDeque<Entry<T>>()
    private val redoStack = ArrayDeque<Entry<T>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /** Aplica [change]; se ela lançar [EditException] (ou qualquer exceção) o estado fica como estava. */
    fun edit(label: String, change: (T) -> T): T {
        val next = change(state)
        if (next == state) return state
        undoStack.addLast(Entry(label, state))
        while (undoStack.size > maxEntries) undoStack.removeFirst()
        redoStack.clear()
        state = next
        return state
    }

    fun undo(): Boolean {
        val e = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(Entry(e.label, state))
        state = e.state
        return true
    }

    fun redo(): Boolean {
        val e = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(Entry(e.label, state))
        state = e.state
        return true
    }

    /** Descarta o histórico (ex.: depois de salvar e fechar). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

object Num {
    /** Número JSON limpo: sem ".0" desnecessário e arredondado a 1e-6. */
    fun fmt(d: Double): String {
        require(d.isFinite()) { "Número inválido" }
        val r = Math.round(d * 1_000_000.0) / 1_000_000.0
        if (r == Math.rint(r) && Math.abs(r) < 1e15) return r.toLong().toString()
        return java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString()
    }

    /** Tempo de quadro-chave como o Bedrock costuma gravar: sempre com parte decimal ("0.0", "0.25"). */
    fun fmtTime(t: Double): String {
        require(t.isFinite()) { "Tempo inválido" }
        val r = Math.round(t * 10_000.0) / 10_000.0
        val s = java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString()
        return if (s.contains('.')) s else "$s.0"
    }
}
