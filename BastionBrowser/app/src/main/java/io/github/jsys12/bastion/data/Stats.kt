package io.github.jsys12.bastion.data

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

data class StatsSnapshot(
    val requests: Long = 0,
    val popups: Long = 0,
    val appRedirects: Long = 0,
    val params: Long = 0,
    val elements: Long = 0,
    val navigations: Long = 0,
) {
    val total get() = requests + popups + appRedirects + navigations

    /** Rough estimate: an average blocked ad/tracker resource weighs ~35 KB. */
    val savedBytes get() = requests * 35_000L

    /** Rough estimate: ~40 ms of loading per blocked resource, 5 s per blocked popup. */
    val savedMillis get() = requests * 40L + (popups + appRedirects) * 5_000L
}

/** Lifetime protection counters, updated from any thread and persisted lazily. */
class Stats(private val prefs: SharedPreferences) {
    private val requests = AtomicLong(prefs.getLong("stat_requests", 0))
    private val popups = AtomicLong(prefs.getLong("stat_popups", 0))
    private val appRedirects = AtomicLong(prefs.getLong("stat_app_redirects", 0))
    private val params = AtomicLong(prefs.getLong("stat_params", 0))
    private val elements = AtomicLong(prefs.getLong("stat_elements", 0))
    private val navigations = AtomicLong(prefs.getLong("stat_navigations", 0))

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<StatsSnapshot> get() = _state

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var scheduled = false

    private fun snapshot() = StatsSnapshot(
        requests.get(), popups.get(), appRedirects.get(), params.get(), elements.get(), navigations.get()
    )

    fun blockedRequest() { requests.incrementAndGet(); schedule() }
    fun blockedPopup() { popups.incrementAndGet(); schedule() }
    fun blockedAppRedirect() { appRedirects.incrementAndGet(); schedule() }
    fun cleanedParams() { params.incrementAndGet(); schedule() }
    fun hiddenElement() { elements.incrementAndGet(); schedule() }
    fun blockedNavigation() { navigations.incrementAndGet(); schedule() }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            val s = snapshot()
            _state.value = s
            prefs.edit()
                .putLong("stat_requests", s.requests).putLong("stat_popups", s.popups)
                .putLong("stat_app_redirects", s.appRedirects).putLong("stat_params", s.params)
                .putLong("stat_elements", s.elements).putLong("stat_navigations", s.navigations)
                .apply()
        }, 1000)
    }

    fun reset() {
        listOf(requests, popups, appRedirects, params, elements, navigations).forEach { it.set(0) }
        schedule()
    }
}
