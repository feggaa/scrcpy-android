package tech.devline.scropy_ui

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    override fun onCreate() {
        app = this
        installCrashHandler()
        writeDiag("App.onCreate: started, SDK=${android.os.Build.VERSION.SDK_INT}")
        super.onCreate()
        writeDiag("App.onCreate: finished")
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            writeDiag("CRASH on thread ${thread.name}:\n$sw")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private var app: App? = null

        fun writeDiag(msg: String) {
            android.util.Log.i("ScropyApp", msg)
            try {
                val dir = app?.filesDir ?: return
                File(dir, "diag.log").appendText("[${System.currentTimeMillis()}] $msg\n")
            } catch (_: Exception) {}
        }
    }
}
