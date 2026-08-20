package dev.klipper.androidbridge

object InstallerCommand {
    fun isConfigured(installerUrl: String, repositoryUrl: String): Boolean =
        installerUrl.startsWith("https://") &&
            repositoryUrl.startsWith("https://") &&
            !installerUrl.contains("OWNER/REPOSITORY") &&
            !repositoryUrl.contains("OWNER/REPOSITORY")

    fun create(installerUrl: String, repositoryUrl: String): String =
        createCommand(installerUrl, repositoryUrl, "")

    fun createUpdate(installerUrl: String, repositoryUrl: String): String =
        createCommand(installerUrl, repositoryUrl, " --update")

    private fun createCommand(
        installerUrl: String,
        repositoryUrl: String,
        arguments: String,
    ): String {
        val separator = if (installerUrl.contains('?')) "&" else "?"
        val cacheBustedUrl = "$installerUrl${separator}k4a_refresh="
        // GitHub's mutable raw branch URL can be served from a stale CDN cache.
        // Give every user-initiated run a fresh URL and execute the downloaded
        // file rather than a pipe, so the fetched installer is unambiguous.
        return "pkg install -y curl >/dev/null && " +
            "installer=${'$'}(mktemp) && trap 'rm -f -- \"${'$'}installer\"' EXIT && " +
            "curl -fsSL --retry 3 --retry-delay 1 -H 'Cache-Control: no-cache' " +
            "${shellQuote(cacheBustedUrl)}\"${'$'}(date +%s)\" -o \"${'$'}installer\" && " +
            "K4A_REPOSITORY=${shellQuote(repositoryUrl)} bash \"${'$'}installer\"$arguments"
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
