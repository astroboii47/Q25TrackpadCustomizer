package tech.shroyer.q25trackpadcustomizer

import android.app.Activity
import android.os.Bundle

/**
 * Quick toggle entry point for shortcuts/keymappers. No UI is shown.
 */
class QuickToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        QuickToggleRunner.run(this)

        finish()
        overridePendingTransition(0, 0)
    }
}
