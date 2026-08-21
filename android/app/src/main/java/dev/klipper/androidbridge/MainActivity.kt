package dev.klipper.androidbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import dev.klipper.androidbridge.bridge.BridgeState
import dev.klipper.androidbridge.bridge.DeviceRepository
import dev.klipper.androidbridge.bridge.ExternalWebAddress
import dev.klipper.androidbridge.bridge.MainsailAddress
import dev.klipper.androidbridge.bridge.NetworkAddress
import dev.klipper.androidbridge.bridge.UsbBridgeService
import dev.klipper.androidbridge.bridge.UsbSerialDiscovery
import dev.klipper.androidbridge.bridge.UsbSerialDriverKind
import dev.klipper.androidbridge.bridge.toHex
import dev.klipper.configurator.ui.ConfiguratorActivity
import dev.klipper.configurator.ui.ConfiguratorHost
import dev.klipper.configurator.ui.ConfiguratorHostRegistry
import java.util.UUID
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private lateinit var repository: DeviceRepository
    private lateinit var usbManager: UsbManager
    private lateinit var pairing: TextView
    private lateinit var installerCommandView: TextView
    private lateinit var installerNote: TextView
    private lateinit var devices: LinearLayout
    private lateinit var dashboardPage: View
    private lateinit var mainsailPage: View
    private lateinit var setupPage: View
    private lateinit var settingsPage: View
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var appBar: View
    private lateinit var primaryToggle: ImageButton
    private lateinit var overflowButton: ImageButton
    private lateinit var webProgress: ProgressBar
    private lateinit var mainsailRevealHandle: View
    private lateinit var mainsailContainer: FrameLayout
    private lateinit var mainsailError: View
    private lateinit var mainsailErrorMessage: TextView
    private lateinit var mainsailUrlInput: EditText
    private lateinit var mdnsHostnameInput: EditText
    private lateinit var networkAddressView: TextView
    private lateinit var termuxDownloadUrlInput: EditText
    private lateinit var termuxGithubReleasesUrlInput: EditText
    private lateinit var drawerDashboard: TextView
    private lateinit var drawerMainsail: TextView
    private lateinit var drawerSettings: TextView
    private lateinit var summaryBridgeDot: TextView
    private lateinit var summaryBridgeState: TextView
    private lateinit var summaryTermuxDot: TextView
    private lateinit var summaryTermuxState: TextView
    private lateinit var summaryUsbDot: TextView
    private lateinit var summaryUsbState: TextView
    private lateinit var summaryDataDot: TextView
    private lateinit var summaryDataState: TextView
    private lateinit var statusBridgeSwitch: View
    private lateinit var statusTermuxSwitch: View
    private lateinit var statusUsbSwitch: View
    private lateinit var statusDataSwitch: View
    private lateinit var klipperControlRow: View
    private lateinit var sshControlRow: View
    private lateinit var klipperToggle: Switch
    private lateinit var sshToggle: Switch
    private lateinit var firmwareProfileSpinner: Spinner
    private lateinit var firmwareDestinationSpinner: Spinner
    private lateinit var firmwareBuildStatus: TextView
    private lateinit var firmwareBuildButton: Button
    private var firmwareProfiles: List<FirmwareProfileOption> = emptyList()
    private var firmwareDestinations: List<FirmwareDestinationOption> = emptyList()
    private var firmwareOptionsLoading = false
    private var firmwareBuildRunning = false
    private var firmwareOptionsGeneration = 0
    private var destination = Destination.DASHBOARD
    private var previousPrimary = Destination.DASHBOARD
    private var webView: WebView? = null
    private var pendingWebState: Bundle? = null
    private lateinit var wizardHeaders: List<View>
    private lateinit var wizardBodies: List<View>
    private lateinit var wizardStateViews: List<TextView>
    private lateinit var wizardProgress: TextView
    private var lastNextWizardStep = -2
    private var manuallySelectedWizardStep: Int? = null
    private var mainsailPageFailed = false
    private var installerIsConfigured = false
    private var cachedLanAddress: String? = null
    private var nextLanAddressRefresh = 0L
    private var sshRunning = false
    private var moonrakerRunning = false
    private var mainsailRunning = false
    private var nextRuntimeProbe = 0L
    private var termuxHealthCheckInFlight = false
    private var termuxHealthCheckGeneration = 0
    private val runtimeProbeInFlight = AtomicBoolean(false)
    private val statusExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val hideMainsailAppBar = Runnable { setMainsailAppBarVisible(false) }
    private var mainsailSwipeStartY: Float? = null
    private val previousBytes = mutableMapOf<UUID, Triple<Long, Long, Long>>()
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = DeviceRepository(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        pairing = findViewById(R.id.pairing_command)
        installerCommandView = findViewById(R.id.installer_command)
        installerNote = findViewById(R.id.installer_note)
        devices = findViewById(R.id.device_list)
        dashboardPage = findViewById(R.id.dashboard_page)
        mainsailPage = findViewById(R.id.mainsail_page)
        setupPage = findViewById(R.id.setup_page)
        settingsPage = findViewById(R.id.settings_page)
        drawerLayout = findViewById(R.id.drawer_layout)
        appBar = findViewById(R.id.top_app_bar)
        appBar.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && destination == Destination.MAINSAIL) {
                scheduleMainsailAppBarHide(APP_BAR_VISIBLE_MS)
            }
            false
        }
        primaryToggle = findViewById(R.id.primary_toggle)
        overflowButton = findViewById(R.id.overflow_button)
        webProgress = findViewById(R.id.web_progress)
        mainsailRevealHandle = findViewById(R.id.mainsail_reveal_handle)
        mainsailRevealHandle.setOnClickListener {
            setMainsailAppBarVisible(true)
            scheduleMainsailAppBarHide(APP_BAR_VISIBLE_MS)
        }
        mainsailContainer = findViewById(R.id.mainsail_container)
        mainsailError = findViewById(R.id.mainsail_error)
        mainsailErrorMessage = findViewById(R.id.mainsail_error_message)
        mainsailUrlInput = findViewById(R.id.mainsail_url)
        mdnsHostnameInput = findViewById(R.id.mdns_hostname)
        networkAddressView = findViewById(R.id.network_address)
        termuxDownloadUrlInput = findViewById(R.id.termux_download_url)
        termuxGithubReleasesUrlInput = findViewById(R.id.termux_github_releases_url)
        drawerDashboard = findViewById(R.id.drawer_dashboard)
        drawerMainsail = findViewById(R.id.drawer_mainsail)
        drawerSettings = findViewById(R.id.drawer_settings)
        wizardHeaders = listOf(
            findViewById(R.id.wizard_termux_header),
            findViewById(R.id.wizard_permission_header),
            findViewById(R.id.wizard_install_header),
            findViewById(R.id.wizard_bridge_header),
            findViewById(R.id.wizard_firmware_header),
            findViewById(R.id.wizard_ssh_header),
            findViewById(R.id.wizard_verify_header),
        )
        wizardBodies = listOf(
            findViewById(R.id.wizard_termux_body),
            findViewById(R.id.wizard_permission_body),
            findViewById(R.id.wizard_install_body),
            findViewById(R.id.wizard_bridge_body),
            findViewById(R.id.wizard_firmware_body),
            findViewById(R.id.wizard_ssh_body),
            findViewById(R.id.wizard_verify_body),
        )
        wizardStateViews = listOf(
            findViewById(R.id.wizard_termux_state),
            findViewById(R.id.wizard_permission_state),
            findViewById(R.id.wizard_install_state),
            findViewById(R.id.wizard_bridge_state),
            findViewById(R.id.wizard_firmware_state),
            findViewById(R.id.wizard_ssh_state),
            findViewById(R.id.wizard_verify_state),
        )
        wizardProgress = findViewById(R.id.wizard_progress)
        summaryBridgeDot = findViewById(R.id.summary_bridge_dot)
        summaryBridgeState = findViewById(R.id.summary_bridge_state)
        summaryTermuxDot = findViewById(R.id.summary_termux_dot)
        summaryTermuxState = findViewById(R.id.summary_termux_state)
        summaryUsbDot = findViewById(R.id.summary_usb_dot)
        summaryUsbState = findViewById(R.id.summary_usb_state)
        summaryDataDot = findViewById(R.id.summary_data_dot)
        summaryDataState = findViewById(R.id.summary_data_state)
        statusBridgeSwitch = findViewById(R.id.status_bridge_switch)
        statusTermuxSwitch = findViewById(R.id.status_termux_switch)
        statusUsbSwitch = findViewById(R.id.status_usb_switch)
        statusDataSwitch = findViewById(R.id.status_data_switch)
        klipperControlRow = findViewById(R.id.klipper_control_row)
        sshControlRow = findViewById(R.id.ssh_control_row)
        klipperToggle = findViewById(R.id.klipper_toggle)
        sshToggle = findViewById(R.id.ssh_toggle)
        firmwareProfileSpinner = findViewById(R.id.firmware_profile)
        firmwareDestinationSpinner = findViewById(R.id.firmware_destination)
        firmwareBuildStatus = findViewById(R.id.firmware_build_status)
        firmwareBuildButton = findViewById(R.id.wizard_build_firmware)
        statusBridgeSwitch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (BridgeState.serviceRunning) {
                confirmStopBridge()
            } else {
                startBridge()
            }
        }
        statusTermuxSwitch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (moonrakerRunning || mainsailRunning || sshRunning ||
                BridgeState.snapshots().isNotEmpty()) confirmStopStack() else requestStartEverything()
        }
        statusUsbSwitch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val driver = UsbSerialDiscovery.findAllDrivers(usbManager, repository).firstOrNull()
            when {
                driver == null -> Toast.makeText(this, "No USB serial device attached", Toast.LENGTH_SHORT).show()
                !usbManager.hasPermission(driver.device) -> requestUsbPermission(driver.device)
                else -> Toast.makeText(this, "USB access is ready", Toast.LENGTH_SHORT).show()
            }
        }
        statusDataSwitch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Toast.makeText(this, "Blue indicates traffic within the last 1.5 seconds", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.nav_button).setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        drawerDashboard.setOnClickListener { selectFromDrawer(Destination.DASHBOARD) }
        drawerMainsail.setOnClickListener { selectFromDrawer(Destination.MAINSAIL) }
        drawerSettings.setOnClickListener { selectFromDrawer(Destination.SETTINGS) }
        findViewById<Button>(R.id.open_setup_wizard).setOnClickListener { openSetupWizard() }
        findViewById<Button>(R.id.build_mcu_firmware).setOnClickListener {
            openFirmwareBuilder()
        }
        findViewById<Button>(R.id.open_firmware_builds).setOnClickListener {
            val firmwareUrl = Uri.parse(repository.mainsailUrl()).buildUpon()
                .path("/firmware/")
                .clearQuery()
                .fragment(null)
                .build()
                .toString()
            navigate(Destination.MAINSAIL)
            ensureWebView().loadUrl(firmwareUrl)
        }
        findViewById<Button>(R.id.open_printer_configurator).setOnClickListener {
            ConfiguratorHostRegistry.host = object : ConfiguratorHost {
                override val label = "this phone's Termux"
                override fun applyBundle(zip: ByteArray): String = when (TermuxRunner.applyConfigBundle(this@MainActivity, zip)) {
                    TermuxRunner.Result.SENT -> "Config apply requested; Termux will back up, validate, and restart safely"
                    TermuxRunner.Result.PERMISSION_REQUIRED -> "Grant the Termux command permission, then try again"
                    TermuxRunner.Result.TERMUX_UNAVAILABLE -> "Termux is unavailable or external commands are disabled"
                }
                override fun rollback(): String = when (TermuxRunner.rollbackConfig(this@MainActivity)) {
                    TermuxRunner.Result.SENT -> "Config rollback requested"
                    TermuxRunner.Result.PERMISSION_REQUIRED -> "Grant the Termux command permission, then try again"
                    TermuxRunner.Result.TERMUX_UNAVAILABLE -> "Termux is unavailable or external commands are disabled"
                }
            }
            startActivity(Intent(this, ConfiguratorActivity::class.java))
        }
        wizardHeaders.forEachIndexed { index, header ->
            header.setOnClickListener {
                manuallySelectedWizardStep = index
                lastNextWizardStep = -2
                renderWizard()
            }
        }
        primaryToggle.setOnClickListener { navigate(AppNavigation.toggle(destination)) }
        overflowButton.setOnClickListener { showOverflow(it) }
        mainsailUrlInput.setText(repository.mainsailUrl())
        mdnsHostnameInput.setText(repository.mdnsHostname())
        findViewById<Button>(R.id.save_mainsail_url).setOnClickListener { saveMainsailUrl() }
        findViewById<Button>(R.id.save_mdns_hostname).setOnClickListener { saveMdnsHostname() }
        findViewById<Button>(R.id.open_device_name_settings).setOnClickListener {
            openAndroidDeviceNameSettings()
        }
        networkAddressView.setOnClickListener {
            val port = Uri.parse(repository.mainsailUrl()).port.takeIf { it > 0 } ?: 80
            val address = cachedLanAddress
            val url = if (address != null) "http://$address:$port/" else {
                "http://${repository.mdnsHostname()}.local:$port/"
            }
            copyToClipboard("Mainsail LAN address", url, "Mainsail address copied")
        }
        termuxDownloadUrlInput.setText(repository.termuxDownloadUrl(BuildConfig.TERMUX_DOWNLOAD_URL))
        termuxGithubReleasesUrlInput.setText(
            repository.termuxGithubReleasesUrl(BuildConfig.TERMUX_GITHUB_RELEASES_URL),
        )
        findViewById<Button>(R.id.open_termux_download).setOnClickListener {
            openConfiguredLink(termuxDownloadUrlInput)
        }
        findViewById<Button>(R.id.open_termux_github_releases).setOnClickListener {
            openConfiguredLink(termuxGithubReleasesUrlInput)
        }
        findViewById<Button>(R.id.save_termux_links).setOnClickListener { saveTermuxLinks() }
        findViewById<Button>(R.id.wizard_open_termux_download).setOnClickListener {
            openConfiguredLink(termuxDownloadUrlInput)
        }
        findViewById<Button>(R.id.wizard_open_termux_github).setOnClickListener {
            openConfiguredLink(termuxGithubReleasesUrlInput)
        }
        findViewById<Button>(R.id.wizard_enable_external_apps).setOnClickListener {
            enableExternalTermuxApps()
        }
        findViewById<Button>(R.id.wizard_grant_permission).setOnClickListener {
            if (!TermuxRunner.isInstalled(this)) {
                openConfiguredLink(termuxDownloadUrlInput)
            } else {
                requestPermissions(arrayOf(TermuxRunner.PERMISSION), 11)
            }
        }
        findViewById<Button>(R.id.wizard_send_pairing).setOnClickListener { sendPairingToTermux() }
        findViewById<Button>(R.id.wizard_create_starter_config).setOnClickListener {
            handleTermuxResult(
                TermuxRunner.createStarterConfig(this),
                "Starter printer.cfg request sent",
            )
        }
        findViewById<Button>(R.id.wizard_usb_action).setOnClickListener { prepareUsbBridge() }
        findViewById<Button>(R.id.wizard_refresh_firmware).setOnClickListener {
            refreshFirmwareOptions()
        }
        firmwareBuildButton.setOnClickListener { buildAndExportFirmware() }
        findViewById<Button>(R.id.wizard_skip_firmware).setOnClickListener {
            repository.markFirmwareSetupHandled()
            firmwareBuildStatus.text = "Firmware step marked complete. You can return and build firmware at any time."
            renderWizard()
        }
        findViewById<Button>(R.id.wizard_setup_ssh).setOnClickListener { setupSsh() }
        findViewById<Button>(R.id.wizard_skip_ssh).setOnClickListener {
            repository.markSshSetupHandled()
            renderWizard()
        }
        findViewById<Button>(R.id.wizard_start_stack).setOnClickListener {
            requestStartEverything()
        }
        findViewById<Button>(R.id.wizard_open_mainsail).setOnClickListener {
            navigate(Destination.MAINSAIL)
        }
        findViewById<Button>(R.id.retry_mainsail).setOnClickListener { loadMainsail() }
        findViewById<Button>(R.id.start_stack_from_web).setOnClickListener {
            requestStartEverything { handler.postDelayed({ loadMainsail() }, 1200) }
        }
        findViewById<Button>(R.id.web_open_setup).setOnClickListener { openSetupWizard() }
        findViewById<Button>(R.id.web_open_external).setOnClickListener { openExternal(repository.mainsailUrl()) }
        klipperToggle.setOnClickListener {
            val enabled = klipperToggle.isChecked
            if (enabled) requestStartEverything() else confirmStopStack()
            renderServiceControls()
        }
        sshToggle.setOnClickListener {
            requestSshState(sshToggle.isChecked)
        }
        findViewById<Switch>(R.id.auto_start_ssh).apply {
            isChecked = repository.sshAutoStart()
            setOnClickListener { updateSshAutoStart(this, isChecked) }
        }
        findViewById<Button>(R.id.regenerate_token).setOnClickListener {
            repository.regenerateToken()
            render()
        }
        pairing.setOnClickListener {
            copyToClipboard("Termux bridge config", pairing.text, "Pairing configuration copied")
        }
        val installerCommand = InstallerCommand.create(
            BuildConfig.K4A_INSTALLER_URL,
            BuildConfig.K4A_REPOSITORY_URL,
        )
        installerIsConfigured = InstallerCommand.isConfigured(
            BuildConfig.K4A_INSTALLER_URL,
            BuildConfig.K4A_REPOSITORY_URL,
        )
        findViewById<Button>(R.id.update_klipper).apply {
            isEnabled = installerIsConfigured
            setOnClickListener {
                val updateCommand = InstallerCommand.createUpdate(
                    BuildConfig.K4A_INSTALLER_URL,
                    BuildConfig.K4A_REPOSITORY_URL,
                )
                val result = TermuxRunner.update(this@MainActivity, updateCommand)
                handleTermuxResult(result, "Klipper update started — opening Termux")
                if (result == TermuxRunner.Result.SENT) {
                    handler.postDelayed({
                        if (!TermuxRunner.openApp(this@MainActivity)) {
                            Toast.makeText(
                                this@MainActivity,
                                "Open Termux to view the update",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }, 400)
                }
            }
        }
        installerCommandView.text = installerCommand
        installerCommandView.setOnClickListener {
            copyToClipboard("Termux installer", installerCommand, "Installer command copied")
        }
        findViewById<Button>(R.id.install_termux).apply {
            isEnabled = installerIsConfigured
            setText(if (repository.installerAttempted()) {
                R.string.reinstall_in_termux
            } else {
                R.string.install_in_termux
            })
            setOnClickListener {
                val result = TermuxRunner.install(this@MainActivity, installerCommand)
                if (result == TermuxRunner.Result.SENT) {
                    repository.markInstallerAttempted()
                    handleTermuxResult(result, "Install request sent — opening Termux")
                    // Android 10+ may prevent Termux's service from bringing its own activity
                    // forward. This launch comes directly from the user's tap and also makes
                    // installer output visible if the terminal session is already being created.
                    handler.postDelayed({
                        if (!TermuxRunner.openApp(this@MainActivity)) {
                            Toast.makeText(
                                this@MainActivity,
                                "Open Termux to view the installer",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }, 400)
                } else {
                    handleTermuxResult(result, "Install request sent")
                }
                renderWizard()
            }
        }
        if (!installerIsConfigured) {
            installerNote.text = getString(R.string.installer_not_published)
            installerNote.setTextColor(getColor(R.color.mainsail_warning))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        pendingWebState = savedInstanceState?.getBundle(KEY_WEB_STATE)
        destination = savedInstanceState?.getString(KEY_DESTINATION)
            ?.let { runCatching { Destination.valueOf(it) }.getOrNull() }
            ?: Destination.DASHBOARD
        previousPrimary = savedInstanceState?.getString(KEY_PRIMARY)
            ?.let { runCatching { Destination.valueOf(it) }.getOrNull() }
            ?.takeIf { it != Destination.SETUP && it != Destination.SETTINGS }
            ?: Destination.DASHBOARD
        startBridge()
        navigate(destination, rememberPrimary = false)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        nextRuntimeProbe = 0L
        handler.post(refresh)
        if (destination == Destination.MAINSAIL && appBar.visibility == View.VISIBLE) {
            scheduleMainsailAppBarHide(INITIAL_APP_BAR_DELAY_MS)
        } else if (destination == Destination.MAINSAIL) {
            setMainsailFullscreen(true)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(hideMainsailAppBar)
        webView?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DESTINATION, destination.name)
        outState.putString(KEY_PRIMARY, previousPrimary.name)
        webView?.let { view ->
            outState.putBundle(KEY_WEB_STATE, Bundle().also(view::saveState))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideMainsailAppBar)
        webView?.let {
            mainsailContainer.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
        statusExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Use Android's back dispatcher")
    override fun onBackPressed() {
        when (AppNavigation.backAction(
            drawerLayout.isDrawerOpen(GravityCompat.START),
            destination,
            webView?.canGoBack() == true,
        )) {
            BackAction.CLOSE_DRAWER -> drawerLayout.closeDrawer(GravityCompat.START)
            BackAction.WEB_HISTORY -> webView?.goBack()
            BackAction.DASHBOARD -> navigate(Destination.DASHBOARD)
            BackAction.PRIMARY -> navigate(previousPrimary)
            BackAction.SETTINGS -> navigate(Destination.SETTINGS)
            BackAction.EXIT -> super.onBackPressed()
        }
    }

    private fun startBridge() {
        val intent = Intent(this, UsbBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun runTermux(command: String) {
        handleTermuxResult(TermuxRunner.invoke(this, command), "Termux stack command sent: $command")
        nextRuntimeProbe = 0L
    }

    private fun updateSshAutoStart(button: CompoundButton, enabled: Boolean) {
        button.isEnabled = false
        val result = TermuxRunner.setSshAutoStart(this, enabled) { command ->
            button.isEnabled = true
            if (command.succeeded) {
                repository.setSshAutoStart(enabled)
                Toast.makeText(
                    this,
                    if (enabled) "SSH auto-start enabled" else "SSH auto-start disabled",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                restoreSshAutoStartSwitch(button, !enabled)
                showTermuxCommandFailure(command)
            }
            requestRuntimeProbe()
        }
        if (result != TermuxRunner.Result.SENT) {
            button.isEnabled = true
            restoreSshAutoStartSwitch(button, !enabled)
            handleTermuxResult(result, "")
        }
    }

    private fun restoreSshAutoStartSwitch(button: CompoundButton, enabled: Boolean) {
        button.isChecked = enabled
    }

    private fun requestSshState(enabled: Boolean) {
        sshToggle.isEnabled = false
        val callback: (TermuxRunner.CommandResult) -> Unit = { command ->
            sshToggle.isEnabled = true
            if (command.succeeded) {
                Toast.makeText(
                    this,
                    if (enabled) "SSH started" else "SSH stopped",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                showTermuxCommandFailure(command)
            }
            requestRuntimeProbe()
        }
        val result = if (enabled) {
            TermuxRunner.startSsh(this, repository.sshAutoStart(), callback)
        } else {
            TermuxRunner.stopSsh(this, callback)
        }
        if (result != TermuxRunner.Result.SENT) {
            sshToggle.isEnabled = true
            if (result == TermuxRunner.Result.TERMUX_UNAVAILABLE) {
                showStartTermuxForSshPrompt(enabled)
            } else {
                handleTermuxResult(result, "")
            }
            requestRuntimeProbe()
        }
    }

    private fun requestRuntimeProbe() {
        nextRuntimeProbe = 0L
        refreshRuntimeStatus(System.currentTimeMillis(), force = true)
        handler.postDelayed({
            nextRuntimeProbe = 0L
            refreshRuntimeStatus(System.currentTimeMillis(), force = true)
        }, 500)
    }

    private fun showTermuxCommandFailure(command: TermuxRunner.CommandResult) {
        val detail = command.stderr.ifBlank { command.errorMessage }.trim()
        val outdated = detail.contains("klctl") &&
            (detail.contains("No such file") || detail.contains("not found"))
        val message = if (outdated) {
            "K4A tools are out of date. Use Update Klipper in Settings."
        } else {
            detail.ifBlank { "Termux command failed (exit ${command.exitCode})" }
        }
        Toast.makeText(this, message.take(240), Toast.LENGTH_LONG).show()
    }

    private fun requestStartEverything(afterStart: () -> Unit = {}) {
        if (!TermuxRunner.isInstalled(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.termux_required_title)
                .setMessage(R.string.termux_required_description)
                .setPositiveButton(R.string.open_setup) { _, _ -> openSetupWizard() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val termuxDetected = moonrakerRunning || mainsailRunning || sshRunning ||
            BridgeState.snapshots().isNotEmpty()
        if (termuxDetected) {
            startEverything(afterStart)
            return
        }
        checkTermuxCommandChannel(afterStart)
    }

    private fun checkTermuxCommandChannel(afterStart: () -> Unit) {
        if (termuxHealthCheckInFlight) {
            Toast.makeText(this, "Checking Termux…", Toast.LENGTH_SHORT).show()
            return
        }
        termuxHealthCheckInFlight = true
        val generation = ++termuxHealthCheckGeneration
        val dispatchResult = TermuxRunner.healthCheck(this) { commandResult ->
            if (generation != termuxHealthCheckGeneration) return@healthCheck
            termuxHealthCheckInFlight = false
            if (commandResult.healthCheckSucceeded) {
                startEverything(afterStart)
            } else {
                showStartTermuxPrompt(afterStart)
            }
        }
        if (dispatchResult != TermuxRunner.Result.SENT) {
            termuxHealthCheckInFlight = false
            if (dispatchResult == TermuxRunner.Result.TERMUX_UNAVAILABLE) {
                showStartTermuxPrompt(afterStart)
            } else {
                handleTermuxResult(dispatchResult, "Termux is available")
            }
            return
        }
        Toast.makeText(this, "Checking Termux…", Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            if (generation == termuxHealthCheckGeneration && termuxHealthCheckInFlight) {
                termuxHealthCheckInFlight = false
                termuxHealthCheckGeneration++
                showStartTermuxPrompt(afterStart)
            }
        }, 3_000)
    }

    private fun showStartTermuxPrompt(afterStart: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.start_termux_title)
            .setMessage(R.string.start_termux_description)
            .setPositiveButton(R.string.start_termux_action) { _, _ ->
                startEverything(afterStart, bringTermuxForward = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStartTermuxForSshPrompt(enabled: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(R.string.start_termux_ssh_title)
            .setMessage(R.string.start_termux_ssh_description)
            .setPositiveButton(R.string.start_termux_action) { _, _ ->
                if (!TermuxRunner.openApp(this)) {
                    Toast.makeText(this, "Unable to open Termux", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                handler.postDelayed({
                    requestSshState(enabled)
                }, 400)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startEverything(
        afterStart: () -> Unit = {},
        bringTermuxForward: Boolean = false,
    ) {
        startBridge()
        if (bringTermuxForward) TermuxRunner.openApp(this)
        handler.postDelayed({
            runTermux("start")
            afterStart()
        }, if (bringTermuxForward) 400L else 0L)
    }

    private fun handleTermuxResult(result: TermuxRunner.Result, sentMessage: String) {
        when (result) {
            TermuxRunner.Result.SENT -> Toast.makeText(
                this, sentMessage, Toast.LENGTH_SHORT,
            ).show()
            TermuxRunner.Result.PERMISSION_REQUIRED -> {
                requestPermissions(arrayOf(TermuxRunner.PERMISSION), 11)
                Toast.makeText(
                    this,
                    "Grant Termux command permission, then tap again",
                    Toast.LENGTH_LONG,
                ).show()
            }
            TermuxRunner.Result.TERMUX_UNAVAILABLE -> Toast.makeText(
                this,
                "Termux is unavailable or external commands are disabled",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun configureTermuxBridge(showFeedback: Boolean) {
        val result = TermuxRunner.configureBridge(
            this,
            repository.token().toHex(),
            repository.port(),
        )
        if (result == TermuxRunner.Result.SENT) {
            repository.markPairingSent()
        }
        if (showFeedback) {
            handleTermuxResult(
                result,
                "Bridge configured to use the first available USB serial device",
            )
            renderWizard()
        }
    }

    private fun sendPairingToTermux() {
        configureTermuxBridge(showFeedback = true)
    }

    private fun enableExternalTermuxApps() {
        if (!TermuxRunner.isInstalled(this)) {
            openConfiguredLink(termuxDownloadUrlInput)
            return
        }
        copyToClipboard(
            "Enable Termux external apps",
            TermuxRunner.ENABLE_EXTERNAL_APPS_COMMAND,
            "Command copied — paste it in Termux and press Enter",
        )
        repository.markExternalAppsSetupAttempted()
        renderWizard()
        if (!TermuxRunner.openApp(this)) {
            Toast.makeText(this, "Open Termux and paste the copied command", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupSsh() {
        val result = TermuxRunner.setupSsh(this)
        if (result == TermuxRunner.Result.SENT) {
            repository.markSshSetupHandled()
            repository.setSshAutoStart(true)
            handleTermuxResult(result, "SSH setup started — opening Termux")
            handler.postDelayed({
                if (!TermuxRunner.openApp(this)) {
                    Toast.makeText(this, "Open Termux to finish SSH setup", Toast.LENGTH_LONG).show()
                }
            }, 400)
        } else {
            handleTermuxResult(result, "SSH setup requested")
        }
        renderWizard()
    }

    private fun prepareUsbBridge() {
        val driver = UsbSerialDiscovery.findAllDrivers(usbManager, repository).firstOrNull()
        when {
            driver == null -> Toast.makeText(
                this,
                "Connect the printer through USB OTG, then try again",
                Toast.LENGTH_LONG,
            ).show()
            !usbManager.hasPermission(driver.device) -> requestUsbPermission(driver.device)
            else -> {
                repository.profileFor(driver.device, driver.ports.first().portNumber, create = true)
                startBridge()
                Toast.makeText(this, "USB is ready; starting the bridge", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshFirmwareOptions() {
        if (firmwareOptionsLoading || firmwareBuildRunning) return
        firmwareOptionsLoading = true
        firmwareBuildStatus.text = getString(R.string.firmware_options_loading)
        firmwareBuildButton.isEnabled = false
        val generation = ++firmwareOptionsGeneration
        val remaining = AtomicInteger(2)
        val finished = {
            if (generation == firmwareOptionsGeneration && remaining.decrementAndGet() == 0) {
                firmwareOptionsLoading = false
                updateFirmwareAdapters()
                firmwareBuildStatus.text = when {
                    firmwareProfiles.isEmpty() -> "No firmware profiles were returned. Run UPDATE first."
                    firmwareDestinations.isEmpty() -> "No export methods were returned. Run UPDATE first."
                    else -> "${firmwareProfiles.size} board profiles · ${firmwareDestinations.size} export methods"
                }
                firmwareBuildButton.isEnabled = firmwareProfiles.isNotEmpty() &&
                    firmwareDestinations.isNotEmpty()
            }
        }
        val profileDispatch = TermuxRunner.firmwareProfiles(this) { result ->
            runOnUiThread {
                if (generation != firmwareOptionsGeneration) return@runOnUiThread
                if (result.succeeded) {
                    firmwareProfiles = FirmwareCommandOutput.profiles(result.stdout)
                } else {
                    firmwareBuildStatus.text = firmwareResultMessage("Could not read firmware profiles", result)
                }
                finished()
            }
        }
        if (profileDispatch != TermuxRunner.Result.SENT) {
            firmwareOptionsLoading = false
            firmwareBuildButton.isEnabled = false
            handleTermuxResult(profileDispatch, "Firmware profiles requested")
            return
        }
        val destinationDispatch = TermuxRunner.firmwareDestinations(this) { result ->
            runOnUiThread {
                if (generation != firmwareOptionsGeneration) return@runOnUiThread
                if (result.succeeded) {
                    firmwareDestinations = FirmwareCommandOutput.destinations(result.stdout)
                } else {
                    firmwareBuildStatus.text = firmwareResultMessage("Could not detect export destinations", result)
                }
                finished()
            }
        }
        if (destinationDispatch != TermuxRunner.Result.SENT) {
            firmwareOptionsGeneration++
            firmwareOptionsLoading = false
            firmwareBuildButton.isEnabled = false
            handleTermuxResult(destinationDispatch, "Firmware storage scan requested")
        }
    }

    private fun openFirmwareBuilder() {
        manuallySelectedWizardStep = FIRMWARE_WIZARD_STEP
        lastNextWizardStep = -2
        navigate(Destination.SETUP)
        if (!firmwareOptionsLoading && !firmwareBuildRunning) refreshFirmwareOptions()
    }

    private fun openSetupWizard() {
        manuallySelectedWizardStep = null
        lastNextWizardStep = -2
        navigate(Destination.SETUP)
    }

    private fun updateFirmwareAdapters() {
        val selectedProfile = (firmwareProfileSpinner.selectedItem as? FirmwareProfileOption)?.id
        val selectedDestination =
            (firmwareDestinationSpinner.selectedItem as? FirmwareDestinationOption)?.id
        firmwareProfileSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, firmwareProfiles,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        firmwareDestinationSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, firmwareDestinations,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        firmwareProfiles.indexOfFirst { it.id == selectedProfile }.takeIf { it >= 0 }?.let {
            firmwareProfileSpinner.setSelection(it)
        }
        firmwareDestinations.indexOfFirst { it.id == selectedDestination }.takeIf { it >= 0 }?.let {
            firmwareDestinationSpinner.setSelection(it)
        }
    }

    private fun buildAndExportFirmware() {
        if (firmwareBuildRunning) return
        val profile = firmwareProfileSpinner.selectedItem as? FirmwareProfileOption ?: run {
            Toast.makeText(this, "Refresh firmware profiles first", Toast.LENGTH_LONG).show()
            return
        }
        val destination = firmwareDestinationSpinner.selectedItem as? FirmwareDestinationOption ?: run {
            Toast.makeText(this, "Select an export method", Toast.LENGTH_LONG).show()
            return
        }
        if (!destination.writable) {
            firmwareBuildStatus.text = "${destination.label} is visible but not writable from Termux. Choose web download or Android share."
            return
        }
        firmwareBuildRunning = true
        firmwareProfileSpinner.isEnabled = false
        firmwareDestinationSpinner.isEnabled = false
        firmwareBuildButton.isEnabled = false
        firmwareBuildButton.text = "Building…"
        firmwareBuildStatus.text = "Building ${profile.board} ${profile.revision}. The first build may download a compiler."
        val exportTarget = when (destination.id) {
            "web", "share" -> destination.id
            else -> destination.argument
        }
        val dispatch = TermuxRunner.buildAndExportFirmware(this, profile.id, exportTarget)
        if (dispatch == TermuxRunner.Result.SENT) {
            repository.markFirmwareSetupHandled()
            finishFirmwareAction(
                "Firmware build and ${destination.label} export started in Termux. Return here when it finishes.",
            )
            // Match the installer/update flow: Android 10+ commonly prevents the
            // RunCommandService from bringing its terminal activity forward itself.
            // This launch is directly attributable to the user's build-button tap.
            handler.postDelayed({
                if (!TermuxRunner.openApp(this)) {
                    Toast.makeText(
                        this,
                        "Open Termux to view the firmware build",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }, 400)
        } else {
            finishFirmwareAction("Could not start the firmware build")
            handleTermuxResult(dispatch, "Firmware build requested")
        }
    }

    private fun handleFirmwareBuildResult(
        destination: FirmwareDestinationOption,
        result: TermuxRunner.CommandResult,
    ) {
        if (!result.succeeded) {
            finishFirmwareAction(firmwareResultMessage("Firmware build failed", result))
            return
        }
        val buildId = FirmwareCommandOutput.buildId(result.stdout)
        if (buildId == null) {
            finishFirmwareAction("Firmware built, but Termux returned no valid build ID. Open Firmware downloads to inspect it.")
            return
        }
        repository.markFirmwareSetupHandled()
        renderWizard()
        when (destination.id) {
            "web" -> finishFirmwareAction("Build complete: $buildId. It is ready under Firmware downloads.")
            "share" -> dispatchFirmwareShare(buildId)
            else -> dispatchFirmwareExport(buildId, destination)
        }
    }

    private fun dispatchFirmwareExport(buildId: String, destination: FirmwareDestinationOption) {
        firmwareBuildButton.text = "Exporting…"
        firmwareBuildStatus.text = "Build complete. Exporting and verifying ${destination.label}…"
        val dispatch = TermuxRunner.exportFirmware(this, buildId, destination.argument) { result ->
            runOnUiThread {
                finishFirmwareAction(if (result.succeeded) {
                    result.stdout.trim().ifEmpty { "Firmware exported to ${destination.label}" }
                } else firmwareResultMessage("Firmware was built, but export failed", result))
            }
        }
        if (dispatch != TermuxRunner.Result.SENT) {
            finishFirmwareAction("Firmware was built, but Termux could not start the export")
            handleTermuxResult(dispatch, "Firmware export requested")
        }
    }

    private fun dispatchFirmwareShare(buildId: String) {
        firmwareBuildButton.text = "Opening share…"
        firmwareBuildStatus.text = "Build complete. Asking Android to share the firmware…"
        val dispatch = TermuxRunner.shareFirmware(this, buildId) { result ->
            runOnUiThread {
                finishFirmwareAction(if (result.succeeded) {
                    "Build complete: $buildId. Android share requested."
                } else firmwareResultMessage("Firmware was built, but sharing failed", result))
            }
        }
        if (dispatch != TermuxRunner.Result.SENT) {
            finishFirmwareAction("Firmware was built, but Termux could not open Android share")
            handleTermuxResult(dispatch, "Firmware share requested")
        }
    }

    private fun firmwareResultMessage(prefix: String, result: TermuxRunner.CommandResult): String {
        val detail = result.stderr.trim().ifEmpty {
            result.errorMessage.trim().ifEmpty { "exit ${result.exitCode}" }
        }
        return "$prefix: $detail"
    }

    private fun finishFirmwareAction(message: String) {
        firmwareBuildRunning = false
        firmwareProfileSpinner.isEnabled = true
        firmwareDestinationSpinner.isEnabled = true
        firmwareBuildButton.isEnabled = firmwareProfiles.isNotEmpty() && firmwareDestinations.isNotEmpty()
        firmwareBuildButton.setText(R.string.build_and_export_firmware)
        firmwareBuildStatus.text = message
    }

    private enum class WizardStatus { COMPLETE, ATTEMPTED, PENDING }

    private fun renderWizard() {
        if (!::wizardHeaders.isInitialized) return
        val drivers = UsbSerialDiscovery.findAllDrivers(usbManager, repository)
        val bridgeConnected = BridgeState.snapshots().isNotEmpty()
        val usbReady = drivers.any { usbManager.hasPermission(it.device) }
        val mainsailReady = repository.mainsailSeen()
        val statuses = listOf(
            if (TermuxRunner.isInstalled(this)) WizardStatus.COMPLETE else WizardStatus.PENDING,
            when {
                bridgeConnected || mainsailReady -> WizardStatus.COMPLETE
                checkSelfPermission(TermuxRunner.PERMISSION) == PackageManager.PERMISSION_GRANTED &&
                    repository.externalAppsSetupAttempted() -> WizardStatus.ATTEMPTED
                else -> WizardStatus.PENDING
            },
            when {
                bridgeConnected || mainsailReady -> WizardStatus.COMPLETE
                repository.installerAttempted() -> WizardStatus.ATTEMPTED
                else -> WizardStatus.PENDING
            },
            when {
                bridgeConnected -> WizardStatus.COMPLETE
                repository.pairingSent() || usbReady -> WizardStatus.ATTEMPTED
                else -> WizardStatus.PENDING
            },
            if (repository.firmwareSetupHandled()) WizardStatus.COMPLETE else WizardStatus.PENDING,
            if (repository.sshSetupHandled()) WizardStatus.COMPLETE else WizardStatus.PENDING,
            if (mainsailReady) WizardStatus.COMPLETE else WizardStatus.PENDING,
        )
        val firstPending = statuses.indexOfFirst { it == WizardStatus.PENDING }
        val next = if (firstPending >= 0) firstPending else {
            statuses.indexOfFirst { it == WizardStatus.ATTEMPTED }
        }
        val completed = statuses.count { it == WizardStatus.COMPLETE }
        wizardProgress.text = if (completed == statuses.size) {
            "Setup complete · $completed/${statuses.size} steps verified"
        } else {
            "$completed/${statuses.size} steps verified · Step ${next + 1} is next"
        }
        statuses.forEachIndexed { index, status ->
            val isNext = index == next
            wizardHeaders[index].setBackgroundResource(
                when {
                    status == WizardStatus.COMPLETE -> R.drawable.bg_wizard_complete
                    isNext -> R.drawable.bg_wizard_next
                    status == WizardStatus.ATTEMPTED -> R.drawable.bg_wizard_attempted
                    else -> R.drawable.bg_panel_header
                },
            )
            wizardStateViews[index].apply {
                text = when {
                    status == WizardStatus.COMPLETE -> "✓ COMPLETE"
                    status == WizardStatus.ATTEMPTED && isNext -> "ATTEMPTED · NEXT"
                    status == WizardStatus.ATTEMPTED -> "ATTEMPTED"
                    isNext -> "NEXT"
                    else -> "PENDING"
                }
                setTextColor(getColor(
                    when {
                        status == WizardStatus.COMPLETE -> R.color.mainsail_success
                        isNext -> R.color.mainsail_primary
                        status == WizardStatus.ATTEMPTED -> R.color.mainsail_warning
                        else -> R.color.mainsail_text_muted
                    },
                ))
            }
        }
        val displayedStep = manuallySelectedWizardStep ?: next
        if (displayedStep != lastNextWizardStep) {
            wizardBodies.forEachIndexed { index, body ->
                body.visibility = if (index == displayedStep ||
                    (displayedStep == -1 && index == statuses.lastIndex)) {
                    View.VISIBLE
                } else View.GONE
            }
            lastNextWizardStep = displayedStep
        }
        findViewById<Button>(R.id.wizard_grant_permission).isEnabled = TermuxRunner.isInstalled(this)
        findViewById<Button>(R.id.wizard_enable_external_apps).isEnabled = TermuxRunner.isInstalled(this)
        findViewById<Button>(R.id.install_termux).setText(if (repository.installerAttempted()) {
            R.string.reinstall_in_termux
        } else {
            R.string.install_in_termux
        })
        findViewById<Button>(R.id.wizard_send_pairing).isEnabled = repository.installerAttempted() ||
            bridgeConnected || mainsailReady
    }

    private fun selectFromDrawer(selected: Destination) {
        drawerLayout.closeDrawer(GravityCompat.START)
        navigate(selected)
    }

    private fun navigate(selected: Destination, rememberPrimary: Boolean = true) {
        destination = selected
        if (rememberPrimary && selected != Destination.SETUP && selected != Destination.SETTINGS) {
            previousPrimary = selected
        }
        if (selected == Destination.MAINSAIL) ensureWebView()
        dashboardPage.visibility = if (selected == Destination.DASHBOARD) View.VISIBLE else View.GONE
        mainsailPage.visibility = if (selected == Destination.MAINSAIL) View.VISIBLE else View.GONE
        setupPage.visibility = if (selected == Destination.SETUP) View.VISIBLE else View.GONE
        settingsPage.visibility = if (selected == Destination.SETTINGS) View.VISIBLE else View.GONE
        val primaryPage = selected == Destination.DASHBOARD || selected == Destination.MAINSAIL
        primaryToggle.visibility = if (primaryPage) View.VISIBLE else View.GONE
        overflowButton.visibility = if (primaryPage) View.VISIBLE else View.GONE
        primaryToggle.setImageResource(
            if (selected == Destination.MAINSAIL) R.drawable.ic_dashboard else R.drawable.ic_web,
        )
        primaryToggle.contentDescription = getString(
            if (selected == Destination.MAINSAIL) R.string.open_dashboard else R.string.open_mainsail,
        )
        drawerDashboard.isSelected = selected == Destination.DASHBOARD
        drawerMainsail.isSelected = selected == Destination.MAINSAIL
        drawerSettings.isSelected = selected == Destination.SETTINGS || selected == Destination.SETUP
        if (selected == Destination.MAINSAIL) {
            scheduleMainsailAppBarHide(INITIAL_APP_BAR_DELAY_MS)
        } else {
            handler.removeCallbacks(hideMainsailAppBar)
            setMainsailAppBarVisible(true, animate = false)
        }
        if (selected == Destination.SETUP) renderWizard()
        if (selected == Destination.SETUP && firmwareProfiles.isEmpty() && !firmwareOptionsLoading &&
            TermuxRunner.isInstalled(this) &&
            checkSelfPermission(TermuxRunner.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            refreshFirmwareOptions()
        }
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SETUP, 0, R.string.settings)
            if (destination == Destination.MAINSAIL) {
                menu.add(0, MENU_RELOAD, 1, R.string.reload)
                menu.add(0, MENU_BROWSER, 2, R.string.open_in_browser)
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_SETUP -> navigate(Destination.SETTINGS)
                    MENU_RELOAD -> loadMainsail()
                    MENU_BROWSER -> openExternal(repository.mainsailUrl())
                }
                true
            }
            show()
        }
    }

    private fun saveMainsailUrl() {
        runCatching { repository.setMainsailUrl(mainsailUrlInput.text.toString()) }
            .onSuccess { url ->
                mainsailUrlInput.error = null
                mainsailUrlInput.setText(url)
                webView?.clearHistory()
                webView?.loadUrl(url)
                Toast.makeText(this, "Mainsail address saved", Toast.LENGTH_SHORT).show()
            }
            .onFailure { mainsailUrlInput.error = it.message ?: "Invalid loopback URL" }
    }

    private fun saveMdnsHostname() {
        runCatching { repository.setMdnsHostname(mdnsHostnameInput.text.toString()) }
            .onSuccess { hostname ->
                mdnsHostnameInput.error = null
                mdnsHostnameInput.setText(hostname)
                handleTermuxResult(
                    TermuxRunner.configureHostname(this, hostname),
                    "mDNS hostname applied: $hostname.local",
                )
                render()
            }
            .onFailure {
                mdnsHostnameInput.error = it.message ?: "Invalid hostname"
            }
    }

    private fun openAndroidDeviceNameSettings() {
        val actions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                // Some Android builds expose this screen without publishing an SDK constant.
                add("android.settings.DEVICE_NAME_SETTINGS")
            }
            add(Settings.ACTION_DEVICE_INFO_SETTINGS)
            add(Settings.ACTION_SETTINGS)
        }
        val opened = actions.any { action ->
            runCatching { startActivity(Intent(action)) }.isSuccess
        }
        if (!opened) {
            Toast.makeText(
                this,
                R.string.device_name_settings_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun saveTermuxLinks() {
        runCatching {
            repository.setTermuxLinks(
                termuxDownloadUrlInput.text.toString(),
                termuxGithubReleasesUrlInput.text.toString(),
            )
        }.onSuccess { (download, releases) ->
            termuxDownloadUrlInput.error = null
            termuxGithubReleasesUrlInput.error = null
            termuxDownloadUrlInput.setText(download)
            termuxGithubReleasesUrlInput.setText(releases)
            Toast.makeText(this, "Termux download links saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: "Invalid download URL", Toast.LENGTH_LONG).show()
        }
    }

    private fun openConfiguredLink(input: EditText) {
        runCatching { ExternalWebAddress.normalize(input.text.toString()) }
            .onSuccess {
                input.error = null
                openExternal(it)
            }
            .onFailure { input.error = it.message ?: "Invalid HTTPS URL" }
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(this).apply {
            setBackgroundColor(getColor(R.color.mainsail_surface))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(false)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, progress: Int) {
                    webProgress.progress = progress
                    webProgress.visibility = if (
                        destination == Destination.MAINSAIL && progress in 0..99
                    ) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (MainsailAddress.isLoopbackUrl(target)) return false
                    openExternal(target)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    mainsailPageFailed = false
                    mainsailError.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    webProgress.visibility = View.GONE
                    if (!mainsailPageFailed && url != null && MainsailAddress.isLoopbackUrl(url)) {
                        repository.markMainsailSeen()
                        renderWizard()
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        mainsailPageFailed = true
                        showWebError(error.description.toString())
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        mainsailPageFailed = true
                        showWebError("HTTP ${errorResponse.statusCode}")
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    mainsailPageFailed = true
                    showWebError("TLS certificate validation failed")
                }
            }
            setDownloadListener { url, _, _, _, _ -> openExternal(url) }
            setOnTouchListener { _, event ->
                handleMainsailSwipe(event)
                false
            }
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        mainsailContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        webView = view
        val restored = pendingWebState?.let(view::restoreState) != null
        pendingWebState = null
        if (!restored) view.loadUrl(repository.mainsailUrl())
        return view
    }

    private fun handleMainsailSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mainsailSwipeStartY = event.y.takeIf {
                    it <= dp(APP_BAR_REVEAL_EDGE_DP).toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val start = mainsailSwipeStartY ?: return
                if (event.y - start >= dp(APP_BAR_REVEAL_DISTANCE_DP)) {
                    mainsailSwipeStartY = null
                    setMainsailAppBarVisible(true)
                    scheduleMainsailAppBarHide(APP_BAR_VISIBLE_MS)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mainsailSwipeStartY = null
        }
    }

    private fun scheduleMainsailAppBarHide(delayMillis: Long) {
        handler.removeCallbacks(hideMainsailAppBar)
        handler.postDelayed(hideMainsailAppBar, delayMillis)
    }

    private fun setMainsailAppBarVisible(visible: Boolean, animate: Boolean = true) {
        appBar.animate().cancel()
        if (visible) {
            setMainsailFullscreen(false)
            mainsailRevealHandle.visibility = View.GONE
            if (appBar.visibility == View.VISIBLE && appBar.alpha == 1f) return
            appBar.visibility = View.VISIBLE
            if (!animate) {
                appBar.translationY = 0f
                appBar.alpha = 1f
                return
            }
            appBar.translationY = -appBar.height.toFloat().coerceAtLeast(dp(60).toFloat())
            appBar.alpha = 0f
            appBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(APP_BAR_ANIMATION_MS)
                .start()
            return
        }
        if (destination != Destination.MAINSAIL || appBar.visibility != View.VISIBLE) return
        if (!animate) {
            appBar.visibility = View.GONE
            appBar.translationY = 0f
            appBar.alpha = 0f
            mainsailRevealHandle.visibility = View.VISIBLE
            setMainsailFullscreen(true)
            return
        }
        appBar.animate()
            .translationY(-appBar.height.toFloat())
            .alpha(0f)
            .setDuration(APP_BAR_ANIMATION_MS)
            .withEndAction {
                if (destination == Destination.MAINSAIL) {
                    appBar.visibility = View.GONE
                    mainsailRevealHandle.visibility = View.VISIBLE
                    setMainsailFullscreen(true)
                }
                appBar.translationY = 0f
            }
            .start()
    }

    @Suppress("DEPRECATION")
    private fun setMainsailFullscreen(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!fullscreen)
            window.insetsController?.apply {
                if (fullscreen) {
                    systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                } else {
                    show(WindowInsets.Type.systemBars())
                }
            }
            return
        }
        window.decorView.systemUiVisibility = if (fullscreen) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun loadMainsail() {
        mainsailError.visibility = View.GONE
        ensureWebView().loadUrl(repository.mainsailUrl())
    }

    private fun showWebError(message: String) {
        webProgress.visibility = View.GONE
        mainsailErrorMessage.text = message
        mainsailError.visibility = View.VISIBLE
    }

    private fun openExternal(rawUrl: String) {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return
        if (uri.scheme != "http" && uri.scheme != "https") {
            Toast.makeText(this, "Unsupported link", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, "No browser is available", Toast.LENGTH_SHORT).show() }
    }

    private fun copyToClipboard(label: String, value: CharSequence, message: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun confirmStopStack() {
        AlertDialog.Builder(this)
            .setTitle("Stop Klipper?")
            .setMessage("This can interrupt an active print. The Android USB bridge will remain available.")
            .setPositiveButton("Stop") { _, _ -> runTermux("stop") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmStopBridge() {
        AlertDialog.Builder(this)
            .setTitle("Stop USB bridge?")
            .setMessage("This disconnects the printer MCU and can interrupt an active print.")
            .setPositiveButton("Stop bridge") { _, _ ->
                stopService(Intent(this, UsbBridgeService::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render() {
        val snapshots = BridgeState.snapshots().associateBy { it.deviceId }
        val drivers = UsbSerialDiscovery.findAllDrivers(usbManager, repository)
        val supportedDeviceIds = drivers.mapTo(mutableSetOf()) { it.device.deviceId }
        val unsupportedDevices = usbManager.deviceList.values
            .filter { it.deviceId !in supportedDeviceIds }
            .sortedBy { it.deviceId }
        val rawUsbCount = usbManager.deviceList.size
        renderWizard()
        val now = System.currentTimeMillis()
        refreshRuntimeStatus(now)
        renderServiceControls()
        if (now >= nextLanAddressRefresh) {
            cachedLanAddress = NetworkAddress.currentIpv4()
            nextLanAddressRefresh = now + 5_000
        }
        val webPort = Uri.parse(repository.mainsailUrl()).port.takeIf { it > 0 } ?: 80
        networkAddressView.text = buildString {
            append("LAN  ·  ")
            append(cachedLanAddress?.let { "http://$it:$webPort/" } ?: "address unavailable")
            append("\nmDNS  ·  http://${repository.mdnsHostname()}.local:$webPort/")
        }
        val dataActive = snapshots.values.any { now - it.lastActivityMillis < 1500 }
        val termuxDetected = moonrakerRunning || mainsailRunning || sshRunning || snapshots.isNotEmpty()
        statusBridgeSwitch.setBackgroundResource(
            when {
                BridgeState.listenerError != null -> R.drawable.pb86_switch_amber
                BridgeState.serviceRunning -> R.drawable.pb86_switch_green
                else -> R.drawable.pb86_switch_off
            },
        )
        statusTermuxSwitch.setBackgroundResource(
            if (termuxDetected) R.drawable.pb86_switch_green else R.drawable.pb86_switch_off,
        )
        updateSummary(
            summaryBridgeDot,
            summaryBridgeState,
            when {
                BridgeState.listenerError != null -> "Error"
                BridgeState.serviceRunning -> "Running (${snapshots.size})"
                else -> "Stopped"
            },
            when {
                BridgeState.listenerError != null -> R.color.mainsail_error
                BridgeState.serviceRunning -> R.color.mainsail_success
                else -> R.color.mainsail_text_muted
            },
        )
        updateSummary(
            summaryTermuxDot,
            summaryTermuxState,
            if (termuxDetected) "Running" else "Waiting",
            if (termuxDetected) R.color.mainsail_success else R.color.mainsail_text_muted,
        )
        val usbPorts = drivers.sumOf { it.ports.size }
        val usbPermission = drivers.any { usbManager.hasPermission(it.device) }
        statusUsbSwitch.setBackgroundResource(
            when {
                usbPorts == 0 && rawUsbCount > 0 -> R.drawable.pb86_switch_amber
                usbPorts == 0 -> R.drawable.pb86_switch_off
                usbPermission -> R.drawable.pb86_switch_green
                else -> R.drawable.pb86_switch_amber
            },
        )
        updateSummary(
            summaryUsbDot,
            summaryUsbState,
            when {
                usbPorts == 0 && rawUsbCount > 0 -> "Select driver"
                usbPorts == 0 -> "Detached"
                usbPermission -> "$usbPorts ready"
                else -> "Permission"
            },
            when {
                usbPorts == 0 && rawUsbCount > 0 -> R.color.mainsail_warning
                usbPorts == 0 -> R.color.mainsail_text_muted
                usbPermission -> R.color.mainsail_success
                else -> R.color.mainsail_warning
            },
        )
        statusDataSwitch.setBackgroundResource(
            if (dataActive) R.drawable.pb86_switch_blue else R.drawable.pb86_switch_off,
        )
        updateSummary(
            summaryDataDot,
            summaryDataState,
            if (dataActive) "Active" else "Idle",
            if (dataActive) R.color.mainsail_primary else R.color.mainsail_text_muted,
        )
        pairing.text = getString(R.string.pairing_value, repository.token().toHex(), repository.port())
        devices.removeAllViews()
        if (drivers.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(18), dp(16), dp(18))
                setBackgroundResource(R.drawable.bg_card)
                if (unsupportedDevices.isEmpty()) {
                    addView(textView("Android sees no attached USB device.", color = R.color.mainsail_text_secondary))
                    addView(textView("Connect a printer through USB OTG or a powered hub.", 13f, R.color.mainsail_text_muted))
                } else {
                    addView(textView("USB device found — select its serial driver.", color = R.color.mainsail_warning))
                    unsupportedDevices.forEach { device ->
                        val interfaces = (0 until device.interfaceCount).joinToString { index ->
                            val usbInterface = device.getInterface(index)
                            "${usbInterface.interfaceClass}/${usbInterface.interfaceSubclass}"
                        }
                        addView(textView(
                            "VID:PID %04x:%04x · interfaces %s".format(
                                device.vendorId, device.productId,
                                interfaces.ifEmpty { "none" },
                            ),
                            13f,
                            R.color.mainsail_text_secondary,
                        ))
                        addView(Button(this@MainActivity, null, 0, R.style.MainsailButtonPrimary).apply {
                            text = "Select serial driver"
                            setOnClickListener { showSerialDriverPicker(device) }
                        })
                    }
                }
            }
            devices.addView(empty)
            return
        }
        for (driver in drivers) for (port in driver.ports) {
            val profile = repository.profileFor(driver.device, port.portNumber, usbManager.hasPermission(driver.device))
            val snapshot = profile?.let { snapshots[it.id] }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val header = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(13), dp(10), dp(13), dp(10))
                setBackgroundResource(R.drawable.bg_panel_header)
            }
            header.addView(
                textView(profile?.alias ?: "Unconfigured USB device", 17f, R.color.mainsail_text_primary).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            val connectionLabel = when {
                !usbManager.hasPermission(driver.device) -> "USB ACCESS"
                snapshot != null -> "CONNECTED"
                else -> "IDLE"
            }
            header.addView(textView(
                connectionLabel,
                11f,
                when {
                    !usbManager.hasPermission(driver.device) -> R.color.mainsail_warning
                    snapshot != null -> R.color.mainsail_success
                    else -> R.color.mainsail_text_muted
                },
            ).apply {
                setBackgroundResource(
                    if (snapshot != null) R.drawable.bg_status_chip else R.drawable.bg_status_chip_off,
                )
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            card.addView(header)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(14))
                setBackgroundResource(R.drawable.bg_panel_body)
            }
            card.addView(content)
            content.addView(textView(
                "VID:PID %04x:%04x · port %d · %s\nPermission: %s · Bridge: %s".format(
                    driver.device.vendorId, driver.device.productId, port.portNumber,
                    driver.javaClass.simpleName,
                    if (usbManager.hasPermission(driver.device)) "granted" else "required",
                    if (snapshot != null) "connected" else "idle",
                ),
                13f,
                R.color.mainsail_text_secondary,
            ).apply { setPadding(0, dp(8), 0, 0) })
            if (profile != null) content.addView(textView(
                "Device ID  ${profile.id}", 12f, R.color.mainsail_text_muted,
            ))
            if (profile != null) {
                content.addView(Button(this, null, 0, R.style.MainsailButtonSecondary).apply {
                    text = "Rename"
                    setOnClickListener { renameProfile(profile) }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END; topMargin = dp(8) })
            }
            content.addView(Button(this, null, 0, R.style.MainsailButtonSecondary).apply {
                text = "Serial driver"
                setOnClickListener { showSerialDriverPicker(driver.device) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; topMargin = dp(8) })
            if (snapshot != null) {
                val active = System.currentTimeMillis() - snapshot.lastActivityMillis < 1500
                val now = System.currentTimeMillis()
                val prior = previousBytes.put(
                    snapshot.deviceId,
                    Triple(now, snapshot.hostToUsbBytes, snapshot.usbToHostBytes),
                )
                val elapsed = ((prior?.let { now - it.first } ?: 1000L).coerceAtLeast(1L))
                val txRate = prior?.let { (snapshot.hostToUsbBytes - it.second) * 1000 / elapsed } ?: 0
                val rxRate = prior?.let { (snapshot.usbToHostBytes - it.third) * 1000 / elapsed } ?: 0
                content.addView(textView(
                    "${if (active) "●" else "○"}  TX  ${snapshot.hostToUsbBytes} B   ${txRate} B/s\n" +
                        "${if (active) "●" else "○"}  RX  ${snapshot.usbToHostBytes} B   ${rxRate} B/s\n" +
                        "USB writes ${snapshot.usbWrites}  ·  reads ${snapshot.usbReads}  ·  errors ${snapshot.errors}\n" +
                        "Peak write  USB %.1f ms  ·  socket %.1f ms".format(
                            snapshot.maxUsbWriteMicros / 1000.0,
                            snapshot.maxSocketWriteMicros / 1000.0,
                        ),
                    13f,
                    if (active) R.color.mainsail_primary else R.color.mainsail_text_secondary,
                ).apply {
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(10), 0, 0)
                })
            }
            if (!usbManager.hasPermission(driver.device)) {
                content.addView(Button(this, null, 0, R.style.MainsailButtonPrimary).apply {
                    text = "Grant USB access"
                    setOnClickListener { requestUsbPermission(driver.device) }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) })
            }
            val layout = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(4), 0, dp(8)) }
            devices.addView(card, layout)
        }
    }

    private fun renderServiceControls() {
        val klipperRunning = moonrakerRunning || BridgeState.snapshots().isNotEmpty()
        klipperToggle.isChecked = klipperRunning
        sshToggle.isChecked = sshRunning
        klipperControlRow.setBackgroundResource(
            if (klipperRunning) R.drawable.bg_service_running else R.drawable.bg_service_stopped,
        )
        sshControlRow.setBackgroundResource(
            if (sshRunning) R.drawable.bg_service_running else R.drawable.bg_service_stopped,
        )
    }

    private fun refreshRuntimeStatus(now: Long, force: Boolean = false) {
        if ((!force && now < nextRuntimeProbe) ||
            !runtimeProbeInFlight.compareAndSet(false, true)) return
        nextRuntimeProbe = now + 1_000
        statusExecutor.execute {
            val moonraker = isLocalPortOpen(7125)
            val webPort = Uri.parse(repository.mainsailUrl()).port.takeIf { it > 0 } ?: 8080
            val mainsail = isLocalPortOpen(webPort)
            val ssh = isLocalPortOpen(2020)
            handler.post {
                moonrakerRunning = moonraker
                mainsailRunning = mainsail
                sshRunning = ssh
                runtimeProbeInFlight.set(false)
                renderServiceControls()
            }
        }
    }

    private fun isLocalPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 250)
        }
        true
    }.getOrDefault(false)

    private fun updateSummary(dot: TextView, state: TextView, value: String, color: Int) {
        dot.setTextColor(getColor(color))
        state.text = value
        state.setTextColor(getColor(color))
    }

    private fun requestUsbPermission(device: android.hardware.usb.UsbDevice) {
        val intent = Intent("$packageName.USB_PERMISSION").setPackage(packageName)
        val pending = PendingIntent.getBroadcast(this, device.deviceId, intent, UsbBridgeService.pendingIntentFlags())
        usbManager.requestPermission(device, pending)
    }

    private fun showSerialDriverPicker(device: android.hardware.usb.UsbDevice) {
        val kinds = UsbSerialDriverKind.entries
        val labels = arrayOf("Automatic") + kinds.map { it.displayName }
        AlertDialog.Builder(this)
            .setTitle("USB serial driver")
            .setItems(labels) { _, which ->
                val kind = if (which == 0) null else kinds[which - 1]
                repository.setDriverOverride(device, kind)
                val driver = UsbSerialDiscovery.findAllDrivers(usbManager, repository)
                    .firstOrNull { it.device.deviceId == device.deviceId }
                if (driver == null) {
                    Toast.makeText(this, "Automatic detection found no serial driver", Toast.LENGTH_LONG).show()
                } else if (!usbManager.hasPermission(device)) {
                    requestUsbPermission(device)
                } else {
                    Toast.makeText(this, "Using ${kind?.displayName ?: driver.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameProfile(profile: dev.klipper.androidbridge.bridge.DeviceProfile) {
        val input = EditText(this).apply {
            setText(profile.alias)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Device alias")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                runCatching { repository.rename(profile, input.text.toString()) }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun textView(
        value: String,
        size: Float = 14f,
        color: Int = R.color.mainsail_text_secondary,
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val FIRMWARE_WIZARD_STEP = 4
        private const val KEY_DESTINATION = "destination"
        private const val KEY_PRIMARY = "primary_destination"
        private const val KEY_WEB_STATE = "web_state"
        private const val MENU_SETUP = 1
        private const val MENU_RELOAD = 2
        private const val MENU_BROWSER = 3
        private const val INITIAL_APP_BAR_DELAY_MS = 1_500L
        private const val APP_BAR_VISIBLE_MS = 10_000L
        private const val APP_BAR_ANIMATION_MS = 220L
        private const val APP_BAR_REVEAL_EDGE_DP = 48
        private const val APP_BAR_REVEAL_DISTANCE_DP = 44
    }
}
