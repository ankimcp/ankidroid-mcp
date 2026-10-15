package ai.ankimcp.ankidroid

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AnkiMcpApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Keystore decrypt + prefs read stay off the main thread; the UI waits on `loaded`.
        graph.appScope.launch(Dispatchers.IO) { graph.credentialStore.load() }
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as AnkiMcpApp).graph
