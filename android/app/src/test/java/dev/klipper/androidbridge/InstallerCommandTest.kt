package dev.klipper.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerCommandTest {
    @Test fun buildsCopyablePinnedCommand() {
        assertEquals(
            "pkg install -y curl && curl -fsSL 'https://example.test/install.sh' | " +
                "KAB_REPOSITORY='https://example.test/repo.git' bash",
            InstallerCommand.create(
                "https://example.test/install.sh",
                "https://example.test/repo.git",
            ),
        )
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
}
