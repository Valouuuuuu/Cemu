package info.cemu.cemu.common.android.context

import java.util.concurrent.CountDownLatch

/**
 * Another app can call a content provider as soon as this process starts, while the application is
 * still being created. Cemu then loads its settings from disk, over anything set before that.
 */
object AppInitialization {
    private val finished = CountDownLatch(1)

    fun markFinished() = finished.countDown()

    fun await() = finished.await()
}
