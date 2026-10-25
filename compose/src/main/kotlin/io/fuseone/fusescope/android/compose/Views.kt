package io.fuseone.fusescope.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import io.fuseone.fusescope.android.FuseScope

/** Starts a FuseScope view named [route] when this composable enters the composition or [route] changes. */
@Composable
public fun FuseScopeView(route: String) {
    LaunchedEffect(route) { FuseScope.startView(route) }
}

/** Starts a FuseScope view for every destination [navController] navigates to, named by its route template. */
@Composable
public fun FuseScopeNavigationViews(navController: NavController) {
    DisposableEffect(navController) {
        val listener = navController.trackFuseScopeViews()
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
}

/** Starts a FuseScope view for every destination, Compose or Fragment based; remove the returned listener to stop. */
public fun NavController.trackFuseScopeViews(): NavController.OnDestinationChangedListener {
    val listener = NavController.OnDestinationChangedListener { _, destination, _ -> viewRoute(destination)?.let(FuseScope::startView) }
    addOnDestinationChangedListener(listener)
    return listener
}

/** The route template (`orders/{id}`), never the filled-in arguments; else the label. */
internal fun viewRoute(destination: NavDestination): String? = destination.route ?: destination.label?.toString()
