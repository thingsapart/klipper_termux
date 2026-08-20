package dev.klipper.configurator.ui

interface ConfiguratorHost {
    val label: String
    fun applyBundle(zip: ByteArray): String
    fun rollback(): String
}

object ConfiguratorHostRegistry {
    var host: ConfiguratorHost? = null
}
