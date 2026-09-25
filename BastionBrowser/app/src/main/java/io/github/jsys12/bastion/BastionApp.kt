package io.github.jsys12.bastion

import android.app.Application
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.adblock.ContentScripts
import io.github.jsys12.bastion.adblock.FilterUpdateWorker
import io.github.jsys12.bastion.data.BrowserDb
import io.github.jsys12.bastion.data.Settings
import io.github.jsys12.bastion.data.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BastionApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: Settings
        private set
    lateinit var db: BrowserDb
        private set
    lateinit var stats: Stats
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        db = BrowserDb(this)
        stats = Stats(settings.prefs)
        ContentScripts.init(this)
        AdBlocker.init(this)
        FilterUpdateWorker.schedule(this)
    }

    companion object {
        lateinit var instance: BastionApp
            private set
    }
}
