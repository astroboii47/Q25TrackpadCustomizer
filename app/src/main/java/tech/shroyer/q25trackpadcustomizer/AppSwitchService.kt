package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tracks foreground app changes and key events, applying trackpad mode and scroll behavior.
 * Supports temporary Keyboard override while editing text, plus hold-key temporary switching.
 *
 * Updated:
 *  - Secondary Hold support (separate mode/key/allow-in-text/double-press).
 *  - Optional "require double press + hold" for Primary Hold and Secondary Hold.
 *  - Optional "Secondary Hold is triggered by Primary Hold double-press + hold".
 */
class AppSwitchService : AccessibilityService() {

    private lateinit var prefs: Prefs

    private var lastAppliedPackage: String? = null

    private var imePackages: Set<String> = emptySet()

    private var lastTextInteractionUptime: Long = 0L

    private val contentCheckHandler = Handler(Looper.getMainLooper())
    private var contentCheckScheduled = false
    private val contentCheckRunnable = Runnable {
        contentCheckScheduled = false
        handleContentChangedCheck()
    }

    private val textOverrideHandler = Handler(Looper.getMainLooper())
    private var textOverrideMisses = 0
    private val textOverrideCheckRunnable = object : Runnable {
        override fun run() {
            if (!AppState.textInputOverrideActive) {
                textOverrideMisses = 0
                return
            }

            val fg = AppState.currentForegroundPackage
            if (!isAutoKeyboardEnabledForPackage(fg)) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
                return
            }

            val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < 1200L
            val stillEditing = hasFocusedTextInput() || recent

            if (stillEditing) {
                textOverrideMisses = 0
            } else {
                textOverrideMisses++
                if (textOverrideMisses >= 2) {
                    restoreModeAfterTextInput()
                    stopTextOverrideMonitor()
                    return
                }
            }

            textOverrideHandler.postDelayed(this, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
        }
    }

    // ---------- HOLD RUNTIME STATE ----------

    private val holdHandler = Handler(Looper.getMainLooper())

    private data class HoldRuntimeState(
        val id: Int,
        var active: Boolean = false,
        var keyDown: Int? = null,
        var targetMode: Mode? = null,
        var waitingSecondPress: Boolean = false,
        var lastUpUptime: Long = 0L,
        var pendingSingleHoldRunnable: Runnable? = null
    )

    private val hold1 = HoldRuntimeState(id = 1)
    private val hold2 = HoldRuntimeState(id = 2)

    private var lastActivatedHoldSlot: Int = 0

    private var pendingVerticalScrollSteps = 0
    private var pendingHorizontalScrollSteps = 0
    private var scrollGestureInFlight = false
    private var lastScrollHandledUptime = 0L
    private var pendingDirectScrollX = 0f
    private var pendingDirectScrollY = 0f

    private var trackpadEventPath: String? = null
    private var scrollMode2Process: Process? = null
    private var scrollMode2ReaderThread: Thread? = null
    private var scrollMode2ConfigSignature: String? = null
    private var scrollMode2ControlFile: java.io.File? = null
    private var tapMonitorProcess: Process? = null
    private var tapMonitorReaderThread: Thread? = null
    private val scrollMode2ControlHandler = Handler(Looper.getMainLooper())
    private var pendingScrollMode2Deactivate: Runnable? = null
    private var tapGestureStartUptime = 0L
    private var tapLastMotionUptime = 0L
    private var tapMotionDistance = 0
    private var tapMotionSamples = 0
    private var tapMaxDelta = 0
    private var tapPhysicalClickSeen = false

    private val tapMonitorHandler = Handler(Looper.getMainLooper())
    private val tapFinalizeRunnable = Runnable {
        finalizeTapGesture()
    }
    private val helperSyncHandler = Handler(Looper.getMainLooper())
    private val helperRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REFRESH_HELPER_STATE -> {
                    val targetMode = intent.getIntExtra(EXTRA_TARGET_MODE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                        ?.let { Mode.fromPrefValue(it) }
                    if (targetMode != null) {
                        AppState.currentMode = targetMode
                    }
                    syncScrollMode2Helper()
                    syncTapMonitor()
                    syncModeStatusNotification()
                }
                ACTION_APPLY_MODE -> {
                    val targetMode = intent.getIntExtra(EXTRA_TARGET_MODE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                        ?.let { Mode.fromPrefValue(it) } ?: return
                    val showToast = intent.getBooleanExtra(EXTRA_SHOW_QUICK_TOGGLE_TOAST, false)
                    applyModeTransition(targetMode, showToast)
                }
                else -> return
            }
        }
    }
    private val helperSyncRunnable = object : Runnable {
        override fun run() {
            if (AppState.currentMode == Mode.SCROLL_MODE_2 || AppState.scrollMode2HelperRunning) {
                syncScrollMode2Helper()
            }
            if (shouldUseHeuristicTapClick() || tapMonitorProcess != null) {
                syncTapMonitor()
            }
            helperSyncHandler.postDelayed(this, HELPER_SYNC_INTERVAL_MS)
        }
    }

    private val keyInjectExec = Executors.newSingleThreadExecutor()

    private fun isLegacyOverridePipelineEnabled(): Boolean = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                helperRefreshReceiver,
                IntentFilter().apply {
                    addAction(ACTION_REFRESH_HELPER_STATE)
                    addAction(ACTION_APPLY_MODE)
                },
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(helperRefreshReceiver, IntentFilter().apply {
                addAction(ACTION_REFRESH_HELPER_STATE)
                addAction(ACTION_APPLY_MODE)
            })
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        imePackages = loadImePackages()
        if (AppState.currentMode == null) {
            AppState.currentMode = prefs.getLastKnownMode() ?: prefs.getSystemDefaultMode()
        }
        clearLegacyOverrideState()
        stopTapMonitor()
        stopScrollMode2Helper(forceRootKill = true)
        syncScrollMode2Helper()
        syncTapMonitor()
        syncModeStatusNotification()
        helperSyncHandler.removeCallbacks(helperSyncRunnable)
        helperSyncHandler.post(helperSyncRunnable)
    }

    override fun onDestroy() {
        clearLegacyOverrideState()
        stopTextOverrideMonitor()
        contentCheckHandler.removeCallbacks(contentCheckRunnable)
        contentCheckHandler.removeCallbacks(processPendingScrollRunnable)
        tapMonitorHandler.removeCallbacks(tapFinalizeRunnable)
        helperSyncHandler.removeCallbacks(helperSyncRunnable)
        pendingScrollMode2Deactivate?.let { scrollMode2ControlHandler.removeCallbacks(it) }
        pendingScrollMode2Deactivate = null
        stopTapMonitor()
        stopScrollMode2Helper()
        unregisterReceiver(helperRefreshReceiver)
        cancelHoldPendingRunnables()
        keyInjectExec.shutdownNow()
        NotificationHelper.clearModeStatus(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (!isLegacyOverridePipelineEnabled()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString() ?: return
                if (isImePackage(pkg)) return
                handleForegroundAppChanged(pkg)
                syncScrollMode2Helper()
                syncTapMonitor()
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return

                if (isImePackage(pkg)) {
                    lastTextInteractionUptime = SystemClock.uptimeMillis()
                    val fg = AppState.currentForegroundPackage
                    if (isAutoKeyboardEnabledForPackage(fg)) {
                        enterTextInputOverrideIfNeeded()
                    }
                    return
                }

                handleForegroundAppChanged(pkg)
                syncScrollMode2Helper()
                scheduleContentCheck()
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleViewFocusedForAutoKeyboard(event)
                scheduleContentCheck()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleTextInteraction(event)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleContentCheck()
            }
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val fg = AppState.currentForegroundPackage

        if (isTrackpadModeSwitchPress(event)) {
            if (event.action == KeyEvent.ACTION_UP) {
                QuickToggleRunner.run(this)
            }
            return true
        }

        if (isLegacyOverridePipelineEnabled() && handleHoldKeyEvents(event, fg)) {
            return false
        }

        if (isLegacyOverridePipelineEnabled() &&
            event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            handleEndOfTextInputCheck()
            return false
        }

        if (shouldRemapMetaDpadToPlain(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                injectPlainDpad(event.keyCode)
            }
            return true
        }

        if (AppState.currentMode != Mode.SCROLL_WHEEL) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleScrollKey(event)
                }
                true
            }
            else -> false
        }
    }

    private fun isTrackpadModeSwitchPress(event: KeyEvent): Boolean {
        return prefs.isTrackpadPressModeSwitchEnabled() &&
            event.keyCode == KeyEvent.KEYCODE_ENTER &&
            event.scanCode == TRACKPAD_MODE_SWITCH_SCAN_CODE &&
            event.repeatCount == 0 &&
            !event.isAltPressed &&
            !event.isCtrlPressed &&
            !event.isShiftPressed &&
            !event.isMetaPressed
    }

    private fun shouldRemapMetaDpadToPlain(event: KeyEvent): Boolean {
        if (!isAnyHoldActive()) return false
        if (AppState.currentMode != Mode.KEYBOARD) return false

        val isDpad = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
        if (!isDpad) return false

        return event.isShiftPressed || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed
    }

    private fun injectPlainDpad(keyCode: Int) {
        keyInjectExec.execute {
            execSu("$SYSTEM_INPUT_BIN keyevent $keyCode")
        }
    }

    private fun execSu(cmd: String): Boolean {
        val (ok, _) = execSuForOutput(cmd)
        return ok
    }

    private fun execSuForOutput(cmd: String): Pair<Boolean, String?> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val stdout = try {
                p.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                null
            }
            try {
                p.errorStream.bufferedReader().readText()
            } catch (_: Exception) {
            }
            Pair(p.waitFor() == 0, stdout)
        } catch (_: IOException) {
            Pair(false, null)
        } catch (_: InterruptedException) {
            Pair(false, null)
        }
    }

    private fun stopTapMonitor() {
        resetTapGesture()
        tapMonitorProcess?.destroy()
        tapMonitorProcess?.destroyForcibly()
        tapMonitorProcess = null
        tapMonitorReaderThread?.interrupt()
        tapMonitorReaderThread = null
    }

    private fun syncTapMonitor() {
        if (shouldUseHeuristicTapClick()) {
            startTapMonitorIfNeeded()
        } else {
            stopTapMonitor()
        }
    }

    private fun startTapMonitorIfNeeded() {
        if (tapMonitorProcess != null) return

        val helperFile = ensureScrollMode2HelperInstalled() ?: return
        trackpadEventPath = trackpadEventPath ?: findTrackpadEventPath() ?: DEFAULT_TRACKPAD_EVENT_PATH
        val eventPath = trackpadEventPath ?: DEFAULT_TRACKPAD_EVENT_PATH
        val modeSwitchEnabled = prefs.isTrackpadPressModeSwitchEnabled()

        try {
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "su",
                    "-c",
                    buildString {
                        append("exec ")
                        append(helperFile.absolutePath)
                        append(' ')
                        append(eventPath)
                        append(" --grab --emit-rel")
                        if (modeSwitchEnabled) {
                            append(" --mode-switch")
                        }
                    }
                )
            )
            tapMonitorProcess = process
            val thread = Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!shouldUseHeuristicTapClick()) return@forEach

                            when {
                                line.startsWith("REL ") -> {
                                    val parts = line.split(' ')
                                    if (parts.size >= 3) {
                                        val dx = parts[1].toIntOrNull() ?: 0
                                        val dy = parts[2].toIntOrNull() ?: 0
                                        registerTapMotion(dx, dy)
                                    }
                                }
                                line.startsWith("KEY ") -> {
                                    val parts = line.split(' ')
                                    if (parts.size >= 3) {
                                        val keyCode = parts[1].toIntOrNull() ?: 0
                                        val value = parts[2].toIntOrNull() ?: 0
                                        if ((keyCode == 272 || keyCode == 273) && value != 0) {
                                            tapPhysicalClickSeen = true
                                        }
                                    }
                                }
                                line == "SWITCH" -> {
                                    Handler(Looper.getMainLooper()).post {
                                        QuickToggleRunner.run(this)
                                    }
                                }
                                line.contains("BTN_MOUSE") || line.contains("BTN_RIGHT") -> {
                                    if (parseGeteventValue(line) != 0) {
                                        tapPhysicalClickSeen = true
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (tapMonitorReaderThread === Thread.currentThread()) {
                        tapMonitorReaderThread = null
                    }
                    if (tapMonitorProcess === process) {
                        tapMonitorProcess = null
                    }
                }
            }, "q25-tap-monitor").apply {
                isDaemon = true
                start()
            }
            tapMonitorReaderThread = thread
        } catch (_: Exception) {
            stopTapMonitor()
        }
    }

    private fun parseGeteventValue(line: String): Int {
        val raw = line.substringAfterLast(' ').trim()
        return runCatching {
            when {
                raw.startsWith("0x", ignoreCase = true) -> raw.removePrefix("0x").removePrefix("0X").toLong(16).toInt()
                raw.matches(Regex("^[0-9a-fA-F]{8}$")) -> raw.toLong(16).toInt()
                else -> raw.toInt()
            }
        }.getOrDefault(0)
    }

    private fun clearLegacyOverrideState() {
        stopTextOverrideMonitor()
        contentCheckScheduled = false
        contentCheckHandler.removeCallbacks(contentCheckRunnable)
        textOverrideMisses = 0
        AppState.textInputOverrideActive = false
        AppState.modeBeforeTextInput = null
        AppState.manualOverrideForPackage = null
        clearAllHoldState()
    }

    private fun syncScrollMode2Helper() {
        val shouldRun = AppState.currentMode == Mode.SCROLL_MODE_2

        trackpadEventPath = trackpadEventPath ?: findTrackpadEventPath() ?: DEFAULT_TRACKPAD_EVENT_PATH
        if (shouldRun) {
            startScrollMode2HelperIfNeeded()
            setScrollMode2Active(true)
        } else {
            setScrollMode2Active(false)
        }
    }

    private fun startScrollMode2HelperIfNeeded() {
        val helperFile = ensureScrollMode2HelperInstalled() ?: return
        val eventPath = trackpadEventPath ?: findTrackpadEventPath() ?: DEFAULT_TRACKPAD_EVENT_PATH
        val dm = resources.displayMetrics
        val settings = prefs.getGlobalScrollSettings()
        val mode2Sensitivity = prefs.getGlobalScrollMode2Sensitivity()
        val horizontalEnabled = false
        val modeSwitchEnabled = prefs.isTrackpadPressModeSwitchEnabled()
        val scale = when (mode2Sensitivity) {
            ScrollSensitivity.ULTRA_SLOW -> 2.4f
            ScrollSensitivity.VERY_SLOW -> 3.4f
            ScrollSensitivity.SLOW -> 4.5f
            ScrollSensitivity.MEDIUM -> 6.0f
            ScrollSensitivity.FAST -> 8.0f
        }
        val configSignature = listOf(
            eventPath,
            dm.widthPixels.toString(),
            dm.heightPixels.toString(),
            mode2Sensitivity.name,
            horizontalEnabled.toString(),
            modeSwitchEnabled.toString(),
            settings.invertVertical.toString(),
            settings.invertHorizontal.toString()
        ).joinToString("|")

        if (scrollMode2Process != null && scrollMode2ConfigSignature == configSignature) return
        if (scrollMode2Process != null) {
            stopScrollMode2Helper()
        }

        try {
            val command = StringBuilder().apply {
                append("exec ")
                append(helperFile.absolutePath)
                append(' ')
                append(eventPath)
                append(" --control ")
                append(ensureScrollMode2ControlFile().absolutePath)
                if (modeSwitchEnabled) {
                    append(" --mode-switch")
                }
                append(" --touch-scroll ")
                append(dm.widthPixels)
                append(' ')
                append(dm.heightPixels)
                append(' ')
                append(String.format(Locale.US, "%.2f", scale))
                append(' ')
                append(if (horizontalEnabled) "1" else "0")
                append(' ')
                append(if (settings.invertVertical) "1" else "0")
                append(' ')
                append(if (settings.invertHorizontal) "1" else "0")
            }.toString()
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            scrollMode2Process = process
            scrollMode2ConfigSignature = configSignature
            val thread = Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line == "SWITCH") {
                                Handler(Looper.getMainLooper()).post {
                                    QuickToggleRunner.run(this)
                                }
                            }
                        }
                    }
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    if (scrollMode2ReaderThread === Thread.currentThread()) {
                        scrollMode2ReaderThread = null
                    }
                    if (scrollMode2Process === process) {
                        scrollMode2Process = null
                        AppState.scrollMode2HelperRunning = false
                        scrollMode2ConfigSignature = null
                    }
                }
            }, "q25-scroll-mode-2").apply {
                isDaemon = true
                start()
            }
            scrollMode2ReaderThread = thread
        } catch (_: Exception) {
            stopScrollMode2Helper()
        }
    }

    private fun ensureScrollMode2ControlFile(): java.io.File {
        val existing = scrollMode2ControlFile
        if (existing != null) return existing
        val file = java.io.File(filesDir, "scroll_mode_2_control")
        if (!file.exists()) {
            file.writeText("0")
        }
        scrollMode2ControlFile = file
        return file
    }

    private fun writeScrollMode2Active(active: Boolean) {
        runCatching {
            ensureScrollMode2ControlFile().writeText(if (active) "1" else "0")
        }
        AppState.scrollMode2HelperRunning = active
    }

    private fun setScrollMode2Active(active: Boolean, immediate: Boolean = false) {
        pendingScrollMode2Deactivate?.let {
            scrollMode2ControlHandler.removeCallbacks(it)
            pendingScrollMode2Deactivate = null
        }

        if (active || immediate) {
            writeScrollMode2Active(active)
            return
        }

        val deactivate = Runnable {
            writeScrollMode2Active(false)
            pendingScrollMode2Deactivate = null
        }
        pendingScrollMode2Deactivate = deactivate
        scrollMode2ControlHandler.postDelayed(deactivate, SCROLL_MODE_2_DEACTIVATE_DELAY_MS)
    }

    private fun stopScrollMode2Helper(forceRootKill: Boolean = false) {
        setScrollMode2Active(false, immediate = true)
        scrollMode2Process?.destroy()
        scrollMode2Process?.destroyForcibly()
        scrollMode2Process = null
        AppState.scrollMode2HelperRunning = false
        scrollMode2ReaderThread?.interrupt()
        scrollMode2ReaderThread = null
        scrollMode2ConfigSignature = null
        pendingDirectScrollX = 0f
        pendingDirectScrollY = 0f
        if (forceRootKill) {
            execSu("pkill -f $SCROLL_MODE_2_HELPER_BASENAME")
        }
    }

    private fun ensureScrollMode2HelperInstalled(): java.io.File? {
        return try {
            val abi = "arm64-v8a"
            val assetName = "trackpad_helper/trackpad_helper_$abi"
            val outFile = java.io.File(filesDir, "${SCROLL_MODE_2_HELPER_BASENAME}_$abi")
            assets.open(assetName).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.setExecutable(true, true)
            outFile
        } catch (_: Exception) {
            null
        }
    }

    private fun findTrackpadEventPath(): String? {
        val (ok, output) = execSuForOutput("$SYSTEM_GETEVENT_BIN -pl")
        if (!ok || output.isNullOrBlank()) return null

        var currentPath: String? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("add device ")) {
                currentPath = trimmed.substringAfter(": ").takeIf { it.startsWith("/dev/input/event") }
                continue
            }
            if (trimmed.startsWith("name:")) {
                val name = trimmed.substringAfter('"').substringBeforeLast('"')
                if (name == "Q25_keyboard") {
                    return currentPath
                }
            }
        }
        return null
    }

    private fun registerTapMotion(dx: Int, dy: Int) {
        if (AppState.currentMode == Mode.SCROLL_MODE_2) return
        if (!shouldUseHeuristicTapClick()) return

        val deltaAbs = kotlin.math.abs(dx) + kotlin.math.abs(dy)
        if (deltaAbs == 0) return

        val now = SystemClock.uptimeMillis()
        if (tapGestureStartUptime == 0L || now - tapLastMotionUptime > TAP_IDLE_TIMEOUT_MS) {
            tapGestureStartUptime = now
            tapMotionDistance = 0
            tapMotionSamples = 0
            tapMaxDelta = 0
            tapPhysicalClickSeen = false
        }

        tapLastMotionUptime = now
        tapMotionDistance += deltaAbs
        tapMotionSamples += 1
        tapMaxDelta = maxOf(tapMaxDelta, kotlin.math.abs(dx), kotlin.math.abs(dy))

        tapMonitorHandler.removeCallbacks(tapFinalizeRunnable)
        tapMonitorHandler.postDelayed(tapFinalizeRunnable, TAP_IDLE_TIMEOUT_MS)
    }

    private fun finalizeTapGesture() {
        val duration = if (tapGestureStartUptime == 0L) Long.MAX_VALUE else {
            tapLastMotionUptime - tapGestureStartUptime
        }

        val isTapLike =
            shouldUseHeuristicTapClick() &&
                    !tapPhysicalClickSeen &&
                    tapMotionSamples in 1..MAX_TAP_MOTION_SAMPLES &&
                    tapMotionDistance in 1..MAX_TAP_TOTAL_DISTANCE &&
                    tapMaxDelta <= MAX_TAP_SINGLE_DELTA &&
                    duration <= MAX_TAP_DURATION_MS

        if (isTapLike) {
            injectSyntheticLeftClick()
        }

        resetTapGesture()
    }

    private fun resetTapGesture() {
        tapMonitorHandler.removeCallbacks(tapFinalizeRunnable)
        tapGestureStartUptime = 0L
        tapLastMotionUptime = 0L
        tapMotionDistance = 0
        tapMotionSamples = 0
        tapMaxDelta = 0
        tapPhysicalClickSeen = false
        pendingDirectScrollX = 0f
        pendingDirectScrollY = 0f
    }

    private fun shouldUseHeuristicTapClick(): Boolean {
        return AppState.currentMode == Mode.MOUSE && prefs.isTrackpadPressModeSwitchEnabled()
    }

    private fun injectSyntheticLeftClick() {
        val eventPath = trackpadEventPath ?: DEFAULT_TRACKPAD_EVENT_PATH
        keyInjectExec.execute {
            execSu(
                "$SYSTEM_SENDEVENT_BIN $eventPath 4 4 589825; " +
                "$SYSTEM_SENDEVENT_BIN $eventPath 1 272 1; " +
                "$SYSTEM_SENDEVENT_BIN $eventPath 0 0 0; " +
                "$SYSTEM_SENDEVENT_BIN $eventPath 4 4 589825; " +
                "$SYSTEM_SENDEVENT_BIN $eventPath 1 272 0; " +
                "$SYSTEM_SENDEVENT_BIN $eventPath 0 0 0"
            )
        }
    }

    private fun handleDirectScrollMotion(dx: Int, dy: Int) {
        if (AppState.currentMode != Mode.SCROLL_MODE_2) return
        if (scrollGestureInFlight) {
            pendingDirectScrollX += dx.toFloat()
            pendingDirectScrollY += dy.toFloat()
            return
        }

        val settings = prefs.getGlobalScrollSettings()
        pendingDirectScrollX += dx.toFloat()
        pendingDirectScrollY += dy.toFloat()
        val vertical = pendingDirectScrollY
        val horizontal = pendingDirectScrollX

        if (kotlin.math.abs(vertical) >= DIRECT_SCROLL_THRESHOLD) {
            pendingDirectScrollY = 0f
            val scrollUp = if (settings.invertVertical) vertical > 0 else vertical < 0
            val distance = kotlin.math.abs(vertical) * DIRECT_SCROLL_PIXEL_MULTIPLIER
            performDirectVerticalGesture(scrollUp, distance)
        }
    }

    private fun performDirectVerticalGesture(up: Boolean, distancePx: Float) {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val clamped = distancePx.coerceIn(DIRECT_SCROLL_MIN_DISTANCE_PX, DIRECT_SCROLL_MAX_DISTANCE_PX)
        val (startY, endY) = if (up) {
            (centerY + clamped / 2f) to (centerY - clamped / 2f)
        } else {
            (centerY - clamped / 2f) to (centerY + clamped / 2f)
        }
        val gesture = buildGestureStroke(centerX, startY, centerX, endY, DIRECT_SCROLL_DURATION_MS)
        scrollGestureInFlight = true
        val started = dispatchGesture(gesture, scrollGestureCallback, null)
        if (!started) {
            scrollGestureInFlight = false
        }
    }

    private fun performDirectHorizontalGesture(left: Boolean, distancePx: Float) {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val clamped = distancePx.coerceIn(DIRECT_SCROLL_MIN_DISTANCE_PX, DIRECT_SCROLL_MAX_DISTANCE_PX)
        val (startX, endX) = if (left) {
            (centerX + clamped / 2f) to (centerX - clamped / 2f)
        } else {
            (centerX - clamped / 2f) to (centerX + clamped / 2f)
        }
        val gesture = buildGestureStroke(startX, centerY, endX, centerY, DIRECT_SCROLL_DURATION_MS)
        scrollGestureInFlight = true
        val started = dispatchGesture(gesture, scrollGestureCallback, null)
        if (!started) {
            scrollGestureInFlight = false
        }
    }

    private fun handleScrollKey(event: KeyEvent) {
        val now = SystemClock.uptimeMillis()
        if ((now - lastScrollHandledUptime) < SCROLL_KEY_THROTTLE_MS) return
        lastScrollHandledUptime = now

        val pkg = AppState.currentForegroundPackage
        val settings = prefs.getEffectiveScrollSettings(pkg)
        val steps = settings.sensitivity.steps

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val scrollUp = !settings.invertVertical
                scrollVertical(scrollUp, steps)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val scrollUp = settings.invertVertical
                scrollVertical(scrollUp, steps)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (settings.horizontalEnabled) {
                    val scrollLeft = !settings.invertHorizontal
                    scrollHorizontal(scrollLeft, steps)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (settings.horizontalEnabled) {
                    val scrollLeft = settings.invertHorizontal
                    scrollHorizontal(scrollLeft, steps)
                }
            }
        }
    }

    // ---------- IME / AUTO KEYBOARD FOR TEXT INPUT ----------

    private fun loadImePackages(): Set<String> {
        return try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList
            HashSet<String>().apply {
                for (ime in enabled) add(ime.packageName)
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (imePackages.contains(pkg)) return true

        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains("keyboard", ignoreCase = true)) return true
        if (pkg.contains("ime", ignoreCase = true)) return true

        return false
    }

    private fun isAutoKeyboardEnabledForPackage(packageName: String?): Boolean {
        // Scroll mode 2 must stay stable while active. Auto-switching to keyboard tears
        // the grabbed helper down and back up, which causes major app churn in some apps.
        if (AppState.currentMode == Mode.SCROLL_MODE_2 ||
            AppState.modeBeforeTextInput == Mode.SCROLL_MODE_2) {
            return false
        }
        return prefs.isEffectiveAutoKeyboardForTextEnabled(packageName)
    }

    private fun handleTextInteraction(event: AccessibilityEvent) {
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return

        if (fg != null && prefs.getExcludedPackages().contains(fg)) return

        lastTextInteractionUptime = SystemClock.uptimeMillis()

        val src = event.source
        val isText = if (src != null) {
            val res = isTextInputNode(src)
            src.recycle()
            res
        } else false

        if (isText) {
            enterTextInputOverrideIfNeeded()
        } else {
            scheduleContentCheck()
        }
    }

    private fun scheduleContentCheck() {
        if (contentCheckScheduled) return
        contentCheckScheduled = true
        contentCheckHandler.removeCallbacks(contentCheckRunnable)
        contentCheckHandler.postDelayed(contentCheckRunnable, 120L)
    }

    private fun handleContentChangedCheck() {
        val fg = AppState.currentForegroundPackage

        if (!isAutoKeyboardEnabledForPackage(fg) || (fg != null && prefs.getExcludedPackages().contains(fg))) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        if (hasFocusedTextInput()) {
            enterTextInputOverrideIfNeeded()
        }
    }

    private fun handleViewFocusedForAutoKeyboard(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()

        if (pkg == "android" || pkg == "com.android.systemui") return

        val excluded = prefs.getExcludedPackages()
        if (pkg.isNotEmpty() && excluded.contains(pkg)) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        if (!isAutoKeyboardEnabledForPackage(pkg)) return

        val src = event.source ?: return
        val isText = isTextInputNode(src)
        src.recycle()

        if (isText) {
            lastTextInteractionUptime = SystemClock.uptimeMillis()
            enterTextInputOverrideIfNeeded()
        }
    }

    private fun isTextInputNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true)) return true
        if (cls.contains("TextInput", ignoreCase = true)) return true
        if (cls.contains("AutoCompleteTextView", ignoreCase = true)) return true
        if (node.isEditable) return true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    private fun enterTextInputOverrideIfNeeded() {
        if (AppState.textInputOverrideActive) return

        val fg = AppState.currentForegroundPackage
        if (fg != null && prefs.getExcludedPackages().contains(fg)) return

        val anyHoldActive = isAnyHoldActive()

        val prevMode = if (anyHoldActive) {
            AppState.modeBeforeHoldKey
                ?: AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(fg)
        } else {
            AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(fg)
        }

        if (prevMode == Mode.SCROLL_MODE_2) {
            return
        }

        AppState.modeBeforeTextInput = prevMode

        if (prevMode == Mode.KEYBOARD) {
            val desiredProc = procValueForMode(Mode.KEYBOARD)
            val currentProc = if (desiredProc != null) TrackpadController.readValue() else null
            if (desiredProc != null && currentProc != null && currentProc != desiredProc) {
                val ok = TrackpadController.setModeValue(Mode.KEYBOARD, this)
                if (!ok) {
                    showRootError()
                    AppState.modeBeforeTextInput = null
                    AppState.textInputOverrideActive = false
                    stopTextOverrideMonitor()
                    return
                }
                NotificationHelper.clearRootError(this)
                AppState.currentMode = Mode.KEYBOARD
                syncModeStatusNotification(Mode.KEYBOARD)
            }

            AppState.textInputOverrideActive = true
            startTextOverrideMonitor()
            return
        }

        val ok = TrackpadController.setModeValue(Mode.KEYBOARD, this)
        if (!ok) {
            showRootError()
            AppState.modeBeforeTextInput = null
            AppState.textInputOverrideActive = false
            stopTextOverrideMonitor()
            return
        }

        NotificationHelper.clearRootError(this)
        AppState.currentMode = Mode.KEYBOARD
        syncModeStatusNotification(Mode.KEYBOARD)
        AppState.textInputOverrideActive = true
        startTextOverrideMonitor()
    }

    private fun restoreModeAfterTextInput() {
        val fg = AppState.currentForegroundPackage
        val targetMode = AppState.modeBeforeTextInput ?: getEffectiveModeForPackage(fg)

        if (isAnyHoldActive()) {
            clearAllHoldState()
        }

        val desiredProc = procValueForMode(targetMode)
        val currentProc = if (desiredProc != null) TrackpadController.readValue() else null

        val alreadyCorrect = (AppState.currentMode == targetMode) &&
                (desiredProc == null || currentProc == null || currentProc == desiredProc)

        if (!alreadyCorrect) {
            val ok = TrackpadController.setModeValue(targetMode, this)
            if (!ok) {
                showRootError()
            } else {
                NotificationHelper.clearRootError(this)
                AppState.currentMode = targetMode
                syncModeStatusNotification(targetMode)
            }
        }

        AppState.modeBeforeTextInput = null
        AppState.textInputOverrideActive = false
    }

    private fun handleEndOfTextInputCheck() {
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return
        if (!AppState.textInputOverrideActive) return

        val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < 1200L
        if (!hasFocusedTextInput() && !recent) {
            restoreModeAfterTextInput()
            stopTextOverrideMonitor()
        }
    }

    private fun startTextOverrideMonitor() {
        textOverrideMisses = 0
        textOverrideHandler.removeCallbacks(textOverrideCheckRunnable)
        textOverrideHandler.postDelayed(textOverrideCheckRunnable, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
    }

    private fun stopTextOverrideMonitor() {
        textOverrideHandler.removeCallbacks(textOverrideCheckRunnable)
        textOverrideMisses = 0
    }

    private fun hasFocusedTextInput(): Boolean {
        val root = rootInActiveWindow ?: return false

        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }

        if (focused != null) {
            val res = isTextInputNode(focused)
            focused.recycle()
            root.recycle()
            return res
        }

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val focusedNow = node.isFocused || node.isAccessibilityFocused
            if (focusedNow && isTextInputNode(node)) {
                node.recycle()
                recycleQueue(queue)
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            node.recycle()
        }

        return false
    }

    private fun recycleQueue(queue: ArrayDeque<AccessibilityNodeInfo>) {
        while (queue.isNotEmpty()) {
            queue.removeFirst().recycle()
        }
    }

    // ---------- HOLD-KEY MODE SWITCHING (PRIMARY Hold + SECONDARY Hold) ----------

    private fun handleHoldKeyEvents(event: KeyEvent, fg: String?): Boolean {
        // Keep scroll mode 2 fully manual. Hold-key transitions interfere with the
        // grabbed helper lifecycle and have been a major source of mode thrash.
        if (AppState.currentMode == Mode.SCROLL_MODE_2) return false

        val now = SystemClock.uptimeMillis()

        val hold1KeyCode = prefs.getEffectiveHoldKeyCode(fg)

        val hold2Mode = prefs.getEffectiveHold2Mode(fg)
        val hold2UseHold1Double = prefs.isEffectiveHold2UseHold1DoublePressHold(fg)

        val tieHold2ToHold1Double = hold2UseHold1Double && hold2Mode != HoldMode.DISABLED

        val hold2KeyCode = if (tieHold2ToHold1Double) hold1KeyCode else prefs.getEffectiveHold2KeyCode(fg)

        val matchesHold1Key = (event.keyCode == hold1KeyCode)
        val matchesHold2Key = (!tieHold2ToHold1Double && event.keyCode == hold2KeyCode)

        if (!matchesHold1Key && !matchesHold2Key) return false

        if (!tieHold2ToHold1Double && hold1KeyCode == hold2KeyCode && matchesHold1Key) {
            handleHoldSingleHandler(
                event = event,
                fg = fg,
                state = hold1,
                holdMode = prefs.getEffectiveHoldMode(fg),
                allowInText = prefs.isEffectiveHoldAllowedInTextFields(fg),
                requireDouble = prefs.isEffectiveHoldDoublePressRequired(fg),
                toastPrefix = "Primary Hold"
            )
            return true
        }

        if (tieHold2ToHold1Double && matchesHold1Key) {
            handleHold1KeyWithHold2DoubleGesture(event, fg, now, hold1KeyCode)
            return true
        }

        if (matchesHold1Key) {
            handleHoldSingleHandler(
                event = event,
                fg = fg,
                state = hold1,
                holdMode = prefs.getEffectiveHoldMode(fg),
                allowInText = prefs.isEffectiveHoldAllowedInTextFields(fg),
                requireDouble = prefs.isEffectiveHoldDoublePressRequired(fg),
                toastPrefix = "Primary Hold"
            )
            return true
        }

        if (matchesHold2Key) {
            handleHoldSingleHandler(
                event = event,
                fg = fg,
                state = hold2,
                holdMode = hold2Mode,
                allowInText = prefs.isEffectiveHold2AllowedInTextFields(fg),
                requireDouble = prefs.isEffectiveHold2DoublePressRequired(fg),
                toastPrefix = "Secondary Hold"
            )
            return true
        }

        return false
    }

    private fun handleHoldSingleHandler(
        event: KeyEvent,
        fg: String?,
        state: HoldRuntimeState,
        holdMode: HoldMode,
        allowInText: Boolean,
        requireDouble: Boolean,
        toastPrefix: String
    ) {
        if (holdMode == HoldMode.DISABLED) return
        if (!allowInText && hasFocusedTextInput()) return

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return

        val now = SystemClock.uptimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (state.active && state.keyDown == event.keyCode) return

                if (requireDouble) {
                    val within = state.waitingSecondPress && (now - state.lastUpUptime) <= DOUBLE_PRESS_TIMEOUT_MS
                    if (within) {
                        state.waitingSecondPress = false
                        startHold(state, fg, event.keyCode, holdMode, toastPrefix)
                    } else {
                        state.keyDown = event.keyCode
                        state.waitingSecondPress = false
                        state.lastUpUptime = 0L
                        syncHoldStateToAppState()
                    }
                } else {
                    startHold(state, fg, event.keyCode, holdMode, toastPrefix)
                }
            }

            KeyEvent.ACTION_UP -> {
                if (state.active && state.keyDown == event.keyCode) {
                    endHold(state, fg)
                    return
                }

                if (requireDouble && state.keyDown == event.keyCode) {
                    state.keyDown = null
                    state.waitingSecondPress = true
                    state.lastUpUptime = now
                    syncHoldStateToAppState()
                }
            }
        }
    }

    private fun handleHold1KeyWithHold2DoubleGesture(event: KeyEvent, fg: String?, now: Long, hold1KeyCode: Int) {
        val hold1Mode = prefs.getEffectiveHoldMode(fg)
        val hold2Mode = prefs.getEffectiveHold2Mode(fg)

        val hold1AllowInText = prefs.isEffectiveHoldAllowedInTextFields(fg)
        val hold2AllowInText = prefs.isEffectiveHold2AllowedInTextFields(fg)

        val canRunHold1 = hold1Mode != HoldMode.DISABLED
        val canRunHold2 = hold2Mode != HoldMode.DISABLED

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (canRunHold2) {
                    val within = hold2.waitingSecondPress && (now - hold2.lastUpUptime) <= DOUBLE_PRESS_TIMEOUT_MS
                    if (within) {
                        hold2.waitingSecondPress = false
                        cancelPendingSingleHold(hold1)

                        if (!hold2AllowInText && hasFocusedTextInput()) return
                        startHold(hold2, fg, hold1KeyCode, hold2Mode, "Secondary Hold")
                        return
                    }
                }

                if (canRunHold1) {
                    if (!hold1AllowInText && hasFocusedTextInput()) return
                    scheduleDelayedSingleHold1Start(fg, hold1KeyCode, hold1Mode)
                    hold1.keyDown = hold1KeyCode
                    syncHoldStateToAppState()
                }
            }

            KeyEvent.ACTION_UP -> {
                if (hold2.active && hold2.keyDown == hold1KeyCode) {
                    endHold(hold2, fg)
                    return
                }

                val hold1WasActive = hold1.active && hold1.keyDown == hold1KeyCode
                cancelPendingSingleHold(hold1)

                if (hold1WasActive) {
                    endHold(hold1, fg)
                    return
                }

                if (canRunHold2) {
                    hold2.waitingSecondPress = true
                    hold2.lastUpUptime = now
                }

                hold1.keyDown = null
                syncHoldStateToAppState()
            }
        }
    }

    private fun scheduleDelayedSingleHold1Start(fg: String?, keyCode: Int, holdMode: HoldMode) {
        cancelPendingSingleHold(hold1)

        val r = Runnable {
            if (hold1.active) return@Runnable
            if (hold2.active) return@Runnable
            if (hold1.keyDown != keyCode) return@Runnable
            startHold(hold1, fg, keyCode, holdMode, "Primary Hold")
        }

        hold1.pendingSingleHoldRunnable = r
        holdHandler.postDelayed(r, SINGLE_HOLD_ACTIVATE_DELAY_MS)
    }

    private fun cancelPendingSingleHold(state: HoldRuntimeState) {
        state.pendingSingleHoldRunnable?.let { holdHandler.removeCallbacks(it) }
        state.pendingSingleHoldRunnable = null
    }

    private fun cancelHoldPendingRunnables() {
        cancelPendingSingleHold(hold1)
        cancelPendingSingleHold(hold2)
    }

    private fun isAnyHoldActive(): Boolean {
        return hold1.active || hold2.active
    }

    private fun clearAllHoldState() {
        hold1.active = false
        hold1.keyDown = null
        hold1.targetMode = null
        hold1.waitingSecondPress = false
        hold1.lastUpUptime = 0L
        cancelPendingSingleHold(hold1)

        hold2.active = false
        hold2.keyDown = null
        hold2.targetMode = null
        hold2.waitingSecondPress = false
        hold2.lastUpUptime = 0L
        cancelPendingSingleHold(hold2)

        AppState.activeHoldSlot = 0
        AppState.hold1Active = false
        AppState.hold1KeyCodeDown = null
        AppState.modeBeforeHold1 = null
        AppState.hold2Active = false
        AppState.hold2KeyCodeDown = null
        AppState.modeBeforeHold2 = null
        AppState.hold1DoublePressWaiting = false
        AppState.hold1FirstTapUpUptime = 0L
        AppState.hold2DoublePressWaiting = false
        AppState.hold2FirstTapUpUptime = 0L
    }

    private fun syncHoldStateToAppState() {
        AppState.activeHoldSlot = when {
            hold1.active && hold2.active -> lastActivatedHoldSlot.coerceIn(1, 2)
            hold2.active -> 2
            hold1.active -> 1
            else -> 0
        }

        AppState.hold1Active = hold1.active
        AppState.hold1KeyCodeDown = hold1.keyDown
        AppState.hold2Active = hold2.active
        AppState.hold2KeyCodeDown = hold2.keyDown

        AppState.hold1DoublePressWaiting = hold1.waitingSecondPress
        AppState.hold1FirstTapUpUptime = hold1.lastUpUptime
        AppState.hold2DoublePressWaiting = hold2.waitingSecondPress
        AppState.hold2FirstTapUpUptime = hold2.lastUpUptime
    }

    private fun procValueForMode(mode: Mode): String? {
        return when (mode) {
            Mode.MOUSE -> "0"
            Mode.KEYBOARD, Mode.SCROLL_WHEEL -> "1"
            Mode.SCROLL_MODE_2 -> "0"
            Mode.FOLLOW_SYSTEM -> null
        }
    }

    private fun startHold(state: HoldRuntimeState, fg: String?, keyCode: Int, holdMode: HoldMode, toastPrefix: String) {
        val baseMode =
            AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(fg)

        val target = computeHoldTargetMode(baseMode, holdMode) ?: return

        val hadAnyHoldBefore = (hold1.active || hold2.active)
        if (!hadAnyHoldBefore) {
            AppState.modeBeforeHoldKey = baseMode
        }

        state.active = true
        state.keyDown = keyCode
        state.targetMode = target

        lastActivatedHoldSlot = state.id
        if (state.id == 1) AppState.modeBeforeHold1 = baseMode else AppState.modeBeforeHold2 = baseMode

        syncHoldStateToAppState()

        if (AppState.currentMode == target) {
            val desiredProc = procValueForMode(target)
            val currentProc = if (desiredProc != null) TrackpadController.readValue() else null
            if (desiredProc == null || currentProc == null || currentProc == desiredProc) {
                if (prefs.isToastHoldKeyEnabled()) {
                    ToastHelper.show(this, "$toastPrefix: ${modeLabel(target)}")
                }
                return
            }
        }

        val ok = TrackpadController.setModeValue(target, this)
        if (!ok) {
            showRootError()

            state.active = false
            state.keyDown = null
            state.targetMode = null

            if (!(hold1.active || hold2.active)) {
                AppState.modeBeforeHoldKey = null
                AppState.modeBeforeHold1 = null
                AppState.modeBeforeHold2 = null
                lastActivatedHoldSlot = 0
            }

            syncHoldStateToAppState()
            return
        }

        NotificationHelper.clearRootError(this)
        AppState.currentMode = target
        syncModeStatusNotification(target)

        if (prefs.isToastHoldKeyEnabled()) {
            ToastHelper.show(this, "$toastPrefix: ${modeLabel(target)}")
        }
    }

    private fun endHold(state: HoldRuntimeState, fg: String?) {
        val releasedKey = state.keyDown

        state.active = false
        state.keyDown = null
        state.targetMode = null

        if (lastActivatedHoldSlot == state.id) {
            lastActivatedHoldSlot = when {
                hold2.active -> 2
                hold1.active -> 1
                else -> 0
            }
        }

        val otherActiveTarget: Mode? = when {
            hold1.active -> hold1.targetMode
            hold2.active -> hold2.targetMode
            else -> null
        }

        val restoreTarget = when {
            otherActiveTarget != null -> otherActiveTarget
            AppState.textInputOverrideActive -> Mode.KEYBOARD
            else -> AppState.modeBeforeHoldKey ?: getEffectiveModeForPackage(fg)
        }

        if (!(hold1.active || hold2.active)) {
            AppState.modeBeforeHoldKey = null
            AppState.modeBeforeHold1 = null
            AppState.modeBeforeHold2 = null
        }

        if (!hold1.active && !hold2.active) {
            if (AppState.hold1KeyCodeDown == releasedKey) AppState.hold1KeyCodeDown = null
            if (AppState.hold2KeyCodeDown == releasedKey) AppState.hold2KeyCodeDown = null
        }

        syncHoldStateToAppState()

        if (AppState.currentMode == restoreTarget) {
            val desiredProc = procValueForMode(restoreTarget)
            val currentProc = if (desiredProc != null) TrackpadController.readValue() else null
            if (desiredProc == null || currentProc == null || currentProc == desiredProc) {
                return
            }
        }

        val ok = TrackpadController.setModeValue(restoreTarget, this)
        if (!ok) {
            showRootError()
            return
        }

        NotificationHelper.clearRootError(this)
        AppState.currentMode = restoreTarget
        syncModeStatusNotification(restoreTarget)
    }

    private fun computeHoldTargetMode(base: Mode, holdMode: HoldMode): Mode? {
        val desired = when (holdMode) {
            HoldMode.DISABLED -> null
            HoldMode.MOUSE -> Mode.MOUSE
            HoldMode.KEYBOARD -> Mode.KEYBOARD
            HoldMode.SCROLL_WHEEL -> Mode.SCROLL_WHEEL
        } ?: return null

        if (desired != base) return desired

        return when (base) {
            Mode.MOUSE -> Mode.KEYBOARD
            Mode.KEYBOARD -> Mode.MOUSE
            Mode.SCROLL_WHEEL -> Mode.KEYBOARD
            Mode.SCROLL_MODE_2 -> Mode.KEYBOARD
            Mode.FOLLOW_SYSTEM -> Mode.KEYBOARD
        }
    }

    // ---------- SCROLL HELPERS ----------

    private fun scrollVertical(up: Boolean, steps: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            enqueueVerticalScroll(up, steps)
        } else {
            scrollVerticalNodeBased(up, steps)
        }
    }

    private fun scrollHorizontal(left: Boolean, steps: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            enqueueHorizontalScroll(left, steps)
        } else {
            scrollHorizontalNodeBased(left, steps)
        }
    }

    private fun enqueueVerticalScroll(up: Boolean, steps: Int) {
        val delta = if (up) steps else -steps
        pendingVerticalScrollSteps = (pendingVerticalScrollSteps + delta)
            .coerceIn(-MAX_PENDING_SCROLL_STEPS, MAX_PENDING_SCROLL_STEPS)
        schedulePendingScrollProcessing()
    }

    private fun enqueueHorizontalScroll(left: Boolean, steps: Int) {
        val delta = if (left) steps else -steps
        pendingHorizontalScrollSteps = (pendingHorizontalScrollSteps + delta)
            .coerceIn(-MAX_PENDING_SCROLL_STEPS, MAX_PENDING_SCROLL_STEPS)
        schedulePendingScrollProcessing()
    }

    private fun schedulePendingScrollProcessing() {
        contentCheckHandler.removeCallbacks(processPendingScrollRunnable)
        contentCheckHandler.post(processPendingScrollRunnable)
    }

    private val processPendingScrollRunnable = Runnable {
        drainPendingScroll()
    }

    private fun drainPendingScroll() {
        if (scrollGestureInFlight) return

        if (pendingVerticalScrollSteps != 0) {
            val signedSteps = pendingVerticalScrollSteps.coerceIn(-GESTURE_STEP_BATCH_SIZE, GESTURE_STEP_BATCH_SIZE)
            pendingVerticalScrollSteps -= signedSteps
            if (!performVerticalGesture(signedSteps > 0, kotlin.math.abs(signedSteps))) {
                scrollVerticalNodeBased(signedSteps > 0, kotlin.math.abs(signedSteps))
                drainPendingScroll()
            }
            return
        }

        if (pendingHorizontalScrollSteps != 0) {
            val signedSteps = pendingHorizontalScrollSteps.coerceIn(-GESTURE_STEP_BATCH_SIZE, GESTURE_STEP_BATCH_SIZE)
            pendingHorizontalScrollSteps -= signedSteps
            if (!performHorizontalGesture(signedSteps > 0, kotlin.math.abs(signedSteps))) {
                scrollHorizontalNodeBased(signedSteps > 0, kotlin.math.abs(signedSteps))
                drainPendingScroll()
            }
        }
    }

    private fun buildGestureStroke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): GestureDescription {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun performVerticalGesture(up: Boolean, steps: Int): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        val baseDistance = height * 0.05f
        val distance = baseDistance * steps
        val duration = (SCROLL_GESTURE_BASE_DURATION_MS + (steps - 1) * SCROLL_GESTURE_EXTRA_STEP_DURATION_MS)
            .coerceAtMost(SCROLL_GESTURE_MAX_DURATION_MS)

        val centerX = width / 2f
        val centerY = height / 2f

        val (startY, endY) = if (up) {
            (centerY + distance / 2f) to (centerY - distance / 2f)
        } else {
            (centerY - distance / 2f) to (centerY + distance / 2f)
        }

        val gesture = buildGestureStroke(centerX, startY, centerX, endY, duration)
        scrollGestureInFlight = true
        val started = dispatchGesture(gesture, scrollGestureCallback, null)
        if (!started) {
            scrollGestureInFlight = false
        }
        return started
    }

    private fun performHorizontalGesture(left: Boolean, steps: Int): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()

        val baseDistance = width * 0.05f
        val distance = baseDistance * steps
        val duration = (SCROLL_GESTURE_BASE_DURATION_MS + (steps - 1) * SCROLL_GESTURE_EXTRA_STEP_DURATION_MS)
            .coerceAtMost(SCROLL_GESTURE_MAX_DURATION_MS)

        val centerX = width / 2f
        val centerY = resources.displayMetrics.heightPixels.toFloat() / 2f

        val (startX, endX) = if (left) {
            (centerX + distance / 2f) to (centerX - distance / 2f)
        } else {
            (centerX - distance / 2f) to (centerX + distance / 2f)
        }

        val gesture = buildGestureStroke(startX, centerY, endX, centerY, duration)
        scrollGestureInFlight = true
        val started = dispatchGesture(gesture, scrollGestureCallback, null)
        if (!started) {
            scrollGestureInFlight = false
        }
        return started
    }

    private val scrollGestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            scrollGestureInFlight = false
            if (AppState.currentMode == Mode.SCROLL_MODE_2 &&
                (kotlin.math.abs(pendingDirectScrollX) >= DIRECT_SCROLL_THRESHOLD ||
                 kotlin.math.abs(pendingDirectScrollY) >= DIRECT_SCROLL_THRESHOLD)
            ) {
                handleDirectScrollMotion(0, 0)
            }
            schedulePendingScrollProcessing()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            scrollGestureInFlight = false
            if (AppState.currentMode == Mode.SCROLL_MODE_2 &&
                (kotlin.math.abs(pendingDirectScrollX) >= DIRECT_SCROLL_THRESHOLD ||
                 kotlin.math.abs(pendingDirectScrollY) >= DIRECT_SCROLL_THRESHOLD)
            ) {
                handleDirectScrollMotion(0, 0)
            }
            schedulePendingScrollProcessing()
        }
    }

    private fun scrollVerticalNodeBased(up: Boolean, steps: Int) {
        val node = findScrollableNode() ?: return
        val action = if (up) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        repeat(steps) { node.performAction(action) }
        node.recycle()
    }

    private fun scrollHorizontalNodeBased(left: Boolean, steps: Int) {
        val node = findScrollableNode() ?: return
        val action = if (left) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        repeat(steps) { node.performAction(action) }
        node.recycle()
    }

    private fun findScrollableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.isScrollable) {
                recycleQueue(queue)
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            node.recycle()
        }

        return null
    }

    // ---------- APP SWITCH / MODE LOGIC ----------

    private fun handleForegroundAppChanged(packageName: String) {
        if (packageName == this.packageName) return
        if (packageName == "android" || packageName == "com.android.systemui") return
        if (isImePackage(packageName)) return

        val excluded = prefs.getExcludedPackages()
        if (excluded.contains(packageName)) return

        if (!isLegacyOverridePipelineEnabled()) {
            AppState.currentForegroundPackage = packageName
            lastAppliedPackage = packageName
            return
        }

        val prevFg = AppState.currentForegroundPackage
        AppState.currentForegroundPackage = packageName

        val overridePkg = AppState.manualOverrideForPackage
        if (overridePkg != null && overridePkg != packageName) {
            AppState.manualOverrideForPackage = null
        }

        if (AppState.textInputOverrideActive && prevFg != null && prevFg != packageName) {
            AppState.textInputOverrideActive = false
            AppState.modeBeforeTextInput = null
            stopTextOverrideMonitor()
        }

        if ((hold1.active || hold2.active) && prevFg != null && prevFg != packageName) {
            clearAllHoldState()
        }

        if (AppState.manualOverrideForPackage == packageName) return
        if (AppState.textInputOverrideActive || hold1.active || hold2.active) return
        lastAppliedPackage = packageName
    }

    private fun syncModeStatusNotification(
        mode: Mode = AppState.currentMode ?: prefs.getLastKnownMode() ?: prefs.getSystemDefaultMode(),
        appLabel: String? = null
    ) {
        if (prefs.isModeStatusNotificationEnabled()) {
            NotificationHelper.updateModeStatus(this, mode, appLabel)
        } else {
            NotificationHelper.clearModeStatus(this)
        }
    }

    private fun applyModeTransition(targetMode: Mode, showQuickToggleToast: Boolean) {
        val currentMode = AppState.currentMode ?: prefs.getLastKnownMode()
        if (currentMode == targetMode && (targetMode != Mode.SCROLL_MODE_2 || AppState.scrollMode2HelperRunning)) {
            syncModeStatusNotification(targetMode)
            return
        }

        clearLegacyOverrideState()

        val ok = TrackpadController.setModeValue(targetMode)
        if (!ok) {
            showRootError()
            return
        }

        if (currentMode == Mode.SCROLL_MODE_2 && targetMode != Mode.SCROLL_MODE_2) {
            setScrollMode2Active(false, immediate = true)
        }

        if (targetMode == Mode.MOUSE) {
            TrackpadController.applyCursorSensitivity(prefs.getGlobalCursorSensitivity())
        }

        NotificationHelper.clearRootError(this)
        AppState.currentMode = targetMode
        prefs.setLastKnownMode(targetMode)
        syncScrollMode2Helper()
        syncTapMonitor()
        syncModeStatusNotification(targetMode)

        if (showQuickToggleToast) {
            ToastHelper.show(this, "Toggled to ${modeLabel(targetMode)} Mode!")
        }
    }

    private fun getEffectiveModeForPackage(packageName: String?): Mode {
        return prefs.getSystemDefaultMode()
    }

    private fun modeLabel(mode: Mode): String {
        return when (mode) {
            Mode.MOUSE -> "Mouse"
            Mode.KEYBOARD -> "Keyboard"
            Mode.SCROLL_WHEEL -> "Scroll wheel"
            Mode.SCROLL_MODE_2 -> "Scroll mode 2"
            Mode.FOLLOW_SYSTEM -> "System"
        }
    }

    private fun getAppLabel(packageName: String): String? {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun showRootError() {
        NotificationHelper.showRootError(
            this,
            "Root access failed. Please grant root to Q25 Trackpad Customizer."
        )
    }

    companion object {
        const val ACTION_REFRESH_HELPER_STATE = "tech.shroyer.q25trackpadcustomizer.REFRESH_HELPER_STATE"
        const val ACTION_APPLY_MODE = "tech.shroyer.q25trackpadcustomizer.APPLY_MODE"
        const val EXTRA_TARGET_MODE = "target_mode"
        const val EXTRA_FORCE_STOP_SCROLL_MODE_2 = "force_stop_scroll_mode_2"
        const val EXTRA_SHOW_QUICK_TOGGLE_TOAST = "show_quick_toggle_toast"
        const val SCROLL_MODE_2_HELPER_BASENAME = "trackpad_helper_v2"
        private const val TEXT_OVERRIDE_CHECK_INTERVAL_MS = 350L
        private const val DOUBLE_PRESS_TIMEOUT_MS = 320L
        private const val SINGLE_HOLD_ACTIVATE_DELAY_MS = 120L
        private const val DEFAULT_TRACKPAD_EVENT_PATH = "/dev/input/event2"
        private const val SYSTEM_GETEVENT_BIN = "/system/bin/getevent"
        private const val SYSTEM_SENDEVENT_BIN = "/system/bin/sendevent"
        private const val SYSTEM_INPUT_BIN = "/system/bin/input"
        private const val TAP_IDLE_TIMEOUT_MS = 140L
        private const val MAX_TAP_DURATION_MS = 190L
        private const val MAX_TAP_MOTION_SAMPLES = 14
        private const val MAX_TAP_TOTAL_DISTANCE = 38
        private const val MAX_TAP_SINGLE_DELTA = 12
        private const val DIRECT_SCROLL_THRESHOLD = 4f
        private const val DIRECT_SCROLL_PIXEL_MULTIPLIER = 3.5f
        private const val DIRECT_SCROLL_MIN_DISTANCE_PX = 10f
        private const val DIRECT_SCROLL_MAX_DISTANCE_PX = 64f
        private const val DIRECT_SCROLL_DURATION_MS = 16L
        private const val HELPER_SYNC_INTERVAL_MS = 1500L
        private const val SCROLL_MODE_2_DEACTIVATE_DELAY_MS = 180L
        private const val TRACKPAD_MODE_SWITCH_SCAN_CODE = 5
        private const val SCROLL_KEY_THROTTLE_MS = 95L
        private const val MAX_PENDING_SCROLL_STEPS = 12
        private const val GESTURE_STEP_BATCH_SIZE = 3
        private const val SCROLL_GESTURE_BASE_DURATION_MS = 32L
        private const val SCROLL_GESTURE_EXTRA_STEP_DURATION_MS = 10L
        private const val SCROLL_GESTURE_MAX_DURATION_MS = 64L
    }
}
