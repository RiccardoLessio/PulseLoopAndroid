package com.pulseloop.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pulseloop.context.ActivityRecognitionManager
import com.pulseloop.context.AutoPlace
import com.pulseloop.context.ContextPermissions
import com.pulseloop.context.ContextPrefs
import com.pulseloop.context.MonitorService
import com.pulseloop.context.PhoneContextWorker
import com.pulseloop.context.ZoneMatcher
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ZoneEntity
import com.pulseloop.remote.RingHubClient
import com.pulseloop.remote.RingHubPrefsStore
import com.pulseloop.remote.RingHubTokenStore
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.roundToInt

/** Settings → Contesto: every phone signal is opt-in, with the permission it needs one tap away. */
@Composable
fun ContextSettingsScreen(onBack: () -> Unit, onOpenZones: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ContextPrefs.get(context) }
    val settings by prefs.settings.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    // Permissions change in system screens: re-read on every return.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    @Suppress("UNUSED_EXPRESSION") refresh
    val usage = ContextPermissions.hasUsageAccess(context)
    val fg = ContextPermissions.hasForegroundLocation(context)
    val bg = ContextPermissions.hasBackgroundLocation(context)
    val ar = ContextPermissions.hasActivityRecognition(context)
    val play = ContextPermissions.hasPlayServices(context)

    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val arLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { prefs.update { it.copy(activityEnabled = true) }; ActivityRecognitionManager.sync(context) }
        refresh++
    }

    SettingsSubScreen(title = "Contesto", onBack = onBack) {
        SectionCard(
            "Tempo davanti allo schermo",
            "Minuti di schermo acceso per ora e ultimo uso prima di dormire, letti dalle statistiche di utilizzo " +
                "di Android. Non registra quali app usi.",
        ) {
            ToggleRow("Registra il tempo schermo", settings.usageEnabled && usage, enabled = usage, onChange = { on ->
                prefs.update { it.copy(usageEnabled = on) }
                PhoneContextWorker.schedule(context)
                if (on) PhoneContextWorker.runNow(context)
            }, sub = if (usage) null else "Serve l'accesso ai dati di utilizzo")
            if (!usage) {
                Text("Impostazioni → Accesso ai dati di utilizzo → PulseLoop → Consenti.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { context.startActivity(ContextPermissions.usageAccessIntent()) }) { Text("Apri accesso utilizzo") }
            }
        }

        SectionCard(
            "Posizione automatica",
            "Registra la posizione in modo leggero (Wi-Fi/rete, ogni 2 min in movimento, ogni 15 da fermo) e la invia " +
                "solo al tuo server ring-hub, che riconosce da solo Casa, Lavoro e gli altri luoghi. Mostra una notifica " +
                "fissa \"Monitoraggio attivo\".",
        ) {
            ToggleRow("Posizione automatica", settings.locationEnabled && fg, enabled = fg, onChange = { on ->
                prefs.update { it.copy(locationEnabled = on) }
                MonitorService.refresh(context)
            }, sub = when {
                !fg -> "Serve il permesso posizione"
                !bg -> "Consigliato: posizione \"Consenti sempre\""
                else -> null
            })
            if (!fg) {
                OutlinedButton(onClick = {
                    fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Consenti posizione") }
            } else if (!bg && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Text(
                    "Per registrare anche ad app chiusa scegli \"Consenti sempre\" nella pagina che si apre.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Consenti sempre") }
            }
            OutlinedButton(onClick = onOpenZones) { Text("Zone (Casa, Lavoro, Palestra…)") }
        }

        SectionCard(
            "Riconoscimento attività",
            "Fermo, a piedi, corsa, bici, in auto: rilevato dal telefono (Google Play Services), usato per " +
                "regolare la frequenza della posizione, per lo sport nel diario e come contesto per lo stress.",
        ) {
            if (!play) {
                Text("Google Play Services non disponibile su questo telefono.", style = MaterialTheme.typography.bodySmall)
            } else {
                ToggleRow("Riconoscimento attività", settings.activityEnabled && ar, onChange = { on ->
                    if (on && !ar && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        arLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        prefs.update { it.copy(activityEnabled = on) }
                        ActivityRecognitionManager.sync(context)
                    }
                })
            }
        }

        SectionCard("Batteria", "MagicOS chiude le app in background: per posizione e modalità adattiva serve \"Senza restrizioni\".") {
            val unrestricted = ContextPermissions.ignoresBatteryOptimizations(context)
            Text(if (unrestricted) "OK: l'app non ha restrizioni batteria." else "L'app è ottimizzata dal sistema.", style = MaterialTheme.typography.bodySmall)
            if (!unrestricted) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(ContextPermissions.batteryOptimizationIntent(context)) }
                        .onFailure { context.startActivity(ContextPermissions.appDetailsIntent(context)) }
                }) { Text("Togli le restrizioni") }
            }
        }
    }
}

private val ZONE_PRESETS = listOf(ZoneMatcher.CATEGORY_HOME, ZoneMatcher.CATEGORY_WORK, ZoneMatcher.CATEGORY_GYM, ZoneMatcher.CATEGORY_CUSTOM)

private fun defaultName(category: String) = ZoneMatcher.CATEGORY_LABELS[category].takeIf { category != ZoneMatcher.CATEGORY_CUSTOM }.orEmpty()

/** Zone: pins + radius on an OpenStreetMap map; ring-hub's detected places can be promoted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZonesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val zones by db.zoneDao().activeFlow().collectAsState(initial = emptyList())
    val places by ContextPrefs.get(context).places.collectAsState()
    var editing by remember { mutableStateOf<ZoneEntity?>(null) }

    LaunchedEffect(Unit) {
        val hub = RingHubPrefsStore.get(context).current
        val token = RingHubTokenStore(context).token
        if (hub.enabled && token.isNotBlank()) {
            runCatching { RingHubClient(hub.serverUrl, token).places() }.getOrNull()?.let { ContextPrefs.get(context).savePlaces(it) }
        }
    }

    fun save(zone: ZoneEntity) = scope.launch {
        db.zoneDao().upsert(zone.copy(updatedAt = System.currentTimeMillis()))
        com.pulseloop.remote.RingHubSyncWorker.enqueue(context)
    }

    SettingsSubScreen(title = "Zone", onBack = onBack) {
        SectionCard("Le tue zone", "Hanno la precedenza sui luoghi riconosciuti in automatico e danno il nome ai luoghi nella dashboard.") {
            if (zones.isEmpty()) Text("Nessuna zona.", style = MaterialTheme.typography.bodySmall)
            zones.forEach { z ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(z.name)
                        Text("${ZoneMatcher.CATEGORY_LABELS[z.category] ?: z.category} · raggio ${z.radiusMeters.roundToInt()} m",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editing = z }) { Text("Modifica") }
                }
                HorizontalDivider()
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZONE_PRESETS.forEach { c ->
                    OutlinedButton(onClick = {
                        val center = zones.lastOrNull()
                        editing = ZoneEntity(name = defaultName(c), category = c,
                            latitude = center?.latitude ?: 45.0703, longitude = center?.longitude ?: 7.6869, radiusMeters = 150.0)
                    }) { Text("+ " + (ZoneMatcher.CATEGORY_LABELS[c] ?: c)) }
                }
            }
        }
        val promotable = places.filter { it.zoneId == null }
        if (promotable.isNotEmpty()) {
            SectionCard("Luoghi riconosciuti", "Dal server ring-hub. Un tocco per trasformarne uno in zona.") {
                promotable.take(10).forEach { p -> PromoteRow(p) { category -> save(promote(p, category)) } }
            }
        }
    }

    editing?.let { zone -> ZoneEditor(zone, onDismiss = { editing = null }, onSave = { save(it); editing = null },
        onDelete = { save(it.copy(deletedAt = System.currentTimeMillis())); editing = null }) }
}

internal fun promote(p: AutoPlace, category: String) = ZoneEntity(
    name = defaultName(category).ifEmpty { p.name },
    category = category,
    latitude = p.latitude,
    longitude = p.longitude,
    radiusMeters = 120.0,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromoteRow(p: AutoPlace, onPromote: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${p.name} · ${p.visitCount} visite", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZONE_PRESETS.forEach { c ->
                FilterChip(selected = false, onClick = { onPromote(c) }, label = { Text("È " + (ZoneMatcher.CATEGORY_LABELS[c] ?: c)) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneEditor(initial: ZoneEntity, onDismiss: () -> Unit, onSave: (ZoneEntity) -> Unit, onDelete: (ZoneEntity) -> Unit) {
    val context = LocalContext.current
    var zone by remember(initial.id) { mutableStateOf(initial) }
    var locating by remember { mutableStateOf(false) }
    val exists = remember(initial.id) { mutableStateOf(false) }
    LaunchedEffect(initial.id) { exists.value = PulseLoopDatabase.getInstance(context).zoneDao().byId(initial.id) != null }

    @android.annotation.SuppressLint("MissingPermission")
    fun useCurrentLocation() {
        if (!ContextPermissions.hasForegroundLocation(context)) return
        locating = true
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc -> loc?.let { zone = zone.copy(latitude = it.latitude, longitude = it.longitude) }; locating = false }
            .addOnFailureListener { locating = false }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (exists.value) "Modifica zona" else "Nuova zona") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = zone.name, onValueChange = { zone = zone.copy(name = it) }, label = { Text("Nome") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZONE_PRESETS.forEach { c ->
                        FilterChip(selected = zone.category == c, onClick = {
                            zone = zone.copy(category = c, name = if (zone.name.isBlank() || zone.name in ZoneMatcher.CATEGORY_LABELS.values) defaultName(c) else zone.name)
                        }, label = { Text(ZoneMatcher.CATEGORY_LABELS[c] ?: c) })
                    }
                }
                ZoneMap(zone) { lat, lon -> zone = zone.copy(latitude = lat, longitude = lon) }
                Text("Tocca la mappa o trascina il segnaposto. Raggio: ${zone.radiusMeters.roundToInt()} m", style = MaterialTheme.typography.bodySmall)
                Slider(value = zone.radiusMeters.toFloat(), onValueChange = { zone = zone.copy(radiusMeters = (it / 10f).roundToInt() * 10.0) }, valueRange = 50f..1000f)
                OutlinedButton(enabled = !locating, onClick = { useCurrentLocation() }) { Text(if (locating) "Cerco la posizione…" else "Usa posizione attuale") }
            }
        },
        confirmButton = { Button(enabled = zone.name.isNotBlank(), onClick = { onSave(zone.copy(name = zone.name.trim())) }) { Text("Salva") } },
        dismissButton = {
            Row {
                if (exists.value) TextButton(onClick = { onDelete(zone) }) { Text("Elimina") }
                TextButton(onClick = onDismiss) { Text("Annulla") }
            }
        },
    )
}

/** osmdroid map (OpenStreetMap tiles, no API key): pin + radius circle, tap or drag to move. */
@Composable
private fun ZoneMap(zone: ZoneEntity, onMove: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(zone.latitude, zone.longitude))
        }
    }
    val marker = remember { Marker(mapView).apply { isDraggable = true; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }
    val circle = remember { Polygon(mapView).apply { fillPaint.color = 0x333E63DD; outlinePaint.color = 0xFF3E63DD.toInt(); outlinePaint.strokeWidth = 3f } }
    val move = rememberUpdated(onMove)
    DisposableEffect(mapView) {
        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(m: Marker) = Unit
            override fun onMarkerDragStart(m: Marker) = Unit
            override fun onMarkerDragEnd(m: Marker) = move.value(m.position.latitude, m.position.longitude)
        })
        mapView.overlays.add(circle)
        mapView.overlays.add(marker)
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { move.value(p.latitude, p.longitude); return true }
            override fun longPressHelper(p: GeoPoint): Boolean { move.value(p.latitude, p.longitude); return true }
        }))
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(240.dp), update = { map ->
        val point = GeoPoint(zone.latitude, zone.longitude)
        marker.position = point
        circle.points = Polygon.pointsAsCircle(point, zone.radiusMeters)
        if (map.mapCenter.let { ZoneMatcher.distanceMeters(it.latitude, it.longitude, point.latitude, point.longitude) } > 300) {
            map.controller.animateTo(point)
        }
        map.invalidate()
    })
}

@Composable
private fun <T> rememberUpdated(value: T): androidx.compose.runtime.State<T> {
    val state = remember { mutableStateOf(value) }
    state.value = value
    return state
}
