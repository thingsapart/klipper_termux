package dev.klipper.configurator.core

enum class DeploymentTarget(val label: String) { K4A_TERMUX("K4A / Termux"), STANDARD_LINUX("Standard Klipper / Moonraker") }
enum class Kinematics(val configValue: String, val label: String) { CARTESIAN("cartesian", "Cartesian / bedslinger"), COREXY("corexy", "CoreXY"), COREXZ("corexz", "CoreXZ"), DELTA("delta", "Delta") }
enum class DriverKind(val section: String?, val label: String) { STANDALONE(null, "Standalone / legacy"), TMC2209("tmc2209", "TMC2209 UART"), TMC2240("tmc2240", "TMC2240 SPI"), TMC5160("tmc5160", "TMC5160 SPI") }
enum class ProbeKind(val label: String) { NONE("No probe / Z switch"), FIXED("Fixed inductive/capacitive"), BLTOUCH("BLTouch / CR Touch"), KLICKY("Klicky / Euclid detachable"), TAP("Voron Tap / nozzle switch"), BEACON("Beacon"), CARTOGRAPHER("Cartographer"), BTT_EDDY("BTT Eddy") }

data class ConfigProject(
    val schemaVersion: Int = CURRENT_SCHEMA, val name: String = "My Klipper Printer",
    val target: DeploymentTarget = DeploymentTarget.K4A_TERMUX, val mechanicsProfile: String = "Custom",
    val kinematics: Kinematics = Kinematics.CARTESIAN, val bedWidth: Int = 220, val bedDepth: Int = 220,
    val buildHeight: Int = 250, val zMotorCount: Int = 1, val controllerId: String = "btt-skr-mini-e3-v3",
    val mcuSerial: String = K4A_SERIAL, val xyDriver: DriverKind = DriverKind.TMC2209,
    val zDriver: DriverKind = DriverKind.TMC2209, val extruderDriver: DriverKind = DriverKind.TMC2209,
    val probe: ProbeKind = ProbeKind.NONE, val bedMesh: Boolean = false, val adaptiveMesh: Boolean = false,
    val inputShaper: Boolean = false, val filamentSensor: Boolean = false,
    val maxVelocity: Int = 200, val maxAccel: Int = 2000, val xyRotationDistance: Double = 40.0,
    val zRotationDistance: Double = 8.0, val extruderRotationDistance: Double = 33.5,
    val xyRunCurrent: Double = 0.70, val zRunCurrent: Double = 0.70, val extruderRunCurrent: Double = 0.70,
    val hotendSensor: String = "Generic 3950", val bedSensor: String = "Generic 3950",
    val hotendMaxTemp: Int = 260, val bedMaxTemp: Int = 120,
    val probeXOffset: Double = 0.0, val probeYOffset: Double = 0.0, val probeZOffset: Double = 0.0,
    val pinOverrides: Map<String, String> = emptyMap(),
    val selectedPlugins: Set<String> = emptySet(),
) {
    companion object { const val CURRENT_SCHEMA = 1; const val K4A_SERIAL = "/data/data/com.termux/files/usr/var/run/klipper-android/main"; const val LINUX_SERIAL = "/dev/serial/by-id/CHANGE_ME" }
}

data class ControllerBoard(val id: String, val name: String, val exactRevision: String, val pins: Map<String, String>, val uartAddresses: Map<String, Int> = emptyMap())
data class CatalogItem(val id: String, val name: String, val status: String, val width: Int = 220, val depth: Int = 220, val height: Int = 250, val kinematics: Kinematics = Kinematics.CARTESIAN, val zMotors: Int = 1)

object ConfigCatalog {
    val mechanics = listOf(
        CatalogItem("custom", "Custom printer", "Fully editable"), CatalogItem("ender3", "Creality Ender-3 / Pro / V2 mechanics", "Geometry and mechanics"),
        CatalogItem("ender3s1", "Creality Ender-3 S1 mechanics", "Geometry and mechanics"), CatalogItem("sv06", "Sovol SV06 mechanics", "Geometry and mechanics"),
        CatalogItem("voron-v02", "Voron V0.2 mechanics", "Geometry and mechanics"), CatalogItem("voron-trident", "Voron Trident mechanics", "Geometry and mechanics"),
        CatalogItem("voron-v24", "Voron 2.4 mechanics", "Geometry and mechanics", 300, 300, 250, Kinematics.COREXY, 4),
        CatalogItem("voron-switchwire", "Voron Switchwire mechanics", "Geometry and mechanics", 250, 235, 220, Kinematics.COREXZ),
        CatalogItem("rat-rig-vcore3-300", "Rat Rig V-Core 3.1 300", "Geometry and mechanics", 300, 300, 300, Kinematics.COREXY, 3),
        CatalogItem("rat-rig-vcore3-400", "Rat Rig V-Core 3.1 400", "Geometry and mechanics", 400, 400, 400, Kinematics.COREXY, 3),
        CatalogItem("prusa-mk3s", "Original Prusa MK3S mechanics", "Geometry only; review required", 250, 210, 210),
        CatalogItem("prusa-mk4", "Original Prusa MK4 mechanics", "Geometry only; review required", 250, 210, 220),
        CatalogItem("neptune3-pro", "Elegoo Neptune 3 Pro mechanics", "Geometry only; review required", 225, 225, 280),
        CatalogItem("neptune4-pro", "Elegoo Neptune 4 Pro mechanics", "Geometry only; review required", 225, 225, 265),
        CatalogItem("ender3-v3", "Creality Ender-3 V3 mechanics", "Geometry only; review required", 220, 220, 250, Kinematics.COREXZ),
        CatalogItem("ender5", "Creality Ender-5 mechanics", "Geometry only; review required", 220, 220, 300),
        CatalogItem("cr10", "Creality CR-10 mechanics", "Geometry only; review required", 300, 300, 400),
        CatalogItem("sv07", "Sovol SV07 mechanics", "Geometry only; review required", 220, 220, 250),
        CatalogItem("sv08", "Sovol SV08 mechanics", "Geometry only; review required", 350, 350, 345, Kinematics.COREXY, 4),
        CatalogItem("qidi-xmax3", "QIDI X-Max 3 mechanics", "Geometry only; review required", 325, 325, 315, Kinematics.COREXY),
        CatalogItem("qidi-q1pro", "QIDI Q1 Pro mechanics", "Geometry only; review required", 245, 245, 240, Kinematics.COREXY),
        CatalogItem("flashforge-5m", "FlashForge Adventurer 5M mechanics", "Geometry only; review required", 220, 220, 220, Kinematics.COREXY),
        CatalogItem("vcore4", "Rat Rig V-Core 4 mechanics", "Geometry only; review required", 400, 400, 400, Kinematics.COREXY, 3),
        CatalogItem("delta-generic", "Generic delta printer", "Fully editable", 220, 220, 300, Kinematics.DELTA),
        CatalogItem("orca-geometry", "Other OrcaSlicer profile", "Geometry only; review required"),
    )
    val controllers = listOf(
        ControllerBoard("generic", "Generic / manual pin mapping", "manual", emptyMap()),
        ControllerBoard("btt-skr-mini-e3-v3", "BTT SKR Mini E3", "V3.0", mapOf(
            "x_step" to "PB13", "x_dir" to "PB12", "x_enable" to "!PB14", "x_endstop" to "^PC0",
            "y_step" to "PB10", "y_dir" to "PB2", "y_enable" to "!PB11", "y_endstop" to "^PC1",
            "z_step" to "PB0", "z_dir" to "PC5", "z_enable" to "!PB1", "z_endstop" to "^PC2",
            "e_step" to "PB3", "e_dir" to "PB4", "e_enable" to "!PD1", "uart" to "PC11",
            "heater_bed" to "PC8", "heater_hotend" to "PC9", "sensor_bed" to "PC4", "sensor_hotend" to "PA0",
            "fan_part" to "PC6", "fan_hotend" to "PC7", "probe" to "^PC14", "servo" to "PA1",
        ), mapOf("x" to 0, "y" to 2, "z" to 1, "extruder" to 3)),
        ControllerBoard("btt-skr-mini-e3-v2", "BTT SKR Mini E3", "V2.0 · manual pins", emptyMap()),
        ControllerBoard("btt-skr-pico", "BTT SKR Pico", "V1.0 · manual pins", emptyMap()),
        ControllerBoard("btt-skr3", "BTT SKR 3 / EZ", "exact revision required · manual pins", emptyMap()),
        ControllerBoard("btt-octopus", "BTT Octopus / Pro", "exact revision required · manual pins", emptyMap()),
        ControllerBoard("btt-manta", "BTT Manta M4P/M5P/M8P", "exact revision required · manual pins", emptyMap()),
        ControllerBoard("creality-422", "Creality 4.2.2", "MCU/driver population required · manual pins", emptyMap()),
        ControllerBoard("creality-427", "Creality 4.2.7", "MCU/driver population required · manual pins", emptyMap()),
        ControllerBoard("fysetc-spider", "Fysetc Spider", "exact revision required · manual pins", emptyMap()),
        ControllerBoard("ldo-leviathan", "LDO Leviathan", "exact revision required · manual pins", emptyMap()),
    )
    val plugins = linkedMapOf("tmc-autotune" to "Klipper TMC Autotune", "shake-tune" to "Klippain Shake&Tune", "timelapse" to "Moonraker Timelapse", "led-effect" to "Klipper LED Effect", "kamp-purge" to "KAMP adaptive purge", "happy-hare" to "Happy Hare MMU")
}
