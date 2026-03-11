package tech.shroyer.q25trackpadcustomizer

import android.R as AndroidR
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    // Header
    private lateinit var tvSettingsVersion: TextView

    // Initial Setup
    private lateinit var btnTestRootAccess: Button
    private lateinit var btnAccessibilitySetup: Button

    // Toast/Theme UI
    private lateinit var checkToastQuick: CheckBox
    private lateinit var checkToastDefault: CheckBox
    private lateinit var checkToastHoldKey: CheckBox
    private lateinit var checkModeStatusNotification: CheckBox
    private lateinit var spinnerTheme: Spinner

    // Quick Toggle global (modes to cycle + single-match fallback)
    private lateinit var checkQuickToggleMouse: CheckBox
    private lateinit var checkQuickToggleKeyboard: CheckBox
    private lateinit var checkQuickToggleScroll: CheckBox
    private lateinit var checkQuickToggleScroll2: CheckBox
    private lateinit var tvQuickToggleFallbackLabel: TextView
    private lateinit var spinnerQuickToggleFallback: Spinner

    // Global scroll
    private lateinit var spinnerScrollSensitivity: Spinner
    private lateinit var spinnerCursorSensitivity: Spinner
    private lateinit var spinnerScrollMode2Sensitivity: Spinner
    private lateinit var checkTrackpadPressModeSwitch: CheckBox
    private lateinit var checkScrollHorizontal: CheckBox
    private lateinit var checkScrollInvertVertical: CheckBox
    private lateinit var checkScrollInvertHorizontal: CheckBox

    // Backup/restore
    private lateinit var btnBackupSettings: Button
    private lateinit var btnRestoreSettings: Button

    // Updates
    private lateinit var btnCheckUpdates: Button
    private lateinit var tvViewOnGitHub: TextView

    // Setup prefs (root granted flag)
    private val setupPrefs by lazy { getSharedPreferences(SETUP_PREFS, MODE_PRIVATE) }

    // Wizard dialog guard
    private var wizardDialogShowing = false

    private val themeOptions = listOf(
        ThemePref.FOLLOW_SYSTEM,
        ThemePref.LIGHT,
        ThemePref.DARK
    )

    private val themeLabels = listOf(
        "Follow system",
        "Light",
        "Dark"
    )

    private val scrollSensOptions = listOf(
        ScrollSensitivity.ULTRA_SLOW,
        ScrollSensitivity.VERY_SLOW,
        ScrollSensitivity.SLOW,
        ScrollSensitivity.MEDIUM,
        ScrollSensitivity.FAST
    )

    private val scrollSensLabels = listOf(
        "Ultra slow",
        "Very slow",
        "Slow",
        "Medium",
        "Fast"
    )

    private val cursorSensOptions = listOf(
        CursorSensitivity.SLOW,
        CursorSensitivity.MEDIUM,
        CursorSensitivity.FAST
    )

    private val cursorSensLabels = listOf(
        "Slow",
        "Medium",
        "Fast"
    )

    private val quickToggleFallbackOptions = listOf(
        Mode.MOUSE,
        Mode.KEYBOARD,
        Mode.SCROLL_WHEEL,
        Mode.SCROLL_MODE_2
    )

    private val quickToggleFallbackLabels = listOf(
        "Mouse",
        "Keyboard",
        "Scroll wheel",
        "Scroll mode 2"
    )

    private var suppressUiListeners = false

    private val backupCreateLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) handleBackupCreate(uri)
        }

    private val restoreOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleRestoreOpen(uri)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            suppressUiListeners = true
            try {
                checkModeStatusNotification.isChecked = granted
            } finally {
                suppressUiListeners = false
            }

            prefs.setModeStatusNotificationEnabled(granted)
            if (granted) {
                val mode = AppState.currentMode ?: prefs.getLastKnownMode() ?: prefs.getSystemDefaultMode()
                NotificationHelper.updateModeStatus(this, mode)
            } else {
                NotificationHelper.clearModeStatus(this)
                Toast.makeText(this, "Notification permission is required for the persistent mode indicator.", Toast.LENGTH_LONG).show()
            }
        }

    private fun getAppVersionName(): String {
        return try {
            val pm = packageManager
            val pkg = packageName
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            info.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyThemeFromPrefs(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)

        // Header
        tvSettingsVersion = findViewById(R.id.tvSettingsVersion)
        tvSettingsVersion.text = "v${getAppVersionName()}"

        // Initial setup buttons
        btnTestRootAccess = findViewById(R.id.btnTestRootAccess)
        btnAccessibilitySetup = findViewById(R.id.btnAccessibilitySetup)

        // Toast + theme
        checkToastQuick = findViewById(R.id.checkToastQuick)
        checkToastDefault = findViewById(R.id.checkToastDefault)
        checkToastHoldKey = findViewById(R.id.checkToastHoldKey)
        checkModeStatusNotification = findViewById(R.id.checkModeStatusNotification)
        spinnerTheme = findViewById(R.id.spinnerTheme)

        // Quick Toggle settings
        checkQuickToggleMouse = findViewById(R.id.checkQuickToggleMouse)
        checkQuickToggleKeyboard = findViewById(R.id.checkQuickToggleKeyboard)
        checkQuickToggleScroll = findViewById(R.id.checkQuickToggleScroll)
        checkQuickToggleScroll2 = findViewById(R.id.checkQuickToggleScroll2)
        tvQuickToggleFallbackLabel = findViewById(R.id.tvQuickToggleFallbackLabel)
        spinnerQuickToggleFallback = findViewById(R.id.spinnerQuickToggleFallback)

        // Scroll
        spinnerScrollSensitivity = findViewById(R.id.spinnerScrollSensitivity)
        spinnerCursorSensitivity = findViewById(R.id.spinnerCursorSensitivity)
        spinnerScrollMode2Sensitivity = findViewById(R.id.spinnerScrollMode2Sensitivity)
        checkTrackpadPressModeSwitch = findViewById(R.id.checkTrackpadPressModeSwitch)
        checkScrollHorizontal = findViewById(R.id.checkScrollHorizontal)
        checkScrollInvertVertical = findViewById(R.id.checkScrollInvertVertical)
        checkScrollInvertHorizontal = findViewById(R.id.checkScrollInvertHorizontal)

        // Backup/restore
        btnBackupSettings = findViewById(R.id.btnBackupSettings)
        btnRestoreSettings = findViewById(R.id.btnRestoreSettings)

        // Updates
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        tvViewOnGitHub = findViewById(R.id.tvViewOnGitHub)

        setupUpdateChecker()
        setupViewOnGitHubLink()

        setupInitialSetupSection()

        setupToasts()
        setupQuickToggleSettings()
        setupThemeSpinner()
        setupBackupRestore()
        setupGlobalScrollSettings()
    }

    override fun onResume() {
        super.onResume()
        refreshUiFromPrefs()
        updateAccessibilitySetupButton()

        // If user is in the middle of the step-by-step wizard, continue it here too.
        maybeContinueAccessibilityWizard()
    }

    private fun refreshUiFromPrefs() {
        if (!::prefs.isInitialized) return

        suppressUiListeners = true
        try {
            // Toasts
            checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()
            checkToastDefault.isChecked = prefs.isToastDefaultModeEnabled()
            checkToastHoldKey.isChecked = prefs.isToastHoldKeyEnabled()
            checkModeStatusNotification.isChecked = prefs.isModeStatusNotificationEnabled()

            // Quick Toggle global modes
            val qt = prefs.getGlobalQuickToggleModes()
            checkQuickToggleMouse.isChecked = qt.contains(Mode.MOUSE)
            checkQuickToggleKeyboard.isChecked = qt.contains(Mode.KEYBOARD)
            checkQuickToggleScroll.isChecked = qt.contains(Mode.SCROLL_WHEEL)
            checkQuickToggleScroll2.isChecked = qt.contains(Mode.SCROLL_MODE_2)

            // Quick Toggle fallback
            val fallback = prefs.getGlobalQuickToggleSingleMatchFallbackMode()
            val fbIdx = quickToggleFallbackOptions.indexOf(fallback).coerceAtLeast(0)
            if (spinnerQuickToggleFallback.selectedItemPosition != fbIdx) {
                spinnerQuickToggleFallback.setSelection(fbIdx, false)
            }
            updateQuickToggleFallbackEnabledState(qt.size)

            // Theme
            val currentTheme = prefs.getThemePref()
            val themeIdx = themeOptions.indexOf(currentTheme).coerceAtLeast(0)
            if (spinnerTheme.selectedItemPosition != themeIdx) {
                spinnerTheme.setSelection(themeIdx, false)
            }

            // Scroll
            val scroll = prefs.getGlobalScrollSettings()
            val scrollIdx = scrollSensOptions.indexOf(scroll.sensitivity).coerceAtLeast(0)
            if (spinnerScrollSensitivity.selectedItemPosition != scrollIdx) {
                spinnerScrollSensitivity.setSelection(scrollIdx, false)
            }
            val cursorIdx = cursorSensOptions.indexOf(prefs.getGlobalCursorSensitivity()).coerceAtLeast(0)
            if (spinnerCursorSensitivity.selectedItemPosition != cursorIdx) {
                spinnerCursorSensitivity.setSelection(cursorIdx, false)
            }
            val scrollMode2Idx = scrollSensOptions.indexOf(prefs.getGlobalScrollMode2Sensitivity()).coerceAtLeast(0)
            if (spinnerScrollMode2Sensitivity.selectedItemPosition != scrollMode2Idx) {
                spinnerScrollMode2Sensitivity.setSelection(scrollMode2Idx, false)
            }
            checkTrackpadPressModeSwitch.isChecked = prefs.isTrackpadPressModeSwitchEnabled()
            checkScrollHorizontal.isChecked = scroll.horizontalEnabled
            checkScrollInvertVertical.isChecked = scroll.invertVertical
            checkScrollInvertHorizontal.isChecked = scroll.invertHorizontal

        } finally {
            suppressUiListeners = false
        }
    }

    // ---------- Initial Setup section ----------

    private fun setupInitialSetupSection() {
        btnTestRootAccess.setOnClickListener { runRootTestInteractive() }

        btnAccessibilitySetup.setOnClickListener {
            // If not enabled, start the 3-step wizard.
            if (!isAccessibilityServiceEnabled()) {
                beginAccessibilityWizard()
            }
        }

        updateAccessibilitySetupButton()
    }

    private fun updateAccessibilitySetupButton() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            btnAccessibilitySetup.isEnabled = false
            btnAccessibilitySetup.text = "Accessibility Service enabled ✅"
            btnAccessibilitySetup.alpha = 0.7f
        } else {
            btnAccessibilitySetup.isEnabled = true
            btnAccessibilitySetup.text = "Enable Accessibility Service"
            btnAccessibilitySetup.alpha = 1.0f
        }
    }

    // ---------- Accessibility step-by-step wizard (shared with MainActivity) ----------

    private fun getA11yWizardStep(): Int {
        val sp = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        return sp.getInt(KEY_A11Y_WIZARD_STEP, 0)
    }

    private fun setA11yWizardStep(step: Int) {
        val sp = getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        sp.edit().putInt(KEY_A11Y_WIZARD_STEP, step).apply()
    }

    private fun cancelAccessibilityWizard() {
        setA11yWizardStep(0)
    }

    private fun beginAccessibilityWizard() {
        setA11yWizardStep(1)
        showAccessibilityWizardStep1()
    }

    private fun maybeContinueAccessibilityWizard(): Boolean {
        if (wizardDialogShowing) return true

        val step = getA11yWizardStep()
        if (step == 0) return false

        if (isAccessibilityServiceEnabled()) {
            cancelAccessibilityWizard()
            return false
        }

        when (step) {
            1 -> showAccessibilityWizardStep1()
            2 -> showAccessibilityWizardStep2()
            3 -> showAccessibilityWizardStep3()
            4 -> showAccessibilityWizardStillNotEnabled()
            else -> cancelAccessibilityWizard()
        }
        return true
    }

    private fun showAccessibilityWizardStep1() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 1 of 3\n\n" +
                    "• Open Accessibility settings\n" +
                    "• Tap “Q25 Trackpad Customizer” (will be grayed out)\n" +
                    "• You’ll see a “Restricted setting” message\n" +
                    "• Come back here (just hit Back)\n\n" +
                    "Already saw the restricted message? You can jump to App info."

        showScrollableDialog(
            title = "Enable Accessibility",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(2)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStep2() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 2 of 3\n\n" +
                    "Now allow restricted settings for this app:\n" +
                    "• In App info (Next screen), tap the ⋮ menu (top-right)\n" +
                    "• Tap “Allow restricted settings”\n" +
                    "• Enter your PIN/passcode, then return here (Press Back button)\n\n" +
                    "Don’t see the ⋮ menu? You probably missed step 1 - go back and tap the app in Accessibility first."

        showScrollableDialog(
            title = "Allow restricted settings",
            message = msg,
            positiveText = "Open App Info",
            onPositive = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            neutralText = "Back to Accessibility",
            onNeutral = {
                setA11yWizardStep(2)
                openAccessibilitySettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStep3() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "Step 3 of 3\n\n" +
                    "Go back to Accessibility and enable the service:\n" +
                    "Settings > Accessibility > Q25 Trackpad Customizer > Enable\n\n" +
                    "Still grayed out? Go back to App info and double-check “Allow restricted settings” is enabled."

        showScrollableDialog(
            title = "Enable the service",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(4)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showAccessibilityWizardStillNotEnabled() {
        if (wizardDialogShowing) return
        wizardDialogShowing = true

        val msg =
            "It looks like the Accessibility service is still OFF.\n\n" +
                    "Go back and make sure “Q25 Trackpad Customizer” is enabled in Accessibility.\n\n" +
                    "If it’s still grayed out, re-check App info > ⋮ > Allow restricted settings."

        showScrollableDialog(
            title = "Almost there",
            message = msg,
            positiveText = "Open Accessibility Settings",
            onPositive = {
                setA11yWizardStep(4)
                openAccessibilitySettings()
            },
            neutralText = "Open App Info",
            onNeutral = {
                setA11yWizardStep(3)
                openAppDetailsSettings()
            },
            negativeText = "Not now",
            onNegative = { cancelAccessibilityWizard() }
        ) {
            wizardDialogShowing = false
        }
    }

    private fun showScrollableDialog(
        title: String,
        message: String,
        positiveText: String,
        onPositive: (() -> Unit)?,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        negativeText: String = "Close",
        onNegative: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(6))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tv = TextView(this).apply {
            text = message
            textSize = 14f
        }

        container.addView(tv)
        scroll.addView(container)

        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(positiveText) { d, _ ->
                d.dismiss()
                onPositive?.invoke()
            }
            .setNegativeButton(negativeText) { d, _ ->
                d.dismiss()
                onNegative?.invoke()
            }
            .setOnDismissListener { onDismiss?.invoke() }

        if (!neutralText.isNullOrBlank()) {
            b.setNeutralButton(neutralText) { d, _ ->
                d.dismiss()
                onNeutral?.invoke()
            }
        }

        b.show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open Accessibility settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val uri = Uri.parse("package:$packageName")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open app settings.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Root test (su -c id) ----------

    private fun runRootTestInteractive() {
        val progress = showSimpleProgressDialog(
            title = "Checking root…",
            message = "Requesting root access (su -c id). If Magisk/KernelSU prompts you, tap Allow/Grant."
        )

        Thread {
            val result = testRootAccessDetailed()
            Handler(Looper.getMainLooper()).post {
                try { progress.dismiss() } catch (_: Exception) {}

                setupPrefs.edit().putBoolean(KEY_ROOT_GRANTED, result.granted).apply()

                val mgr = detectRootManager()
                val mgrLine = when (mgr) {
                    RootManager.MAGISK -> "Magisk detected."
                    RootManager.KERNELSU -> "KernelSU detected."
                    RootManager.UNKNOWN -> "Root manager not detected, or hidden (such as KernelSU)."
                }

                val msg = buildString {
                    append(if (result.granted) "✅ Root access granted.\n\n" else "❌ Root access NOT granted.\n\n")
                    append(mgrLine)
                    append("\n\n")
                    append(
                        when {
                            result.granted -> "You’re good to go."
                            else -> rootTroubleshootingText(mgr)
                        }
                    )
                }

                val otherHelpLabel = when (mgr) {
                    RootManager.MAGISK -> "Using KernelSU instead?"
                    RootManager.KERNELSU -> "Using Magisk instead?"
                    RootManager.UNKNOWN -> "More help"
                }

                showScrollableDialog(
                    title = "Root access",
                    message = msg,
                    positiveText = "OK",
                    onPositive = null,
                    neutralText = otherHelpLabel,
                    onNeutral = { showRootHelpDialog() },
                    negativeText = "Close",
                    onNegative = null
                )
            }
        }.start()
    }

    private data class RootTestResult(
        val granted: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String? = null
    )

    private fun testRootAccessDetailed(): RootTestResult {
        return try {
            val pb = ProcessBuilder("su", "-c", "id")
            val p = pb.start()

            val stdout = p.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = p.errorStream.bufferedReader().use { it.readText() }.trim()
            val exit = p.waitFor()

            val ok = (exit == 0 && stdout.contains("uid=0"))
            RootTestResult(
                granted = ok,
                exitCode = exit,
                stdout = stdout,
                stderr = stderr
            )
        } catch (e: Exception) {
            RootTestResult(
                granted = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                error = e.message
            )
        }
    }

    private enum class RootManager { MAGISK, KERNELSU, UNKNOWN }

    private fun detectRootManager(): RootManager {
        val pm = packageManager

        val hasMagisk =
            isPackageInstalled(pm, "com.topjohnwu.magisk") ||
                    isPackageInstalled(pm, "io.github.vvb2060.magisk")

        val hasKernelSu =
            isPackageInstalled(pm, "me.weishu.kernelsu")

        return when {
            hasKernelSu -> RootManager.KERNELSU
            hasMagisk -> RootManager.MAGISK
            else -> RootManager.UNKNOWN
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun rootTroubleshootingText(mgr: RootManager): String {
        val managerHint = when (mgr) {
            RootManager.MAGISK -> "Open Magisk > Superuser, find this app, and allow it."
            RootManager.KERNELSU -> "Open KernelSU > Superuser, find this app, and allow it."
            RootManager.UNKNOWN -> "Open your root manager’s Superuser list and allow this app."
        }

        return """
If you didn’t see a prompt, or you previously denied it:

• $managerHint
• Then tap “Test / Grant Root Access” again.

(If root is disabled/unavailable, mode switching won’t work.)
""".trim()
    }

    private fun showRootHelpDialog() {
        showScrollableDialog(
            title = "Root help",
            message = """
This app requests root by running:
su -c id

• Magisk: you should get a prompt the first time. If you denied it before, enable it in Magisk > Superuser.
• KernelSU: enable it in KernelSU > Superuser.

If you’re using something else, look for a “Superuser / SU” permission list and allow this app there.
""".trim(),
            positiveText = "OK",
            onPositive = null,
            negativeText = "Close",
            onNegative = null
        )
    }

    private fun showSimpleProgressDialog(title: String, message: String): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val bar = ProgressBar(this).apply { isIndeterminate = true }
        val tv = TextView(this).apply {
            text = message
            setPadding(32, 0, 0, 0)
        }

        container.addView(bar)
        container.addView(tv)

        return AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        if (!enabled) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // Prefer exact service component match, but also allow "packageName/" style entries as fallback.
        val component = ComponentName(this, AppSwitchService::class.java).flattenToString()
        return enabledServices.split(':').any { entry ->
            entry.equals(component, ignoreCase = true) ||
                    entry.startsWith("$packageName/") ||
                    entry.contains(packageName)
        }
    }

    // ---------- Toast ----------

    private fun setupToasts() {
        checkToastQuick.isChecked = prefs.isToastQuickToggleEnabled()
        checkToastDefault.isChecked = prefs.isToastDefaultModeEnabled()
        checkToastHoldKey.isChecked = prefs.isToastHoldKeyEnabled()
        checkModeStatusNotification.isChecked = prefs.isModeStatusNotificationEnabled()

        checkToastQuick.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setToastQuickToggleEnabled(isChecked)
        }
        checkToastDefault.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setToastDefaultModeEnabled(isChecked)
        }
        checkToastHoldKey.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setToastHoldKeyEnabled(isChecked)
        }
        checkModeStatusNotification.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            if (isChecked && !ensureNotificationPermissionForModeStatus()) {
                return@setOnCheckedChangeListener
            }
            prefs.setModeStatusNotificationEnabled(isChecked)
            if (isChecked) {
                val mode = AppState.currentMode ?: prefs.getLastKnownMode() ?: prefs.getSystemDefaultMode()
                NotificationHelper.updateModeStatus(this, mode)
            } else {
                NotificationHelper.clearModeStatus(this)
            }
        }
    }

    private fun ensureNotificationPermissionForModeStatus(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return true
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true
        }

        suppressUiListeners = true
        try {
            checkModeStatusNotification.isChecked = false
        } finally {
            suppressUiListeners = false
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return false
    }

    // ---------- Quick Toggle ----------

    private fun setupQuickToggleSettings() {
        val fbAdapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, quickToggleFallbackLabels)
        fbAdapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerQuickToggleFallback.adapter = fbAdapter

        fun selectedQuickToggleModesFromUi(): Set<Mode> {
            val out = LinkedHashSet<Mode>()
            if (checkQuickToggleMouse.isChecked) out.add(Mode.MOUSE)
            if (checkQuickToggleKeyboard.isChecked) out.add(Mode.KEYBOARD)
            if (checkQuickToggleScroll.isChecked) out.add(Mode.SCROLL_WHEEL)
            if (checkQuickToggleScroll2.isChecked) out.add(Mode.SCROLL_MODE_2)
            return out
        }

        fun applyQuickToggleSelection(maybeRevert: (() -> Unit)? = null) {
            val selected = selectedQuickToggleModesFromUi()
            if (selected.isEmpty()) {
                maybeRevert?.invoke()
                Toast.makeText(this, "Select at least one mode for Quick Toggle.", Toast.LENGTH_SHORT).show()
                return
            }
            prefs.setGlobalQuickToggleModes(selected)
            updateQuickToggleFallbackEnabledState(selected.size)
        }

        val qt = prefs.getGlobalQuickToggleModes()
        suppressUiListeners = true
        try {
            checkQuickToggleMouse.isChecked = qt.contains(Mode.MOUSE)
            checkQuickToggleKeyboard.isChecked = qt.contains(Mode.KEYBOARD)
            checkQuickToggleScroll.isChecked = qt.contains(Mode.SCROLL_WHEEL)
            checkQuickToggleScroll2.isChecked = qt.contains(Mode.SCROLL_MODE_2)

            val fallback = prefs.getGlobalQuickToggleSingleMatchFallbackMode()
            spinnerQuickToggleFallback.setSelection(quickToggleFallbackOptions.indexOf(fallback).coerceAtLeast(0), false)
            updateQuickToggleFallbackEnabledState(qt.size)
        } finally {
            suppressUiListeners = false
        }

        checkQuickToggleMouse.setOnCheckedChangeListener { _, _ ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            applyQuickToggleSelection(maybeRevert = {
                suppressUiListeners = true
                try { checkQuickToggleMouse.isChecked = true } finally { suppressUiListeners = false }
            })
        }
        checkQuickToggleKeyboard.setOnCheckedChangeListener { _, _ ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            applyQuickToggleSelection(maybeRevert = {
                suppressUiListeners = true
                try { checkQuickToggleKeyboard.isChecked = true } finally { suppressUiListeners = false }
            })
        }
        checkQuickToggleScroll.setOnCheckedChangeListener { _, _ ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            applyQuickToggleSelection(maybeRevert = {
                suppressUiListeners = true
                try { checkQuickToggleScroll.isChecked = true } finally { suppressUiListeners = false }
            })
        }
        checkQuickToggleScroll2.setOnCheckedChangeListener { _, _ ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            applyQuickToggleSelection(maybeRevert = {
                suppressUiListeners = true
                try { checkQuickToggleScroll2.isChecked = true } finally { suppressUiListeners = false }
            })
        }

        spinnerQuickToggleFallback.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val enabled = (prefs.getGlobalQuickToggleModes().size == 1)
            if (!enabled) return@setOnItemSelectedListenerSimple
            val selected = quickToggleFallbackOptions[position]
            prefs.setGlobalQuickToggleSingleMatchFallbackMode(selected)
        }
    }

    private fun updateQuickToggleFallbackEnabledState(selectedCount: Int) {
        val enabled = (selectedCount == 1)

        spinnerQuickToggleFallback.isEnabled = enabled
        spinnerQuickToggleFallback.alpha = if (enabled) 1.0f else 0.5f

        tvQuickToggleFallbackLabel.alpha = if (enabled) 1.0f else 0.5f
    }

    // ---------- Theme ----------

    private fun setupThemeSpinner() {
        val adapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, themeLabels)
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerTheme.adapter = adapter

        val currentTheme = prefs.getThemePref()
        val index = themeOptions.indexOf(currentTheme).coerceAtLeast(0)
        spinnerTheme.setSelection(index, false)

        spinnerTheme.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val selectedTheme = themeOptions[position]
            if (selectedTheme != prefs.getThemePref()) {
                prefs.setThemePref(selectedTheme)
                Prefs.applyThemeFromPrefs(this)
                recreate()
            }
        }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        positive: String,
        negative: String,
        onYes: () -> Unit,
        onNo: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .setNegativeButton(negative) { _, _ -> onNo() }
            .setCancelable(false)
            .show()
    }

    // ---------- Global Scroll ----------

    private fun setupGlobalScrollSettings() {
        val adapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, scrollSensLabels)
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerScrollSensitivity.adapter = adapter

        val cursorAdapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, cursorSensLabels)
        cursorAdapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerCursorSensitivity.adapter = cursorAdapter

        val scrollMode2Adapter = ArrayAdapter(this, AndroidR.layout.simple_spinner_item, scrollSensLabels)
        scrollMode2Adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        spinnerScrollMode2Sensitivity.adapter = scrollMode2Adapter

        val current = prefs.getGlobalScrollSettings()
        spinnerScrollSensitivity.setSelection(scrollSensOptions.indexOf(current.sensitivity).coerceAtLeast(0), false)
        spinnerCursorSensitivity.setSelection(cursorSensOptions.indexOf(prefs.getGlobalCursorSensitivity()).coerceAtLeast(0), false)
        spinnerScrollMode2Sensitivity.setSelection(scrollSensOptions.indexOf(prefs.getGlobalScrollMode2Sensitivity()).coerceAtLeast(0), false)

        spinnerScrollSensitivity.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            prefs.setGlobalScrollSensitivity(scrollSensOptions[position])
        }
        spinnerCursorSensitivity.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            val sensitivity = cursorSensOptions[position]
            prefs.setGlobalCursorSensitivity(sensitivity)
            TrackpadController.applyCursorSensitivity(sensitivity)
        }
        spinnerScrollMode2Sensitivity.setOnItemSelectedListenerSimple { position ->
            if (suppressUiListeners) return@setOnItemSelectedListenerSimple
            prefs.setGlobalScrollMode2Sensitivity(scrollSensOptions[position])
        }
        checkTrackpadPressModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setTrackpadPressModeSwitchEnabled(isChecked)
            sendBroadcast(Intent(AppSwitchService.ACTION_REFRESH_HELPER_STATE).apply {
                setPackage(packageName)
                val mode = AppState.currentMode ?: prefs.getLastKnownMode() ?: prefs.getSystemDefaultMode()
                putExtra(AppSwitchService.EXTRA_TARGET_MODE, mode.prefValue)
            })
        }

        checkScrollHorizontal.isChecked = current.horizontalEnabled
        checkScrollInvertVertical.isChecked = current.invertVertical
        checkScrollInvertHorizontal.isChecked = current.invertHorizontal

        checkScrollHorizontal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalScrollHorizontalEnabled(isChecked)
        }
        checkScrollInvertVertical.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalScrollInvertVertical(isChecked)
        }
        checkScrollInvertHorizontal.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUiListeners) return@setOnCheckedChangeListener
            prefs.setGlobalScrollInvertHorizontal(isChecked)
        }
    }

    // ---------- Backup & Restore ----------

    private fun setupBackupRestore() {
        btnBackupSettings.setOnClickListener {
            val name = "q25_settings_backup_${System.currentTimeMillis()}.json"
            backupCreateLauncher.launch(name)
        }

        btnRestoreSettings.setOnClickListener {
            restoreOpenLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun handleBackupCreate(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                val json = SettingsBackup.exportToJson(this)
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Settings backup saved.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to save backup.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleRestoreOpen(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
            if (json.isNullOrBlank()) {
                Toast.makeText(this, "Invalid or empty backup file.", Toast.LENGTH_LONG).show()
                return
            }

            val success = SettingsBackup.restoreFromJson(this, json)
            if (success) {
                Toast.makeText(this, "Backup restored. Restarting settings...", Toast.LENGTH_SHORT).show()
                Prefs.applyThemeFromPrefs(this)
                recreate()
            } else {
                Toast.makeText(this, "Invalid backup file.", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to restore backup.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Updates ----------

    private fun setupUpdateChecker() {
        btnCheckUpdates.setOnClickListener { checkForUpdatesManual() }
    }

    private fun setupViewOnGitHubLink() {
        tvViewOnGitHub.paintFlags = tvViewOnGitHub.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvViewOnGitHub.setOnClickListener { openGitHubReleasePage() }
    }

    private fun openGitHubReleasePage() {
        openInBrowser(UpdateChecker.LATEST_RELEASE_WEB_URL)
    }

    private fun checkForUpdatesManual() {
        val installedRaw = getAppVersionName().ifBlank { "0.0.0" }
        val installedDisplay = UpdateChecker.normalizeForDisplay(installedRaw)

        val progress = showUpdateCheckProgressDialog()

        Thread {
            val result = UpdateChecker.fetchLatestRelease()

            Handler(Looper.getMainLooper()).post {
                try { progress.dismiss() } catch (_: Exception) {}

                when (result) {
                    is UpdateChecker.Result.Error -> {
                        AlertDialog.Builder(this)
                            .setTitle("Update check failed")
                            .setMessage(result.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    is UpdateChecker.Result.Success -> {
                        val latestTag = result.release.tagName
                        val latestDisplay = UpdateChecker.normalizeForDisplay(latestTag)
                        val cmp = UpdateChecker.compareVersions(installedRaw, latestTag)

                        when {
                            cmp > 0 -> {
                                AlertDialog.Builder(this)
                                    .setTitle("You’re ahead of GitHub!")
                                    .setMessage(
                                        "Installed version is newer than latest version.\n\n" +
                                                "Installed: $installedDisplay\n" +
                                                "Latest: $latestDisplay"
                                    )
                                    .setPositiveButton("OK", null)
                                    .setNeutralButton("View on GitHub") { _, _ ->
                                        openInBrowser(result.release.htmlUrl)
                                    }
                                    .show()
                            }
                            cmp == 0 -> {
                                AlertDialog.Builder(this)
                                    .setTitle("No update available")
                                    .setMessage(
                                        "Already on the latest update.\n\n" +
                                                "Installed: $installedDisplay\n" +
                                                "Latest: $latestDisplay"
                                    )
                                    .setPositiveButton("OK", null)
                                    .setNeutralButton("View on GitHub") { _, _ ->
                                        openInBrowser(result.release.htmlUrl)
                                    }
                                    .show()
                            }
                            else -> {
                                val openUrl = result.release.apkUrl ?: result.release.htmlUrl
                                val openLabel = if (result.release.apkUrl != null) "Open APK download" else "Open release page"

                                AlertDialog.Builder(this)
                                    .setTitle("Update available!")
                                    .setMessage(
                                        "An update is available.\n\n" +
                                                "Installed: $installedDisplay\n" +
                                                "Latest: $latestDisplay\n\n" +
                                                "After updating, you will need to re-enable the Accessibility Service!"
                                    )
                                    .setPositiveButton(openLabel) { _, _ -> openInBrowser(openUrl) }
                                    .setNeutralButton("View on GitHub") { _, _ -> openInBrowser(result.release.htmlUrl) }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found to open the link.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to open link.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showUpdateCheckProgressDialog(): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val bar = ProgressBar(this).apply { isIndeterminate = true }

        val tv = TextView(this).apply {
            text = "Comparing installed vs latest release on GitHub."
            setPadding(32, 0, 0, 0)
        }

        container.addView(bar)
        container.addView(tv)

        return AlertDialog.Builder(this)
            .setTitle("Checking for updates…")
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val SETUP_PREFS = "setup_state"
        const val KEY_ROOT_GRANTED = "root_granted"

        // Must match MainActivity wizard prefs/key
        private const val SHARED_PREFS_NAME = "q25_prefs"
        private const val KEY_A11Y_WIZARD_STEP = "a11y_wizard_step_v1"
    }
}
