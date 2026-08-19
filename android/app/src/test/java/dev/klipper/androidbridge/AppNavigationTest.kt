package dev.klipper.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test fun `primary toggle switches dashboard and mainsail`() {
        assertEquals(Destination.MAINSAIL, AppNavigation.toggle(Destination.DASHBOARD))
        assertEquals(Destination.DASHBOARD, AppNavigation.toggle(Destination.MAINSAIL))
    }

    @Test fun `back prioritizes drawer then web history`() {
        assertEquals(BackAction.CLOSE_DRAWER, AppNavigation.backAction(true, Destination.MAINSAIL, true))
        assertEquals(BackAction.WEB_HISTORY, AppNavigation.backAction(false, Destination.MAINSAIL, true))
        assertEquals(BackAction.DASHBOARD, AppNavigation.backAction(false, Destination.MAINSAIL, false))
    }

    @Test fun `setup returns to settings and settings to primary`() {
        assertEquals(BackAction.SETTINGS, AppNavigation.backAction(false, Destination.SETUP, false))
        assertEquals(BackAction.PRIMARY, AppNavigation.backAction(false, Destination.SETTINGS, false))
        assertEquals(BackAction.EXIT, AppNavigation.backAction(false, Destination.DASHBOARD, false))
    }
}
