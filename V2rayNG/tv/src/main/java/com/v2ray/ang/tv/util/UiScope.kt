package com.v2ray.ang.tv.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A named, cancellable scope owning every asynchronous operation started by one Activity.
 *
 * Ownership distinction from the :app module: the TV client has no AndroidX ViewModel or
 * lifecycle component (they would raise this module's minSdk), so each Activity owns one
 * UiScope and closes it in onDestroy. Work already running is interrupted by shutdownNow;
 * queued results are dropped because the owning screen is gone.
 */
class UiScope {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val alive = AtomicBoolean(true)

    fun <T> launch(work: () -> T, onResult: (Result<T>) -> Unit) {
        if (!alive.get()) return
        executor.execute {
            val result = runCatching(work)
            main.post {
                if (alive.get()) onResult(result)
            }
        }
    }

    fun close() {
        if (!alive.compareAndSet(true, false)) return
        main.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
