package tech.shroyer.q25trackpadcustomizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class QuickToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        QuickToggleRunner.run(context)
    }

    companion object {
        const val ACTION_QUICK_TOGGLE = "tech.shroyer.q25trackpadcustomizer.action.QUICK_TOGGLE"
    }
}
