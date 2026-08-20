package dev.klipper.androidbridge

data class FirmwareProfileOption(
    val id: String,
    val board: String,
    val revision: String,
    val filename: String,
    val delivery: String,
) {
    override fun toString(): String = "$board · $revision"
}

data class FirmwareDestinationOption(
    val id: String,
    val label: String,
    val argument: String,
    val writable: Boolean,
) {
    override fun toString(): String = if (writable) label else "$label · unavailable"
}

object FirmwareCommandOutput {
    private val safeId = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")

    fun profiles(stdout: String): List<FirmwareProfileOption> = stdout.lineSequence().mapNotNull { line ->
        val fields = line.split('|')
        if (fields.size != 5 || !safeId.matches(fields[0])) return@mapNotNull null
        FirmwareProfileOption(fields[0], fields[1], fields[2], fields[3], fields[4])
    }.toList()

    fun destinations(stdout: String): List<FirmwareDestinationOption> = stdout.lineSequence().mapNotNull { line ->
        val fields = line.split('|')
        if (fields.size != 4 || !safeId.matches(fields[0])) return@mapNotNull null
        val argument = fields[2]
        if (argument.isNotEmpty() && argument != "downloads" && argument != "share" &&
            !argument.matches(Regex("^/storage/[A-Za-z0-9._-]+$"))) return@mapNotNull null
        FirmwareDestinationOption(fields[0], fields[1], argument, fields[3] == "1")
    }.toList()

    fun buildId(stdout: String): String? = stdout.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("Build complete: ") }
        ?.removePrefix("Build complete: ")
        ?.takeIf(safeId::matches)
}
