package io.github.giovanniandreuzza.sample_android

import android.app.Application
import io.github.giovanniandreuzza.sample_android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber

/**
 * Sample Application.
 *
 * @author Giovanni Andreuzza
 */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())

        Timber.i("Application started")

        startKoin {
            androidLogger()
            androidContext(this@SampleApplication)
            modules(appModule)
        }
    }

}