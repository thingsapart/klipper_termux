package dev.klipper.androidbridge

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxRunnerTest {
    @Test fun `ordinary command success does not require health sentinel`() {
        val result = TermuxRunner.CommandResult("", "", 0, Activity.RESULT_OK, "")
        assertTrue(result.succeeded)
        assertFalse(result.healthCheckSucceeded)
    }

    @Test fun `health check requires exact sentinel`() {
        val result = TermuxRunner.CommandResult("K4A_OK\n", "", 0, Activity.RESULT_OK, "")
        assertTrue(result.succeeded)
        assertTrue(result.healthCheckSucceeded)
    }

    @Test fun `nonzero command result fails`() {
        val result = TermuxRunner.CommandResult("", "unknown command", 2, Activity.RESULT_OK, "")
        assertFalse(result.succeeded)
        assertFalse(result.healthCheckSucceeded)
    }
}
