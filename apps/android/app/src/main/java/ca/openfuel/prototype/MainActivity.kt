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
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** How long stations loaded for the area on screen answer a location fix in the same cell. */
internal const val FIX_RELOAD_MS = 60_000L

/**
 * Whether a location fix in the 0.01° cell on screen keeps its stations and only moves the search origin:
 * while they load, for the silent fix at startup (its cell was just requested), for [FIX_RELOAD_MS] after
 * they loaded ([loadedAt]), and until the daily limit resets. Otherwise the fix asks for the cell again.
 */
internal fun fixKeepsStations(syncing: Boolean, syncState: String, silent: Boolean, loadedAt: Long, limitResetsAt: Instant, now: Long): Boolean =
    syncing || when (syncState) {
        "connected" -> silent || now - loadedAt in 0 until FIX_RELOAD_MS
        "limited" -> silent || now < limitResetsAt.toEpochMilli()
        else -> false
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The saved snapshot is parsed, and its area's fresh stations requested, off the UI thread while the UI starts.
        val startup = StationRepository.startup(applicationContext)
        super.onCreate(savedInstanceState)
        // The app has a light surface even when Android's system theme is dark.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White,
                background = Color.White, surface = Color.White, onSurface = Ink, outlineVariant = Rule,
                secondary = Forest, onSecondary = Color.White, secondaryContainer = Pale, onSecondaryContainer = Forest)) {
                val ready = startup.collectAsState().value
                if (ready != null) OpenFuelApp(ready) else Box(Modifier.fillMaxSize().background(Color(MAP_BACKGROUND)))
            }
        }
    }

    // Responses already in the HTTP cache stay usable if the process is stopped in the background.
    override fun onStop() { super.onStop(); StationRepository.flushHttpCache() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenFuelApp(startup: StationRepository.Startup) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("openfuel-prototype", Context.MODE_PRIVATE) }
    val repository = remember { StationRepository(context) }
    val initial = startup.snapshot
    var stations by remember { mutableStateOf(initial.stations) }
    var syncState by remember { mutableStateOf(if (initial.cached) "cached" else "choose-area") }
    var hasSearchArea by remember { mutableStateOf(initial.point.source != SearchSource.OVERVIEW) }
    var point by remember { mutableStateOf(initial.point) }
    var browse by remember { mutableStateOf<MapMove?>(null) } // The map's view after the last pan or zoom, until an area loads.
    var centerRequest by remember { mutableIntStateOf(0) }
    var devicePoint by remember { mutableStateOf<SearchPoint?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationIntro by remember { mutableStateOf(!prefs.getBoolean("location-intro-seen", false)) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var locationJob by remember { mutableStateOf<Job?>(null) }
    var selectionVersion by remember { mutableIntStateOf(0) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var locationVersion by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var loadedAt by remember { mutableLongStateOf(0L) } // When the last load succeeded, in epoch milliseconds.
    var limitResetsAt by remember { mutableStateOf(Instant.EPOCH) } // Shown while syncState is "limited".
    var submitting by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    var reportLimited by remember { mutableStateOf(false) }
    var grade by rememberSaveable { mutableStateOf(Grade.entries.find { it.name == prefs.getString("fuel-grade", "REGULAR") } ?: Grade.REGULAR) }
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
    var stackIds by rememberSaveable { mutableStateOf(emptyList<String>()) } // A tapped map chip's stations, front first.
    var baseMap by remember { mutableStateOf<String?>(null) } // "openfreemap" or "openstreetmap" once shown.
    val chrome = remember { MapChrome() }
    val draftStore = remember { LocalDraftStore(context) }
    val loadedDrafts = remember { runCatching { draftStore.load() } }
    val drafts = remember { mutableStateListOf<StationProposal>().apply { addAll(loadedDrafts.getOrDefault(emptyList())) } }
    val visible = remember(stations, grade, sort, filters, query, savedOnly, favorites) { FuelCore.visible(stations, grade, sort, filters, query, savedOnly, favorites) }
    val brandLogos = rememberBrandLogos(stations) // The area's logos, so filtering the list loads none again.
    val bestId = visible.filter { it.price(grade) != null && it.age(grade) <= 60 }.minByOrNull { it.price(grade, filters.members)!! }?.id
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.maps_unavailable)
    val linkUnavailable = stringResource(R.string.link_unavailable)
    LaunchedEffect(Unit) { if (loadedDrafts.isFailure) snackbar.showSnackbar(context.getString(R.string.local_storage_error)) }
    // Official builds may name a donation page for the database and map costs; without one no donate UI appears.
    val donate: (() -> Unit)? = remember(context, linkUnavailable) { BuildConfig.DONATE_URL.takeIf { it.isNotEmpty() }?.let { url -> {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { scope.launch { snackbar.showSnackbar(linkUnavailable) } }
    } } }
    fun refresh(next: SearchPoint = point, explicit: Boolean = true, recenter: Boolean = true, fresh: Boolean = false,
                preloaded: Deferred<List<Station>>? = null) {
        if (explicit) selectionVersion++
        val version = ++refreshVersion
        hasSearchArea = true
        refreshJob?.cancel()
        val changedArea = !next.sameCell(point)
        point = next
        repository.rememberArea(next)
        browse = null
        if (recenter && (changedArea || explicit)) centerRequest++
        if (changedArea) stations = emptyList()
        refreshJob = scope.launch {
            syncing = true
            try {
                // Requests for one 0.01° cell share an answer, including the one started before the first frame.
                val loaded = (preloaded ?: repository.prefetch(next, fresh)).await()
                if (version != refreshVersion) return@launch
                // A GPS fix in the same cell may have moved the search origin while this loaded.
                val area = point
                stations = if (area.latitude == next.latitude && area.longitude == next.longitude) loaded else loaded.measuredFrom(area)
                syncState = "connected"; loadedAt = System.currentTimeMillis()
                // The snapshot is written off the UI thread, one write at a time in the order the loads finished.
                try {
                    withContext(StationRepository.cacheDispatcher) { repository.cache(loaded, area, rememberSearchArea = false) }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { scope.launch { snackbar.showSnackbar(context.getString(R.string.local_storage_error)) } }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: ServiceLimitException) {
                // Saved stations stay on screen under the daily-limit notice.
                if (version == refreshVersion) { limitResetsAt = error.resetsAt; syncState = "limited" }
            }
            catch (error: Exception) {
                if (version == refreshVersion) syncState = "offline"
            } finally { if (version == refreshVersion) syncing = false }
        }
    }
    var areaRequest by remember { mutableIntStateOf(0) } // Raised to open the city search in the top controls.
    /** Says why the location cannot be used, with a way to choose a city instead. */
    fun locationFailed(message: Int) {
        scope.launch {
            val result = snackbar.showSnackbar(context.getString(message), actionLabel = context.getString(R.string.choose_city), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) areaRequest++
        }
    }
    fun locate(recenter: Boolean = true, silent: Boolean = false) {
        if (locating && silent) return
        locationJob?.cancel()
        val version = ++locationVersion
        val selectedWhenStarted = selectionVersion
        locationJob = scope.launch {
            locating = true
            try {
                val fix = currentSearchPoint(context)
                if (version != locationVersion) return@launch
                if (fix != null) {
                    devicePoint = fix
                    if (recenter && selectionVersion == selectedWhenStarted) {
                        // A fix inside the area on screen moves the search origin; its stations load again only when old.
                        val sameCell = hasSearchArea && fix.sameCell(point)
                        if (sameCell && fixKeepsStations(syncing, syncState, silent, loadedAt, limitResetsAt, System.currentTimeMillis())) {
                            point = fix; repository.rememberArea(fix); browse = null
                            stations = stations.measuredFrom(fix)
                        } else refresh(fix, explicit = false)
                        if (sameCell && !silent) centerRequest++
                    }
                } else if (!silent && selectionVersion == selectedWhenStarted) {
                    locationFailed(R.string.location_off)
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                if (!silent && selectionVersion == selectedWhenStarted) locationFailed(R.string.location_failed)
            } finally { if (version == locationVersion) locating = false }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) locate()
        else locationFailed(R.string.location_declined)
    }
    fun requestLocation() {
        selectionVersion++
        locationIntro = false
        prefs.edit().putBoolean("location-intro-seen", true).apply()
        if (hasLocationPermission(context)) locate()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) {
        if (hasSearchArea) refresh(explicit = false, preloaded = startup.fresh)
        if (!locationIntro && hasLocationPermission(context)) locate(recenter = point.source == SearchSource.DEVICE || !hasSearchArea, silent = hasSearchArea)
    }
    fun go(station: Station) {
        if (!openMaps(context, station, provider)) scope.launch { snackbar.showSnackbar(unavailable) }
    }
    fun save(station: Station) {
        favorites = if (station.id in favorites) favorites - station.id else favorites + station.id
        prefs.edit().putStringSet("favorites", favorites).apply()
    }
    /** Opens a station's details, from the list or the map. [stack] is every station at a tapped map chip, front first; the details offer the others. */
    fun openDetail(station: Station, stack: List<Station> = emptyList()) {
        selectedId = station.id; stackIds = stack.map { it.id }; menu = Menu.DETAIL
    }
    /**
     * The map was panned or zoomed. A move far enough to offer "Search here" keeps a pending location fix from
     * replacing it. Only the top controls read [browse], so a pan does not recompose the whole screen.
     */
    fun onMapMove(move: MapMove) {
        if (mapMovedAway(point, move)) selectionVersion++
        browse = move
    }
    fun openReport() {
        reportError = null; reportLimited = false; menu = Menu.PRICE
    }
    fun report(s: Station, value: Int) {
        if (!submitting) scope.launch {
            submitting = true
            reportError = null; reportLimited = false
            val reportedGrade = grade
            runCatching { repository.report(s, reportedGrade, value) }.onSuccess {
                stations = it
                syncState = "connected"
                menu = Menu.DETAIL
                snackbar.showSnackbar(context.getString(R.string.report_saved))
            }.onFailure {
                reportLimited = it is ServiceLimitException
                reportError = when (it) {
                    is ServiceLimitException -> context.getString(R.string.limit_report, resetTime(context, it.resetsAt))
                    is PrototypeApiException -> it.message
                    else -> context.getString(R.string.report_failed)
                }
                if (it !is PrototypeApiException && it !is ServiceLimitException) syncState = "offline"
            }
            submitting = false
        }
    }
    fun saveDraft(draft: StationProposal) {
        runCatching { draftStore.save(draft) }.onSuccess { drafts.clear(); drafts.addAll(draftStore.load()); menu = Menu.ABOUT }
            .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.local_storage_error)) } }
    }
    val sheet = rememberStationSheetState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().semantics { testTagsAsResourceId = true }.testTag("native-root")) {
        // The station sheet over the map. It rests at COLLAPSED, HALF or FULL and follows the finger between them.
        StationSheet(
            sheet = sheet,
            chrome = chrome,
            area = point,
            sheetContent = { handle, header, list ->
                StationSheetContent(
                    handle = handle,
                    header = header,
                    list = list,
                    grade = grade,
                    savedOnly = savedOnly,
                    visible = visible,
                    sort = sort,
                    changeSort = { sort = it },
                    cards = cards,
                    onCardsChange = { cards = it },
                    syncState = syncState,
                    limitResetsAt = limitResetsAt,
                    hasStations = stations.isNotEmpty(),
                    donate = donate,
                    syncing = syncing,
                    onRefresh = { if (hasSearchArea && !syncing) refresh(recenter = false, fresh = true) },
                    resetFilters = { query = ""; filters = Filters(); savedOnly = false },
                    showAll = { savedOnly = false },
                    filters = filters,
                    fullWidth = fullWidth,
                    bestId = bestId,
                    brandLogos = brandLogos,
                    select = { openDetail(it) },
                    go = { go(it) },
                    suggestStation = { selectedId = null; menu = Menu.NEW_STATION }
                )
            }
        ) {
            Box(Modifier.fillMaxSize()) {
                // No base map is loaded until there is an area to show.
                if (hasSearchArea) LiveMap(
                    stations = visible,
                    grade = grade,
                    bestId = bestId,
                    center = point,
                    currentLocation = devicePoint,
                    centerRequest = centerRequest,
                    brandLogos = brandLogos,
                    onMove = { onMapMove(it) },
                    onBaseMap = { baseMap = it },
                    modifier = Modifier.fillMaxSize().testTag("station-map"),
                    // The map centres itself between the top controls and the half-open sheet, and stops drawing while covered.
                    padding = with(LocalDensity.current) { { PaddingValues(top = chrome.topControlsBottomPx.toDp(), bottom = (this@BoxWithConstraints.constraints.maxHeight - chrome.halfTopPx).toDp()) } },
                    visible = { !chrome.mapCovered },
                    onSelect = { station, stack -> openDetail(station, stack) }
                )
                // Under the top controls the map fades to its background colour as the sheet reaches FULL.
                SheetScrim(sheet)
                TopControls(
                    chrome = chrome,
                    query = query,
                    onQueryChange = { query = it },
                    area = point,
                    browse = { browse },
                    syncing = syncing,
                    offline = syncState == "offline",
                    locating = locating,
                    areaRequest = areaRequest,
                    searchCities = { repository.searchCities(it) },
                    savedOnly = savedOnly,
                    onSavedOnlyChange = { savedOnly = it },
                    openSettings = { menu = Menu.SETTINGS },
                    useLocation = { requestLocation() },
                    searchArea = { refresh(it, recenter = false) },
                    chooseCity = { refresh(it) }
                )
            }
        }
        // Map credit, location button and snackbar, which travel with the station sheet.
        SheetOverlays(sheet, chrome, snackbar, baseMap, locating, requestLocation = { requestLocation() })
    }
    if (locationIntro) AlertDialog(onDismissRequest = { locationIntro = false; prefs.edit().putBoolean("location-intro-seen", true).apply() },
        title = { Text(stringResource(R.string.intro_title)) },
        text = { Text(stringResource(R.string.intro_body)) },
        confirmButton = { TextButton(onClick = { requestLocation() }, modifier = Modifier.testTag("allow-location")) { Text(stringResource(R.string.intro_use_location)) } },
        dismissButton = { TextButton(onClick = { locationIntro = false; prefs.edit().putBoolean("location-intro-seen", true).apply(); areaRequest++ }) { Text(stringResource(R.string.choose_city)) } })
    MenuHost(menu = { menu }, stationId = { selectedId }, dismiss = { menu = null }) { shown, stationId, closeMenu ->
        MenuBody(
            menu = shown,
            closeMenu = closeMenu,
            openMenu = { menu = it },
            filters = filters,
            provider = provider,
            fullWidth = fullWidth,
            grade = grade,
            changeGrade = { grade = it; prefs.edit().putString("fuel-grade", it.name).apply() },
            donate = donate,
            refreshNow = { if (hasSearchArea && !syncing) refresh(recenter = false, fresh = true) },
            applySettings = { f, p, wide ->
                filters = f; provider = p; fullWidth = wide
                prefs.edit().putString("maps", if (p == MapProvider.GOOGLE) "google" else "ask").putBoolean("wide", wide).apply()
            },
            drafts = drafts,
            clearDrafts = {
                runCatching { draftStore.clear() }.onSuccess { drafts.clear() }
                    .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.local_storage_error)) } }
            },
            selected = stations.find { it.id == stationId },
            favorites = favorites,
            go = { go(it) },
            save = { save(it) },
            openReport = { openReport() },
            submitting = submitting,
            reportError = reportError,
            reportLimited = reportLimited,
            report = { s, value -> report(s, value) },
            saveDraft = { saveDraft(it) },
            stack = stackIds.mapNotNull { id -> stations.find { it.id == id } },
            brandLogos = brandLogos,
            showStation = { selectedId = it.id }
        )
    }
}

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
