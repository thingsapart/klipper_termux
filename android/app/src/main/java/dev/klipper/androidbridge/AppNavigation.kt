package dev.klipper.androidbridge

enum class Destination {
    DASHBOARD,
    MAINSAIL,
    SETUP,
    SETTINGS,
}

enum class BackAction {
    CLOSE_DRAWER,
    WEB_HISTORY,
    DASHBOARD,
    PRIMARY,
    SETTINGS,
    EXIT,
}

object AppNavigation {
    fun toggle(destination: Destination): Destination = when (destination) {
        Destination.DASHBOARD -> Destination.MAINSAIL
        Destination.MAINSAIL -> Destination.DASHBOARD
        Destination.SETUP -> Destination.DASHBOARD
        Destination.SETTINGS -> Destination.DASHBOARD
    }

    fun backAction(
        drawerOpen: Boolean,
        destination: Destination,
        webCanGoBack: Boolean,
    ): BackAction = when {
        drawerOpen -> BackAction.CLOSE_DRAWER
        destination == Destination.MAINSAIL && webCanGoBack -> BackAction.WEB_HISTORY
        destination == Destination.MAINSAIL -> BackAction.DASHBOARD
        destination == Destination.SETUP -> BackAction.SETTINGS
        destination == Destination.SETTINGS -> BackAction.PRIMARY
        else -> BackAction.EXIT
    }
}
