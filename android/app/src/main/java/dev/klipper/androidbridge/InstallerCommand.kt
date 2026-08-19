package dev.klipper.androidbridge

object InstallerCommand {
    fun isConfigured(installerUrl: String, repositoryUrl: String): Boolean =
        installerUrl.startsWith("https://") &&
            repositoryUrl.startsWith("https://") &&
            !installerUrl.contains("OWNER/REPOSITORY") &&
            !repositoryUrl.contains("OWNER/REPOSITORY")

    fun create(installerUrl: String, repositoryUrl: String): String =
        "pkg install -y curl && curl -fsSL ${shellQuote(installerUrl)} | " +
            "KAB_REPOSITORY=${shellQuote(repositoryUrl)} bash"

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
