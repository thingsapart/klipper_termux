package dev.klipper.configurator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigGeneratorTest {
    @Test fun `default project produces managed config tree`() {
        val bundle = ConfigGenerator.generate(ConfigProject())
        assertTrue("printer.cfg" in bundle.files)
        assertTrue("k4a/current.cfg" in bundle.files)
        assertTrue("k4a/revisions/generated/20-motion.cfg" in bundle.files)
        assertTrue(bundle.files.getValue("printer.cfg").decodeToString().contains("[include k4a/current.cfg]"))
        assertTrue("20-missing.cfg" !in bundle.files)
    }

    @Test fun `exported zip reopens editable project`() {
        val expected = ConfigProject(
            name = "Voron Test", target = DeploymentTarget.STANDARD_LINUX,
            kinematics = Kinematics.COREXY, bedWidth = 300, bedDepth = 300,
            buildHeight = 280, zMotorCount = 4, probe = ProbeKind.BTT_EDDY,
            bedMesh = true, adaptiveMesh = true, selectedPlugins = setOf("shake-tune"),
        )
        assertEquals(expected, ConfigGenerator.importProject(ConfigGenerator.zip(ConfigGenerator.generate(expected))))
    }

    @Test fun `generic controller is visibly incomplete`() {
        val bundle = ConfigGenerator.generate(ConfigProject(controllerId = "generic"))
        assertTrue(bundle.issues.any { it.field == "controller" && it.severity == Severity.WARNING })
        assertTrue(bundle.files.getValue("k4a/revisions/generated/20-motion.cfg").decodeToString().contains("CHANGE_ME_X_STEP"))
    }

    @Test fun `advanced values and pin overrides survive zip round trip`() {
        val expected = ConfigProject(maxVelocity = 350, maxAccel = 8000, xyRunCurrent = 1.1,
            hotendSensor = "ATC Semitec 104GT-2", probeXOffset = -22.5,
            pinOverrides = mapOf("x_step" to "PA5", "probe" to "^PB7"))
        val bundle = ConfigGenerator.generate(expected)
        val motion = bundle.files.getValue("k4a/revisions/generated/20-motion.cfg").decodeToString()
        assertTrue(motion.contains("step_pin: PA5"))
        assertTrue(motion.contains("max_velocity: 350"))
        assertTrue("k4a/revisions/generated/80-plugins.cfg" !in bundle.files)
        assertEquals(expected, ConfigGenerator.importProject(ConfigGenerator.zip(bundle)))
    }
}
