package dev.klipper.configurator.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

object ProjectCodec {
    fun encode(project: ConfigProject): ByteArray {
        val p = Properties()
        mapOf("schemaVersion" to project.schemaVersion, "name" to project.name, "target" to project.target.name,
            "mechanicsProfile" to project.mechanicsProfile, "kinematics" to project.kinematics.name, "bedWidth" to project.bedWidth,
            "bedDepth" to project.bedDepth, "buildHeight" to project.buildHeight, "zMotorCount" to project.zMotorCount,
            "controllerId" to project.controllerId, "mcuSerial" to project.mcuSerial, "xyDriver" to project.xyDriver.name,
            "zDriver" to project.zDriver.name, "extruderDriver" to project.extruderDriver.name, "probe" to project.probe.name,
            "bedMesh" to project.bedMesh, "adaptiveMesh" to project.adaptiveMesh, "inputShaper" to project.inputShaper,
            "filamentSensor" to project.filamentSensor, "maxVelocity" to project.maxVelocity, "maxAccel" to project.maxAccel,
            "xyRotationDistance" to project.xyRotationDistance, "zRotationDistance" to project.zRotationDistance,
            "extruderRotationDistance" to project.extruderRotationDistance, "xyRunCurrent" to project.xyRunCurrent,
            "zRunCurrent" to project.zRunCurrent, "extruderRunCurrent" to project.extruderRunCurrent,
            "hotendSensor" to project.hotendSensor, "bedSensor" to project.bedSensor, "hotendMaxTemp" to project.hotendMaxTemp,
            "bedMaxTemp" to project.bedMaxTemp, "probeXOffset" to project.probeXOffset, "probeYOffset" to project.probeYOffset,
            "probeZOffset" to project.probeZOffset, "pinOverrides" to project.pinOverrides.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" },
            "plugins" to project.selectedPlugins.sorted().joinToString(",")
        ).forEach { (k, v) -> p.setProperty(k, v.toString()) }
        return ByteArrayOutputStream().also { p.store(it, "Klipper Configurator project") }.toByteArray()
    }
    fun decode(bytes: ByteArray): ConfigProject {
        val p = Properties().apply { load(ByteArrayInputStream(bytes)) }
        fun i(n: String, d: Int) = p.getProperty(n)?.toIntOrNull() ?: d
        fun b(n: String, d: Boolean) = p.getProperty(n)?.toBooleanStrictOrNull() ?: d
        fun d(n: String, fallback: Double) = p.getProperty(n)?.toDoubleOrNull() ?: fallback
        fun <T : Enum<T>> e(n: String, d: T, values: Array<T>) = values.firstOrNull { it.name == p.getProperty(n) } ?: d
        val pins = p.getProperty("pinOverrides", "").split(';').mapNotNull { item ->
            val at = item.indexOf('='); if (at <= 0) null else item.substring(0, at) to item.substring(at + 1)
        }.toMap()
        return ConfigProject(schemaVersion = i("schemaVersion", 1), name = p.getProperty("name", "Imported Klipper Printer"),
            target = e("target", DeploymentTarget.K4A_TERMUX, DeploymentTarget.entries.toTypedArray()), mechanicsProfile = p.getProperty("mechanicsProfile", "Custom"),
            kinematics = e("kinematics", Kinematics.CARTESIAN, Kinematics.entries.toTypedArray()), bedWidth = i("bedWidth", 220), bedDepth = i("bedDepth", 220),
            buildHeight = i("buildHeight", 250), zMotorCount = i("zMotorCount", 1), controllerId = p.getProperty("controllerId", "generic"), mcuSerial = p.getProperty("mcuSerial", ConfigProject.K4A_SERIAL),
            xyDriver = e("xyDriver", DriverKind.TMC2209, DriverKind.entries.toTypedArray()), zDriver = e("zDriver", DriverKind.TMC2209, DriverKind.entries.toTypedArray()),
            extruderDriver = e("extruderDriver", DriverKind.TMC2209, DriverKind.entries.toTypedArray()), probe = e("probe", ProbeKind.NONE, ProbeKind.entries.toTypedArray()),
            bedMesh = b("bedMesh", false), adaptiveMesh = b("adaptiveMesh", false), inputShaper = b("inputShaper", false), filamentSensor = b("filamentSensor", false),
            maxVelocity = i("maxVelocity", 200), maxAccel = i("maxAccel", 2000), xyRotationDistance = d("xyRotationDistance", 40.0),
            zRotationDistance = d("zRotationDistance", 8.0), extruderRotationDistance = d("extruderRotationDistance", 33.5),
            xyRunCurrent = d("xyRunCurrent", .7), zRunCurrent = d("zRunCurrent", .7), extruderRunCurrent = d("extruderRunCurrent", .7),
            hotendSensor = p.getProperty("hotendSensor", "Generic 3950"), bedSensor = p.getProperty("bedSensor", "Generic 3950"),
            hotendMaxTemp = i("hotendMaxTemp", 260), bedMaxTemp = i("bedMaxTemp", 120), probeXOffset = d("probeXOffset", 0.0),
            probeYOffset = d("probeYOffset", 0.0), probeZOffset = d("probeZOffset", 0.0), pinOverrides = pins,
            selectedPlugins = p.getProperty("plugins", "").split(',').filter(String::isNotBlank).toSet())
    }
}
