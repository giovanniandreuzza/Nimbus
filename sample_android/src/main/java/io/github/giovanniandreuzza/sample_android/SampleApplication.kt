package io.github.giovanniandreuzza.sample_android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.sample_android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import timber.log.Timber

/**
 * Sample Application.
 *
 * @author Giovanni Andreuzza
 */
class SampleApplication : Application() {

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())

        Timber.i("Application started")

        startKoin {
            androidLogger()
            androidContext(this@SampleApplication)
            modules(appModule)
        }

        registerActivityLifecycleCallbacks(FlushWhenBackgrounded(lifecycleScope))
    }
}

/**
 * Commits whatever Nimbus was holding when the app stops being visible.
 *
 * Terminal states are already on disk when their call returns; an enqueue, a pause or a
 * progress position waits for the next coalesced commit, because a commit rewrites every task.
 * On Android the process can be killed without notice from that point on, and this is the last
 * moment anything is guaranteed to run.
 *
 * `close()` is the stronger version — it also stops the transfers — and belongs where the app
 * is genuinely done, not where it is merely backgrounded: a download should keep running.
 */
private class FlushWhenBackgrounded(
    private val scope: CoroutineScope
) : Application.ActivityLifecycleCallbacks, KoinComponent {

    private val nimbus: NimbusAPI by inject()

    override fun onActivityStopped(activity: Activity) {
        scope.launch {
            nimbus.flush().onFailure { Timber.w("Nimbus flush failed: %s", it.causeCode) }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}