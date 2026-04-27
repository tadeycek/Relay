package com.relay.app.ui.screens.map

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.relay.app.data.model.MapStyle
import com.relay.app.ui.components.NavIconButton
import com.relay.app.ui.components.SendPinBottomSheet
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MapScreen(navController: NavController) {
    val vm: MapViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val locationPermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(46.0, 14.5))
        }
    }

    DisposableEffect(mapView) {
        val longPressOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                vm.dropPin(p)
                return true
            }
        })
        mapView.overlays.add(0, longPressOverlay)
        onDispose {}
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    vm.reloadSavedPins()
                    vm.reloadDefaults()
                }
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    DisposableEffect(Unit) {
        if (!locationPermission.status.isGranted) {
            locationPermission.launchPermissionRequest()
        }
        onDispose {}
    }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                // Update tile source based on style
                val tileSource = when (uiState.mapStyle) {
                    MapStyle.STANDARD -> TileSourceFactory.MAPNIK
                    MapStyle.TERRAIN -> XYTileSource(
                        "OpenTopo", 0, 17, 256, ".png",
                        arrayOf("https://tile.opentopomap.org/")
                    )
                    MapStyle.SATELLITE -> object : XYTileSource(
                        "ESRI_Satellite", 0, 19, 256, ".jpg",
                        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
                    ) {
                        override fun getTileURLString(pMapTileIndex: Long): String {
                            val z = MapTileIndex.getZoom(pMapTileIndex)
                            val x = MapTileIndex.getX(pMapTileIndex)
                            val y = MapTileIndex.getY(pMapTileIndex)
                            return "${baseUrl}$z/$y/$x"
                        }
                    }
                }
                if (mv.tileProvider.tileSource?.name() != tileSource.name()) {
                    mv.setTileSource(tileSource)
                }

                // Remove old dropped-pin marker
                mv.overlays.removeAll { it is Marker && it.id == "dropped_pin" }
                // Remove old saved-pin markers
                mv.overlays.removeAll { it is Marker && it.id?.startsWith("saved_pin_") == true }
                // Remove old label overlay
                mv.overlays.removeAll { it is PinLabelOverlay }

                // Add location overlay if permission granted
                val hasLocationOverlay = mv.overlays.any { it is MyLocationNewOverlay }
                if (locationPermission.status.isGranted && !hasLocationOverlay) {
                    val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mv)
                    locationOverlay.enableMyLocation()
                    mv.overlays.add(locationOverlay)
                }

                // Render saved pins
                for (pin in uiState.savedPins) {
                    val lat = pin.lat ?: continue
                    val lng = pin.lng ?: continue
                    val marker = Marker(mv).apply {
                        id = "saved_pin_${pin.id}"
                        position = GeoPoint(lat, lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = pin.pinLabel ?: "${"%.4f".format(lat)}, ${"%.4f".format(lng)}"
                    }
                    mv.overlays.add(marker)
                }

                // Render pin labels overlay
                val pinsWithLabels = uiState.savedPins.filter { it.pinLabel != null }
                if (pinsWithLabels.isNotEmpty()) {
                    mv.overlays.add(PinLabelOverlay(pinsWithLabels))
                }

                // Render dropped pin
                uiState.droppedPin?.let { point ->
                    val marker = Marker(mv).apply {
                        id = "dropped_pin"
                        position = point
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mv.overlays.add(marker)
                }

                mv.invalidate()
            },
        )

        // Top-left wordmark
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .background(Accent, RoundedCornerShape(8.dp)),
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Top-right nav buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavIconButton(
                icon = Icons.Outlined.Layers,
                contentDescription = "Map style",
                onClick = {
                    val next = when (uiState.mapStyle) {
                        MapStyle.STANDARD -> MapStyle.SATELLITE
                        MapStyle.SATELLITE -> MapStyle.TERRAIN
                        MapStyle.TERRAIN -> MapStyle.STANDARD
                    }
                    vm.setMapStyle(next, context)
                },
            )
            NavIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = "Settings",
                onClick = { navController.navigate(Screen.Settings.route) },
            )
            NavIconButton(
                icon = Icons.Outlined.History,
                contentDescription = "Pin history",
                onClick = { navController.navigate(Screen.PinHistory.route) },
            )
            NavIconButton(
                icon = Icons.Outlined.ChatBubble,
                contentDescription = "Chats",
                onClick = { navController.navigate(Screen.Contacts.route) },
            )
            NavIconButton(
                icon = Icons.Outlined.Person,
                contentDescription = "Contacts",
                onClick = { navController.navigate(Screen.Contacts.route) },
            )
        }

        // FAB — drop pin at map center
        if (!uiState.showSendSheet) {
            FloatingActionButton(
                onClick = {
                    val center = mapView.mapCenter as GeoPoint
                    vm.dropPin(center)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(56.dp),
                shape = RoundedCornerShape(14.dp),
                containerColor = Accent,
                contentColor = Color.White,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = "Drop pin",
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Bottom sheet: pick contact/group and send pin
        if (uiState.showSendSheet) {
            SendPinBottomSheet(
                contacts = uiState.contacts,
                groups = uiState.groups,
                selectedContactId = uiState.selectedContactId,
                selectedGroupId = uiState.selectedGroupId,
                pinLabel = uiState.pinLabel,
                pinExpiry = uiState.pinExpiry,
                onContactSelected = vm::selectContact,
                onGroupSelected = vm::selectGroup,
                onPinLabelChange = vm::setPinLabel,
                onPinExpiryChange = vm::setPinExpiry,
                onSend = { vm.sendPin(context) },
                onDismiss = vm::dismissSheet,
            )
        }
    }
}
