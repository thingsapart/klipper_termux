package dev.klipper.androidbridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Isolated adapter for Termux's documented, permission-protected RUN_COMMAND API. */
object TermuxRunner {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val PACKAGE = "com.termux"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home"
    private const val SHELL = "/data/data/com.termux/files/usr/bin/bash"
    private const val RUNNER = "$HOME/.local/bin/klipper-android-runner"

    enum class Result { SENT, PERMISSION_REQUIRED, TERMUX_UNAVAILABLE }

    fun invoke(context: Context, command: String): Result {
        require(command in setOf("start", "stop", "restart", "status"))
        return dispatch(context, RUNNER, arrayOf(command))
    }

    fun install(context: Context, installerCommand: String): Result =
        dispatch(context, SHELL, arrayOf("-lc", installerCommand))

    fun configureBridge(context: Context, tokenHex: String, port: Int): Result {
        require(tokenHex.matches(Regex("[0-9a-f]{64}")))
        require(port in 1..65535)
        val config = "$HOME/printer_data/config/bridge.conf"
        val example = "$HOME/printer_data/config/bridge.conf.example"
        val command = "set -eu; mkdir -p '$HOME/printer_data/config'; " +
            "if [ ! -f '$config' ]; then cp '$example' '$config'; fi; " +
            "sed -i -E 's/^token=.*/token=$tokenHex/; s/^port=.*/port=$port/' '$config'"
        return dispatch(context, SHELL, arrayOf("-lc", command))
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun dispatch(context: Context, path: String, arguments: Array<String>): Result {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return Result.PERMISSION_REQUIRED
        }
        val intent = Intent(ACTION).apply {
            setClassName(PACKAGE, SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
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
