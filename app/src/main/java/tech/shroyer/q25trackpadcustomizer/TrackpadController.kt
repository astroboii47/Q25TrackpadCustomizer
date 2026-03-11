package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.content.Intent
import java.io.IOException

// Handle reading and writing /proc/q20_switch_key_mouse using root
object TrackpadController {

    private const val PROC_PATH = "/proc/q20_switch_key_mouse"
    private const val POINTER_SPEED_SETTING = "pointer_speed"
    private const val HELPER_BINARY_MATCH = "trackpad_helper"

    /**
     * Toggle between 0 and 1 and return the new Mode if successful.
     * 0 = Mouse, 1 = Keyboard (per your mapping).
     * For SCROLL_WHEEL, it still uses the keyboard hardware mode (1) and relies on
     * Accessibility logic to transform arrow keys into scroll actions.
     */
    fun toggleValue(): Mode? {
        val current = readValue()

        // If current is 0 (Mouse), Switch to Keyboard; if 1 (Keyboard), Switch to Mouse.
        val newMode = when (current) {
            "0" -> Mode.KEYBOARD
            "1" -> Mode.MOUSE
            else -> Mode.MOUSE // fallback if it can't be read for any reason
        }

        val procValue = newMode.procValue ?: return null
        val success = writeValue(procValue)
        if (!success) {
            return null
        }

        AppState.currentMode = newMode
        return newMode
    }

    /**
     * Set the mode explicitly.
     * FOLLOW_SYSTEM is resolved before calling this.
     *
     * KEYBOARD and SCROLL_WHEEL use hardware mode 1.
     * SCROLL_MODE_2 uses hardware mode 0 so raw relative motion remains available.
     */
    fun setModeValue(mode: Mode, context: Context? = null): Boolean {
        val previousMode = AppState.currentMode
        val procValue = when (mode) {
            Mode.MOUSE -> "0"
            Mode.KEYBOARD, Mode.SCROLL_WHEEL -> "1"
            Mode.SCROLL_MODE_2 -> "0"
            Mode.FOLLOW_SYSTEM -> return false
        }

        if (previousMode == mode && (mode != Mode.SCROLL_MODE_2 || AppState.scrollMode2HelperRunning)) {
            return true
        }

        val currentProcValue = readValue()
        val success = if (currentProcValue == procValue) {
            true
        } else {
            writeValue(procValue)
        }
        if (success) {
            AppState.currentMode = mode
            if (context != null) {
                requestHelperRefresh(
                    context = context,
                    mode = mode,
                    forceStopScrollMode2 = false
                )
            }
        }
        return success
    }

    /**
     * Read the current value ("0" or "1") from /proc using su
     */
    fun readValue(): String? {
        val (ok, output) = execSu("cat $PROC_PATH")
        if (!ok || output == null) return null
        val trimmed = output.trim()
        return if (trimmed == "0" || trimmed == "1") trimmed else null
    }

    fun applyCursorSensitivity(sensitivity: CursorSensitivity): Boolean {
        val (ok, _) = execSu("settings put system $POINTER_SPEED_SETTING ${sensitivity.pointerSpeed}")
        return ok
    }

    /**
     * Write a value ("0" or "1") to /proc using su
     */
    private fun writeValue(value: String): Boolean {
        val (ok, _) = execSu("echo $value > $PROC_PATH")
        return ok
    }

    private fun requestHelperRefresh(context: Context, mode: Mode, forceStopScrollMode2: Boolean) {
        val intent = Intent(AppSwitchService.ACTION_REFRESH_HELPER_STATE)
            .setPackage(context.packageName)
            .putExtra(AppSwitchService.EXTRA_TARGET_MODE, mode.prefValue)
            .putExtra(AppSwitchService.EXTRA_FORCE_STOP_SCROLL_MODE_2, forceStopScrollMode2)
        context.sendBroadcast(intent)
    }

    /**
     * Run a single su -c command and return (success, stdout)
     */
    private fun execSu(cmd: String): Pair<Boolean, String?> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Pair(exitCode == 0, output)
        } catch (e: IOException) {
            e.printStackTrace()
            Pair(false, null)
        } catch (e: InterruptedException) {
            e.printStackTrace()
            Pair(false, null)
        }
    }
}
