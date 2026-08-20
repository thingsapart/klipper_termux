package dev.klipper.androidbridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64

/** Isolated adapter for Termux's documented, permission-protected RUN_COMMAND API. */
object TermuxRunner {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PACKAGE = "com.termux"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val SHELL = "/data/data/com.termux/files/usr/bin/bash"
    private const val RUNNER = "$HOME/.local/bin/klipper-android-runner"
    private const val KABCTL = "$HOME/.local/bin/kabctl"
    const val ENABLE_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux; touch ~/.termux/termux.properties; " +
            "sed -i -E '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' " +
            "~/.termux/termux.properties; " +
            "printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties; " +
            "termux-reload-settings"

    enum class Result { SENT, PERMISSION_REQUIRED, TERMUX_UNAVAILABLE }

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
            "if [ -x '$KABCTL' ]; then '$KABCTL' printer-starter; " +
            "'$KABCTL' bridge-reload; fi"
        return dispatch(context, SHELL, arrayOf("-lc", command))
    }

    fun createStarterConfig(context: Context): Result =
        dispatch(context, KABCTL, arrayOf("printer-starter"))

    fun applyConfigBundle(context: Context, zip: ByteArray): Result {
        require(zip.size <= 96 * 1024) { "Configuration bundle is too large for Termux command transport" }
        return dispatch(context, KABCTL, arrayOf("config-apply", Base64.encodeToString(zip, Base64.NO_WRAP)))
    }

    fun rollbackConfig(context: Context): Result = dispatch(context, KABCTL, arrayOf("config-rollback"))

    fun setupSsh(context: Context): Result = dispatch(
        context,
        KABCTL,
        arrayOf("ssh-setup"),
        background = false,
        commandLabel = "Set up SSH",
        commandDescription = "Install OpenSSH, choose a password, and listen on port 2020",
    )

    fun configureHostname(context: Context, hostname: String): Result {
        require(hostname.matches(Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")))
        return dispatch(context, KABCTL, arrayOf("hostname", hostname))
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
        }
        return try {
            if (context.startService(intent) == null) Result.TERMUX_UNAVAILABLE else Result.SENT
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
