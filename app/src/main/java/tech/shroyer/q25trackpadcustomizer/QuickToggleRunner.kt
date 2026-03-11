package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.content.Intent

object QuickToggleRunner {

    fun run(context: Context) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)

        val baseMode = prefs.getSystemDefaultMode()

        val currentLogical =
            AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: baseMode

        val quickToggleModes = prefs.getGlobalQuickToggleModes()
        val singleMatchFallback = prefs.getGlobalQuickToggleSingleMatchFallbackMode()

        val nextMode = computeNextQuickToggleMode(
            baseMode = baseMode,
            currentMode = currentLogical,
            selectedModes = quickToggleModes,
            singleMatchFallback = singleMatchFallback
        )
        val intent = Intent(AppSwitchService.ACTION_APPLY_MODE).apply {
            setPackage(appContext.packageName)
            putExtra(AppSwitchService.EXTRA_TARGET_MODE, nextMode.prefValue)
            putExtra(AppSwitchService.EXTRA_SHOW_QUICK_TOGGLE_TOAST, prefs.isToastQuickToggleEnabled())
        }
        appContext.sendBroadcast(intent)
    }

    private fun computeNextQuickToggleMode(
        baseMode: Mode,
        currentMode: Mode,
        selectedModes: Set<Mode>,
        singleMatchFallback: Mode
    ): Mode {
        val validModes = linkedSetOf(Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL, Mode.SCROLL_MODE_2)
        val sanitized = selectedModes.filter { it in validModes }.toSet().ifEmpty { validModes }
        val current = if (currentMode == Mode.FOLLOW_SYSTEM) baseMode else currentMode
        val order = listOf(Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL, Mode.SCROLL_MODE_2)
        var cycle = order.filter { it in sanitized && it != baseMode }

        if (cycle.isEmpty()) {
            val fallback = if (sanitized.size == 1 && sanitized.contains(baseMode)) {
                if (singleMatchFallback != baseMode && singleMatchFallback in validModes) {
                    singleMatchFallback
                } else {
                    defaultOtherMode(baseMode)
                }
            } else {
                defaultOtherMode(baseMode)
            }
            cycle = listOf(fallback)
        }

        return when {
            current == baseMode -> cycle.first()
            current in cycle -> {
                val idx = cycle.indexOf(current)
                if (idx >= 0 && idx < cycle.lastIndex) cycle[idx + 1] else baseMode
            }
            else -> cycle.first()
        }
    }

    private fun defaultOtherMode(baseMode: Mode): Mode {
        return when (baseMode) {
            Mode.MOUSE -> Mode.KEYBOARD
            Mode.KEYBOARD -> Mode.MOUSE
            Mode.SCROLL_WHEEL -> Mode.MOUSE
            Mode.SCROLL_MODE_2 -> Mode.MOUSE
            Mode.FOLLOW_SYSTEM -> Mode.MOUSE
        }
    }
}
