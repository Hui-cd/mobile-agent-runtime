package ai.mobileagent

import android.app.Application
import ai.mobileagent.benchmark.BenchmarkRunStore

class MobileAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BenchmarkRunStore.reconcileInterrupted(this)
    }
}
