package dev.klipper.androidbridge

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Isolated adapter for Termux's documented, permission-protected RUN_COMMAND API. */
object TermuxRunner {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PACKAGE = "com.termux"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val PREFIX = "/data/data/com.termux/files/usr"
    private const val SHELL = "/data/data/com.termux/files/usr/bin/bash"
    private const val RUNNER = "$HOME/.local/bin/klipper-android-runner"
    private const val KLCTL = "$HOME/.local/bin/klctl"
    private const val RESULT_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val RESULT_REQUEST_ID = "k4a_result_request_id"
    private val nextRequestId = AtomicInteger(1)
    private val resultCallbacks = ConcurrentHashMap<Int, (CommandResult) -> Unit>()
    const val ENABLE_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux; touch ~/.termux/termux.properties; " +
            "sed -i -E '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' " +
            "~/.termux/termux.properties; " +
            "printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties; " +
            "termux-reload-settings"

    enum class Result { SENT, PERMISSION_REQUIRED, TERMUX_UNAVAILABLE }

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val errorCode: Int,
        val errorMessage: String,
    ) {
        val succeeded: Boolean
            get() = exitCode == 0 && errorCode == Activity.RESULT_OK
        val healthCheckSucceeded: Boolean
            get() = succeeded && stdout.trim() == "K4A_OK"
    }

    fun healthCheck(context: Context, callback: (CommandResult) -> Unit): Result {
        return dispatchForResult(context, "$PREFIX/bin/printf", arrayOf("K4A_OK\\n"), callback)
    }

    private fun dispatchForResult(
        context: Context,
        path: String,
        arguments: Array<String>,
        callback: (CommandResult) -> Unit,
    ): Result {
        val requestId = nextRequestId.getAndIncrement()
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .putExtra(RESULT_REQUEST_ID, requestId)
        val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, requestId, resultIntent, flags)
        resultCallbacks[requestId] = callback
        val result = dispatch(
            context,
            path,
            arguments,
            resultPendingIntent = pendingIntent,
        )
        if (result != Result.SENT) resultCallbacks.remove(requestId)
        return result
    }

    internal fun acceptResult(intent: Intent) {
        val callback = resultCallbacks.remove(intent.getIntExtra(RESULT_REQUEST_ID, -1)) ?: return
        val bundle = intent.getBundleExtra("result")
        callback(CommandResult(
            stdout = bundle?.getString("stdout").orEmpty(),
            stderr = bundle?.getString("stderr").orEmpty(),
            exitCode = bundle?.getInt("exitCode", -1) ?: -1,
            errorCode = bundle?.getInt("err", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED,
            errorMessage = bundle?.getString("errmsg").orEmpty(),
        ))
    }

    fun invoke(context: Context, command: String): Result {
        require(command in setOf("start", "stop", "restart", "status"))
        return dispatch(context, RUNNER, arrayOf(command))
    }

    fun install(context: Context, installerCommand: String): Result =
        dispatch(
            context,
            SHELL,
            arrayOf("-lc", installerCommand),
            background = false,
            commandLabel = "Install Klipper",
            commandDescription = "Install Klipper, Moonraker, Mainsail, and bridge services",
        )

    fun update(context: Context, installerCommand: String): Result =
        dispatch(
            context,
            SHELL,
            arrayOf("-lc", installerCommand),
            background = false,
            commandLabel = "Update Klipper",
            commandDescription = "Update Klipper, Moonraker, Mainsail, and bridge services",
        )

    fun configureBridge(
        context: Context,
        tokenHex: String,
        port: Int,
        deviceUuid: String? = null,
    ): Result {
        require(tokenHex.matches(Regex("[0-9a-f]{64}")))
        require(port in 1..65535)
        require(deviceUuid == null || deviceUuid.matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        ))
        val config = "$HOME/printer_data/config/bridge.conf"
        val example = "$HOME/printer_data/config/bridge.conf.example"
        val device = deviceUuid ?: "auto"
        val command = "set -eu; mkdir -p '$HOME/printer_data/config'; " +
            "if [ ! -f '$config' ]; then cp '$example' '$config'; fi; " +
            "sed -i -E 's/^token=.*/token=$tokenHex/; s/^port=.*/port=$port/; " +
            "s/^device=main,[^,]*/device=main,$device/; " +
            "s|^(device=main,[^,]+,250000,8,1,none,)none,|\\1dtr+rts,|' '$config'; " +
            "if [ -x '$KLCTL' ]; then '$KLCTL' printer-starter; " +
            "'$KLCTL' bridge-reload; fi"
        return dispatch(context, SHELL, arrayOf("-lc", command))
    }

    fun createStarterConfig(context: Context): Result =
        dispatch(context, KLCTL, arrayOf("printer-starter"))

    fun applyConfigBundle(context: Context, zip: ByteArray): Result {
        require(zip.size <= 96 * 1024) { "Configuration bundle is too large for Termux command transport" }
        return dispatch(context, KLCTL, arrayOf("config-apply", Base64.encodeToString(zip, Base64.NO_WRAP)))
    }

    fun rollbackConfig(context: Context): Result = dispatch(context, KLCTL, arrayOf("config-rollback"))

    fun setupSsh(context: Context): Result = dispatch(
        context,
        KLCTL,
        arrayOf("ssh-setup"),
        background = false,
        commandLabel = "Set up SSH",
        commandDescription = "Install OpenSSH, choose a password, and listen on port 2020",
    )

    fun startSsh(
        context: Context,
        autoStart: Boolean,
        callback: (CommandResult) -> Unit,
    ): Result = dispatchForResult(
        context, KLCTL, arrayOf("ssh-start", if (autoStart) "auto" else "once"), callback,
    )

    fun stopSsh(context: Context, callback: (CommandResult) -> Unit): Result =
        dispatchForResult(context, KLCTL, arrayOf("ssh-stop"), callback)

    fun setSshAutoStart(
        context: Context,
        enabled: Boolean,
        callback: (CommandResult) -> Unit,
    ): Result = dispatchForResult(
        context, KLCTL, arrayOf("ssh-autostart", if (enabled) "on" else "off"), callback,
    )

    fun configureHostname(context: Context, hostname: String): Result {
        require(hostname.matches(Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")))
        return dispatch(context, KLCTL, arrayOf("hostname", hostname))
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun openApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun dispatch(
        context: Context,
        path: String,
        arguments: Array<String>,
        background: Boolean = true,
        commandLabel: String? = null,
        commandDescription: String? = null,
        resultPendingIntent: PendingIntent? = null,
    ): Result {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return Result.PERMISSION_REQUIRED
        }
        val intent = Intent(ACTION).apply {
            setClassName(PACKAGE, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            commandLabel?.let { putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", it) }
            commandDescription?.let {
                putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", it)
            }
            resultPendingIntent?.let { putExtra(RESULT_PENDING_INTENT, it) }
        }
        return try {
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            if (component == null) Result.TERMUX_UNAVAILABLE else Result.SENT
        } catch (_: ActivityNotFoundException) {
            Result.TERMUX_UNAVAILABLE
        } catch (_: SecurityException) {
            Result.PERMISSION_REQUIRED
        } catch (_: IllegalStateException) {
            // This should not occur from a visible activity, but Android may reject service
            // launches if the app is invoked indirectly while it is background-restricted.
            Result.TERMUX_UNAVAILABLE
        }
    }
}
