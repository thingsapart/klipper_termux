package dev.klipper.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerCommandTest {
    @Test fun buildsFreshInstallerCommand() {
        val command = InstallerCommand.create(
            "https://example.test/install.sh",
            "https://example.test/repo.git",
        )
        assertTrue(command.contains("pkg install -y curl >/dev/null"))
        assertTrue(command.contains("k4a_refresh='\"\$(date +%s)\""))
        assertTrue(command.contains("Cache-Control: no-cache"))
        assertTrue(command.contains("K4A_REPOSITORY='https://example.test/repo.git'"))
    }

    @Test fun recognizesPublicationPlaceholders() {
        assertFalse(InstallerCommand.isConfigured(
            "https://raw.githubusercontent.com/OWNER/REPOSITORY/main/installer/install.sh",
            "https://github.com/OWNER/REPOSITORY.git",
        ))
        assertTrue(InstallerCommand.isConfigured(
            "https://example.test/install.sh",
            "https://example.test/repo.git",
        ))
    }

    @Test fun buildsNonInteractiveUpdateCommand() {
        val command = InstallerCommand.createUpdate(
            "https://example.test/install.sh?channel=stable",
            "https://example.test/repo.git",
        )
        assertTrue(command.contains("channel=stable&k4a_refresh="))
        assertTrue(command.endsWith("bash \"\$installer\" --update"))
    }
}
