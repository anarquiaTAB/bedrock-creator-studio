package studio.app

import android.app.Application
import studio.core.project.ProjectStore

class StudioApp : Application() {
    /** Projetos ficam no armazenamento privado do app; só saem dele por exportação/compartilhamento. */
    lateinit var store: ProjectStore
        private set

    override fun onCreate() {
        super.onCreate()
        val dir = java.io.File(filesDir, "projects").apply { mkdirs() }
        store = ProjectStore(dir.toPath())
    }
}
