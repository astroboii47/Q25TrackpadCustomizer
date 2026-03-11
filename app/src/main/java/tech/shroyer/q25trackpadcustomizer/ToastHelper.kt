package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

// Use app-context toasts directly to avoid the overhead of launching a headless activity.
object ToastHelper {

    fun show(context: Context, text: String) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
        }
    }
}
