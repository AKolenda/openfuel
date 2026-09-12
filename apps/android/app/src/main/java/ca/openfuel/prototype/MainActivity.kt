// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Job
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.UUID

private val Forest = Color(DesignTokens.GREEN)
private val Ink = Color(DesignTokens.INK)
private val Muted = Color(DesignTokens.MUTED)
private val Pale = Color(DesignTokens.SOFT)
private val Rule = Color(DesignTokens.LINE)
private enum class Menu { LOCATION, SETTINGS, SORT, ABOUT, DETAIL, PRICE, NEW_STATION, CORRECTION }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White,
                background = Color(0xFFEDF1EA), surface = Color.White, onSurface = Ink, outlineVariant = Rule)) {
                OpenFuelApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenFuelApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE) }
    val repository = remember { StationRepository(context) }
    val initial = remember { repository.initial() }
    var stations by remember { mutableStateOf(initial.stations) }
    var syncState by remember { mutableStateOf(if (initial.cached) "cached" else "choose-area") }
    var hasSearchArea by remember { mutableStateOf(initial.cached) }
    var point by remember { mutableStateOf(initial.point) }
    var browsePoint by remember { mutableStateOf<SearchPoint?>(null) }
    var centerRequest by remember { mutableIntStateOf(0) }
    var devicePoint by remember { mutableStateOf<SearchPoint?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var locationIntro by remember { mutableStateOf(!prefs.getBoolean("location-intro-seen", false)) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    var grade by rememberSaveable { mutableStateOf(Grade.REGULAR) }
    var sort by rememberSaveable { mutableStateOf(SortMode.BEST) }
    var filters by remember { mutableStateOf(Filters()) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.toSet()) }
    var provider by remember { mutableStateOf(if (prefs.getString("maps", "google") == "ask") MapProvider.ASK else MapProvider.GOOGLE) }
    var fullWidth by rememberSaveable { mutableStateOf(prefs.getBoolean("wide", false)) }
    var cards by rememberSaveable { mutableStateOf(false) } // List is always the initial layout.
    var query by rememberSaveable { mutableStateOf("") }
    var savedOnly by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Menu?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val draftStore = remember { LocalDraftStore(context) }
    val loadedDrafts = remember { runCatching { draftStore.load() } }
    val drafts = remember { mutableStateListOf<StationProposal>().apply { addAll(loadedDrafts.getOrDefault(emptyList())) } }
    val visible = FuelCore.visible(stations, grade, sort, filters, query, savedOnly, favorites)
    val bestId = visible.filter { it.price(grade) != null && it.age(grade) <= 60 }.minByOrNull { it.price(grade, filters.members)!! }?.id
    val selected = stations.find { it.id == selectedId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.maps_unavailable)
    LaunchedEffect(Unit) { if (loadedDrafts.isFailure) snackbar.showSnackbar(context.getString(R.string.local_storage_error)) }
    fun refresh(next: SearchPoint = point) {
        hasSearchArea = true
        refreshJob?.cancel()
        val changedArea = next.latitude != point.latitude || next.longitude != point.longitude
        point = next
        browsePoint = null
        centerRequest++
        if (changedArea) stations = emptyList()
        refreshJob = scope.launch {
            syncing = true
            try {
                stations = repository.refresh(next)
                syncState = "connected"
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                syncState = "offline"
            } finally { syncing = false }
        }
    }
    fun locate() {
        if (locating) return
        scope.launch {
            locating = true
            val fix = runCatching { currentSearchPoint(context) }.getOrNull()
            locating = false
            if (fix != null) { devicePoint = fix; locationMessage = null; refresh(fix) }
            else { locationMessage = "Location unavailable. Turn on device location or choose a city."; menu = Menu.LOCATION }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) locate()
        else { locationMessage = "Location permission was declined. Choose an area below."; menu = Menu.LOCATION }
    }
    fun requestLocation() {
        locationIntro = false
        prefs.edit().putBoolean("location-intro-seen", true).apply()
        if (hasLocationPermission(context)) locate()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) {
        if (initial.cached) refresh()
        if (!locationIntro && hasLocationPermission(context)) locate()
    }
    fun go(station: Station) {
        if (!openMaps(context, station, provider)) scope.launch { snackbar.showSnackbar(unavailable) }
    }
    fun save(station: Station) {
        favorites = if (station.id in favorites) favorites - station.id else favorites + station.id
        prefs.edit().putStringSet("favorites", favorites).apply()
    }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().testTag("native-root")) {
        val contentHeight = (maxHeight - 170.dp).coerceAtLeast(280.dp)
        val peekHeight = minOf(336.dp, maxHeight * .43f)
        BottomSheetScaffold(
            sheetPeekHeight = peekHeight,
            sheetContainerColor = Color.White,
            containerColor = Color(0xFFEDF1EA),
            sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            sheetDragHandle = { BottomSheetDefaults.DragHandle(color = Rule) },
            snackbarHost = { SnackbarHost(snackbar) },
            sheetContent = {
                Column(Modifier.fillMaxWidth().height(contentHeight)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.fuel_nearby), fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Row(Modifier.clip(RoundedCornerShape(11.dp)).background(Color(DesignTokens.SOFT)).padding(3.dp)) {
                            listOf(true, false).forEach { mode ->
                                TextButton(onClick = { cards = mode }, modifier = Modifier.height(32.dp).testTag(if (mode) "layout-cards" else "layout-list"),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.textButtonColors(containerColor = if (cards == mode) Color.White else Color.Transparent)) {
                                    Icon(if (mode) Icons.Default.ViewAgenda else Icons.Default.ViewList, null, Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp)); Text(stringResource(if (mode) R.string.cards else R.string.list),fontSize=11.sp)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.station_count, visible.size), fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { menu = Menu.SORT }, modifier = Modifier.testTag("open-sort")) { Text(sortLabel(sort), fontSize = 11.sp); Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val status = when { syncing -> R.string.sync_loading; syncState == "connected" -> R.string.sync_connected;
                            syncState == "offline" -> R.string.sync_offline; syncState == "cached" -> R.string.sync_cached; else -> R.string.sync_samples }
                        Text(stringResource(status), fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f).testTag("sync-status"))
                        IconButton(onClick = { if (hasSearchArea) refresh() else menu = Menu.LOCATION }, enabled = !syncing, modifier = Modifier.size(36.dp).testTag("refresh-prices")) {
                            if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, stringResource(R.string.refresh_prices), Modifier.size(18.dp))
                        }
                    }
                    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (visible.isEmpty()) item {
                            Text(stringResource(R.string.no_matches), Modifier.padding(20.dp), color = Muted)
                            TextButton(onClick = { query = ""; filters = Filters(); savedOnly = false }) { Text(stringResource(R.string.reset_filters)) }
                        }
                        items(visible, key = { it.id }) { station ->
                            StationRow(station, grade, filters, cards, fullWidth,
                                station.id == bestId,
                                select = { selectedId = station.id; menu = Menu.DETAIL }, go = { go(station) })
                        }
                        item { TextButton(onClick = { selectedId = null; menu = Menu.NEW_STATION }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.suggest_station))
                        } }
                    }
                }
            }
        ) {
            Box(Modifier.fillMaxSize()) {
                LiveMap(visible, grade, bestId, point, devicePoint, centerRequest, onMove = { moved ->
                    browsePoint = moved.takeIf { kotlin.math.abs(it.latitude - point.latitude) + kotlin.math.abs(it.longitude - point.longitude) > .004 }
                }, modifier = Modifier.fillMaxSize().padding(bottom = peekHeight), onSelect = { selectedId = it.id; menu = Menu.DETAIL })
                Column(Modifier.padding(16.dp)) {
                    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 5.dp) {
                        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 15.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("openfuel", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                            Spacer(Modifier.width(12.dp)); VerticalDivider(Modifier.height(24.dp))
                            androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = context.getString(R.string.search) },
                                textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 13.sp),
                                decorationBox = { inner -> if (query.isEmpty()) Text(stringResource(R.string.search), color = Muted, fontSize = 13.sp); inner() })
                            IconButton(onClick = { menu = Menu.SETTINGS }, modifier = Modifier.testTag("open-settings")) { Icon(Icons.Default.Tune, stringResource(R.string.settings), tint = Forest) }
                        }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .96f), modifier = Modifier.padding(top = 8.dp).clickable { menu = Menu.LOCATION }.testTag("search-area")) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp), tint = Forest)
                            Spacer(Modifier.width(5.dp)); Text(if (locating) "Finding your location…" else point.label, fontSize = 12.sp)
                            Icon(Icons.Default.KeyboardArrowDown, "Choose city", Modifier.size(16.dp))
                        }
                    }
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Grade.entries.forEach { fuel -> FilterChip(selected = grade == fuel, onClick = { grade = fuel }, label = { Text(gradeLabel(fuel)) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = Color.White, selectedContainerColor = Forest, selectedLabelColor = Color.White),
                            shape = CircleShape) }
                        FilterChip(selected = savedOnly, onClick = { savedOnly = !savedOnly }, label = { Icon(Icons.Default.BookmarkBorder, stringResource(R.string.saved), Modifier.size(18.dp)) }, shape = CircleShape)
                    }
                }
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 15.dp, bottom = peekHeight+16.dp), horizontalAlignment = Alignment.End) {
                    browsePoint?.let { area ->
                        Button(onClick = { refresh(area) }, modifier = Modifier.testTag("search-map-area")) { Text("Search this area") }
                    }
                    FilledTonalIconButton(onClick = { requestLocation() }, modifier = Modifier.testTag("use-location")) {
                        if (locating) CircularProgressIndicator(Modifier.size(20.dp)) else Icon(Icons.Default.MyLocation, "Use my location")
                    }
                    FilledTonalIconButton(onClick = { menu = Menu.ABOUT }) { Icon(Icons.Default.Info, stringResource(R.string.about)) }
                }
                Text("© OpenStreetMap contributors", fontSize = 10.sp, color = Ink,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 15.dp, bottom = peekHeight+13.dp).background(Color.White.copy(alpha = .9f), RoundedCornerShape(5.dp)).clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }.padding(5.dp))
            }
        }
    }
    if (locationIntro) AlertDialog(onDismissRequest = { locationIntro = false; prefs.edit().putBoolean("location-intro-seen", true).apply() },
        title = { Text("Find fuel around you") },
        text = { Text("Use your foreground location to find real nearby stations. Your search coordinates go to OpenFuel; map tiles are supplied by OpenStreetMap. No background tracking. You can also choose a city.") },
        confirmButton = { TextButton(onClick = { requestLocation() }, modifier = Modifier.testTag("allow-location")) { Text("Use my location") } },
        dismissButton = { TextButton(onClick = { locationIntro = false; prefs.edit().putBoolean("location-intro-seen", true).apply(); menu = Menu.LOCATION }) { Text("Choose a city") } })
    if (menu != null) {
        ModalBottomSheet(onDismissRequest = { menu = null }, containerColor = Color.White,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
                when (menu) {
                    Menu.LOCATION -> LocationSearch(repository, locationMessage, dismiss = { menu = null }, useLocation = { menu = null; requestLocation() }) {
                        devicePoint = null; locationMessage = null; menu = null; refresh(it)
                    }
                    Menu.SORT -> {
                        SheetTitle(stringResource(R.string.sort_stations)) { menu = null }
                        SortMode.entries.forEach { mode -> OptionRow(sortLabel(mode), sortHelp(mode), sort == mode) { sort = mode; menu = null } }
                    }
                    Menu.SETTINGS -> SettingsContent(filters, provider, fullWidth,
                        dismiss = { menu = null }, apply = { f, p, wide ->
                            filters = f; provider = p; fullWidth = wide
                            prefs.edit().putString("maps", if (p == MapProvider.GOOGLE) "google" else "ask").putBoolean("wide", wide).apply()
                            menu = null
                        })
                    Menu.ABOUT -> {
                        SheetTitle(stringResource(R.string.about)) { menu = null }
                        Text(stringResource(R.string.about_body), lineHeight = 24.sp, color = Muted)
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.drafts_count, drafts.size), fontWeight = FontWeight.SemiBold)
                        drafts.forEach { draft -> Text("${draft.name} · ${draft.kind.name.lowercase().replace('_', ' ')}", Modifier.padding(top = 10.dp), fontSize = 12.sp) }
                        TextButton(onClick = { runCatching { draftStore.clear() }.onSuccess { drafts.clear() }.onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.local_storage_error)) } } }) { Text(stringResource(R.string.delete_local_drafts)) }
                    }
                    Menu.DETAIL -> selected?.let { s ->
                        SheetTitle(s.name) { menu = null }
                        Text(s.address, color = Muted); Spacer(Modifier.height(18.dp))
                        Price(s.price(grade, filters.members), 42)
                        Text(ageLabel(s.age(grade)), fontSize = 12.sp, color = Muted)
                        if (s.price(grade) != null) Text("Community report · unverified", fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = { go(s) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                            Icon(Icons.Default.Navigation, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_maps))
                        }
                        Text(stringResource(R.string.sample_handoff), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { reportError = null; menu = Menu.PRICE }, Modifier.weight(1f)) { Text(stringResource(R.string.report_price)) }
                            OutlinedButton(onClick = { save(s) }, Modifier.weight(1f)) { Text(stringResource(if (s.id in favorites) R.string.unsave else R.string.save)) }
                        }
                        TextButton(onClick = { menu = Menu.CORRECTION }) { Text(stringResource(R.string.suggest_correction)) }
                    }
                    Menu.PRICE -> selected?.let { s -> PriceForm(s, grade, submitting, reportError, close = { if (!submitting) menu = null }) { value ->
                        if (!submitting) scope.launch {
                            submitting = true
                            reportError = null
                            val reportedGrade = grade
                            runCatching { repository.report(s, reportedGrade, value) }.onSuccess {
                                stations = it
                                syncState = "connected"
                                menu = Menu.DETAIL
                                snackbar.showSnackbar(context.getString(R.string.report_saved))
                            }.onFailure {
                                reportError = if (it is PrototypeApiException) it.message else context.getString(R.string.report_failed)
                                if (it !is PrototypeApiException) syncState = "offline"
                            }
                            submitting = false
                        }
                    } }
                    Menu.NEW_STATION, Menu.CORRECTION -> ProposalForm(if (menu == Menu.CORRECTION) selected else null,
                        close = { menu = null }, submit = { draft ->
                            runCatching { draftStore.save(draft) }.onSuccess { drafts.clear(); drafts.addAll(draftStore.load()); menu = Menu.ABOUT }
                                .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.local_storage_error)) } }
                        })
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun StationRow(s: Station, grade: Grade, filters: Filters, cards: Boolean, wide: Boolean, best: Boolean, select: () -> Unit, go: () -> Unit) {
    Surface(onClick = select, modifier = Modifier.testTag("station-${s.id}"), shape = RoundedCornerShape(15.dp), color = if (best) Color(0xFFF6FAF0) else Color.White,
        border = BorderStroke(1.dp, if (best) Rule else Color(0xFFF1F3EC))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).background(Pale, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text(if (s.name == "Petro-Canada") "PC" else s.name.take(1), color = Forest, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(FuelCore.distanceText(s.distanceMetres) + (if (!cards) " · ${s.address}" else ""), fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cards) Text(s.address, fontSize = 11.sp, color = Muted)
                    if (filters.members && s.memberDiscount > 0) Text(stringResource(R.string.member_price), fontSize = 10.sp, color = Muted)
                }
                if (!cards) Column(horizontalAlignment = Alignment.End) {
                    Price(s.price(grade, filters.members), 24)
                    Text(ageLabel(s.age(grade)), fontSize = 9.sp, color = if (s.age(grade) > 60) Color(0xFF927743) else Muted)
                }
                if (!wide && !cards) GoButton(best, go)
            }
            if (cards) Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Price(s.price(grade, filters.members), 36); Text(ageLabel(s.age(grade)), fontSize = 11.sp, color = Muted) }
                if (!wide) GoButton(best, go)
            }
            if (wide) OutlinedButton(onClick = go, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.Navigation, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_maps))
            }
        }
    }
}
@Composable private fun GoButton(best: Boolean, action: () -> Unit) {
    Surface(onClick = action, color = if (best) Forest else Pale, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(48.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Navigation, null, Modifier.size(20.dp), tint = if (best) Color.White else Forest)
            Text(stringResource(R.string.go), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (best) Color.White else Forest)
        }
    }
}
@Composable private fun Price(value: Int?, fontSize: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value?.let(FuelCore::priceText) ?: "—", fontSize = fontSize.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Bold, color = Forest)
        Text(" ¢/L", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
    }
}
@Composable private fun SheetTitle(title: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = close) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
    }
}
@Composable private fun OptionRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = if (selected) Pale else Color.White,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, fontSize = 12.sp, color = Muted) }
            RadioButton(selected, onClick = null)
        }
    }
}
@Composable private fun gradeLabel(g: Grade) = stringResource(when (g) { Grade.REGULAR -> R.string.regular; Grade.PREMIUM -> R.string.premium; Grade.DIESEL -> R.string.diesel })
@Composable private fun sortLabel(s: SortMode) = stringResource(when (s) { SortMode.BEST -> R.string.best_price; SortMode.PRICE -> R.string.lowest_price; SortMode.NEAREST -> R.string.nearest })
@Composable private fun sortHelp(s: SortMode) = stringResource(when (s) { SortMode.BEST -> R.string.best_help; SortMode.PRICE -> R.string.price_help; SortMode.NEAREST -> R.string.nearest_help })
@Composable private fun ageLabel(age: Int) = if (age == Int.MAX_VALUE) "No report yet" else if (age >= 60) stringResource(R.string.reported_hours, age / 60) else stringResource(R.string.reported_minutes, age)

private fun openMaps(context: Context, station: Station, provider: MapProvider): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FuelCore.directionsUrl(station)))
    return try {
        if (provider == MapProvider.GOOGLE) {
            try { context.startActivity(Intent(intent).setPackage("com.google.android.apps.maps")) }
            catch (_: ActivityNotFoundException) { context.startActivity(intent) }
        } else context.startActivity(Intent.createChooser(intent, context.getString(R.string.choose_maps)))
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}

@Composable private fun SettingsContent(initial: Filters, initialProvider: MapProvider, initialWide: Boolean, dismiss: () -> Unit, apply: (Filters, MapProvider, Boolean) -> Unit) {
    var f by remember { mutableStateOf(initial) }; var provider by remember { mutableStateOf(initialProvider) }; var wide by remember { mutableStateOf(initialWide) }
    SheetTitle(stringResource(R.string.settings), dismiss)
    Text(stringResource(R.string.open_with), fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(provider == MapProvider.GOOGLE, { provider = MapProvider.GOOGLE }, label = { Text("Google Maps") })
        FilterChip(provider == MapProvider.ASK, { provider = MapProvider.ASK }, label = { Text(stringResource(R.string.ask_each_time)) })
    }
    Text(stringResource(R.string.maps_note), fontSize = 11.sp, color = Muted)
    Spacer(Modifier.height(22.dp)); Text(stringResource(R.string.button_layout), fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!wide, { wide = false }, label = { Text(stringResource(R.string.beside_price)) })
        FilterChip(wide, { wide = true }, label = { Text(stringResource(R.string.full_width)) })
    }
    Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.distance), fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2000,5000,10000).forEach { radius -> FilterChip(f.radiusMetres == radius, { f = f.copy(radiusMetres = radius) }, label = { Text("${radius / 1000} km") }) } }
    SettingSwitch(stringResource(R.string.recent), f.fresh) { f = f.copy(fresh = it) }
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { f = Filters(); provider = MapProvider.GOOGLE; wide = false }, Modifier.weight(1f)) { Text(stringResource(R.string.reset)) }
        Button(onClick = { apply(f,provider,wide) }, Modifier.weight(1f)) { Text(stringResource(R.string.apply)) }
    }
}
@Composable private fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked,onCheckedChange = change)
    }
}
@Composable private fun PriceForm(s: Station, grade: Grade, submitting: Boolean, error: String?, close: () -> Unit, submit: (Int) -> Unit) {
    var text by remember { mutableStateOf(s.prices[grade]?.let(FuelCore::priceText) ?: "") }
    val value = FuelCore.parsePrice(text)
    SheetTitle(stringResource(R.string.report_price), close)
    Text(s.name + " · " + gradeLabel(grade)); Spacer(Modifier.height(12.dp))
    OutlinedTextField(text, { text = it.take(16) }, label = { Text(stringResource(R.string.price_label)) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = text.isNotEmpty() && value == null)
    Text(stringResource(R.string.local_price_note), Modifier.padding(vertical = 14.dp), color = Muted, fontSize = 12.sp)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp).testTag("report-error")) }
    Button(onClick = { value?.let(submit) }, enabled = value != null && !submitting, modifier = Modifier.fillMaxWidth().testTag("submit-report")) {
        if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.update_demo))
    }
}
@Composable private fun ProposalForm(s: Station?, close: () -> Unit, submit: (StationProposal) -> Unit) {
    var name by remember { mutableStateOf(s?.name ?: "") }; var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }; var kind by remember { mutableStateOf(if (s == null) ProposalKind.NEW_STATION else ProposalKind.CORRECTION) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetTitle(stringResource(if (s == null) R.string.suggest_station else R.string.suggest_correction), close)
    Text(stringResource(R.string.proposal_note), fontSize = 12.sp, color = Muted)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.station_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (s == null) {
        Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(lat, { lat = it.take(20) }, label = { Text(stringResource(R.string.latitude)) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(lon, { lon = it.take(20) }, label = { Text(stringResource(R.string.longitude)) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    } else {
        listOf(ProposalKind.CORRECTION, ProposalKind.TEMPORARY_CLOSURE, ProposalKind.PERMANENT_CLOSURE).forEach { k ->
            val title = stringResource(when(k) { ProposalKind.CORRECTION -> R.string.correction; ProposalKind.TEMPORARY_CLOSURE -> R.string.temporary_closure; else -> R.string.permanent_closure })
            Row(Modifier.fillMaxWidth().clickable { kind = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == k, { kind = k }); Text(title) }
        }
    }
    Spacer(Modifier.height(10.dp)); OutlinedTextField(note, { note = it.take(500) }, label = { Text(stringResource(R.string.evidence_note)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    Text(stringResource(R.string.no_personal_data), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        try { submit(FuelCore.newProposal(UUID.randomUUID().toString(), kind, s, name, lat.toDoubleOrNull(), lon.toDoubleOrNull(), note)) }
        catch (e: IllegalArgumentException) { error = e.message }
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_draft)) }
}

@Composable
private fun LocationSearch(repository: StationRepository, message: String?, dismiss: () -> Unit, useLocation: () -> Unit, choose: (SearchPoint) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf(SearchPoint.EDMONTON, SearchPoint(51.0447, -114.0719, "Calgary · chosen city"), SearchPoint(49.2827, -123.1207, "Vancouver · chosen city"), SearchPoint(43.6532, -79.3832, "Toronto · chosen city"))) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SheetTitle("Choose your area", dismiss)
    message?.let { Text(it, color = Muted, modifier = Modifier.padding(bottom = 12.dp)) }
    Button(onClick = useLocation, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(8.dp)); Text("Use my location") }
    OutlinedTextField(query, { query = it.take(100) }, label = { Text("Search a Canadian city") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("city-query"))
    Button(onClick = { scope.launch {
        searching = true; error = null
        runCatching { repository.searchCities(query) }.onSuccess { results = it; if (it.isEmpty()) error = "No cities found. Try a nearby city." }
            .onFailure { error = "City search unavailable. Try again or choose a city below." }
        searching = false
    } }, enabled = query.trim().length >= 2 && !searching, modifier = Modifier.testTag("search-city")) { Text(if (searching) "Searching…" else "Search") }
    error?.let { Text(it, color = Muted) }
    results.forEach { result -> TextButton(onClick = { choose(result) }, modifier = Modifier.fillMaxWidth().testTag("city-${result.label.substringBefore(" ·")}")) { Text(result.label, modifier = Modifier.fillMaxWidth()) } }
    Text("Distances are straight-line distances from this area. Pan and zoom to explore the map; choose another area here to load its stations.", fontSize = 12.sp, color = Muted)
}
