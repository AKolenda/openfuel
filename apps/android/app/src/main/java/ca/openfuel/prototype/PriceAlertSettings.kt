// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

/** The Settings row that opens the price-alert settings, with what they are set to. */
@Composable
internal fun PriceAlertsRow(open: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { PriceAlerts.settings(context) }
    Surface(onClick = open, shape = RoundedCornerShape(14.dp), color = if (settings.enabled) Pale else MaterialTheme.colorScheme.surface,
        border = if (settings.enabled) null else BorderStroke(1.dp, Rule),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("open-alerts")) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (settings.enabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(if (settings.enabled) R.string.alerts_row_on else R.string.alerts_row_off), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.alerts_row_help, gradeLabel(settings.grade), FuelCore.priceText(settings.maxPrice), settings.radiusMetres / 1000),
                    fontSize = 12.sp, color = Muted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
        }
    }
}

/** The price-alert settings. Every change is saved, and the periodic check rescheduled, straight away. */
@Composable
internal fun PriceAlertSettings(close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(PriceAlerts.settings(context)) }
    var priceText by remember { mutableStateOf(FuelCore.priceText(settings.maxPrice)) }
    var missing by remember { mutableStateOf(PriceAlerts.missing(context)) }
    // A request Android answered with a refusal: further requests may not show anything, so offer its settings page.
    var refused by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AlertCheck?>(null) }
    fun update(next: AlertSettings) { settings = next; PriceAlerts.save(context, next) }

    // Permissions can change in Android's settings while this screen is open behind them.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) missing = PriceAlerts.missing(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    lateinit var requestNext: () -> Unit
    val afterRequest: (Boolean) -> Unit = { granted ->
        missing = PriceAlerts.missing(context)
        if (granted) requestNext() else refused = true
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), afterRequest)
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterRequest(it.values.any { granted -> granted }) }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), afterRequest)
    val openAppSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    requestNext = {
        when (PriceAlerts.missing(context).firstOrNull()) {
            AlertPermission.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            AlertPermission.LOCATION -> locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            AlertPermission.BACKGROUND_LOCATION -> if (Build.VERSION.SDK_INT >= 29) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            null -> Unit
        }
    }

    SheetTitle(stringResource(R.string.alerts_title), close)
    // The whole row turns alerts on or off. Its note says what turning them on allows, where that is decided.
    Row(Modifier.fillMaxWidth().toggleable(settings.enabled, role = Role.Switch) { on ->
        update(settings.copy(enabled = on))
        refused = false
        if (on && missing.isNotEmpty()) requestNext()
    }.padding(vertical = 4.dp).testTag("alerts-switch"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.alerts_switch), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.alerts_switch_help), fontSize = 12.sp, color = Muted, lineHeight = 16.sp)
        }
        Switch(settings.enabled, onCheckedChange = null)
    }

    if (settings.enabled && missing.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(when (missing.first()) {
                AlertPermission.NOTIFICATIONS -> R.string.alerts_need_notifications
                AlertPermission.LOCATION -> R.string.alerts_need_location
                AlertPermission.BACKGROUND_LOCATION -> R.string.alerts_need_background
            }), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { refused = false; requestNext() }, modifier = Modifier.testTag("alerts-allow")) { Text(stringResource(R.string.alerts_allow)) }
                if (refused) TextButton(onClick = openAppSettings) { Text(stringResource(R.string.alerts_app_settings)) }
            }
        }
    }

    // What an alert matches: one card of rows, each with its label on the left and its value on the right.
    val parsed = FuelCore.parsePrice(priceText)
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Rule), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column {
            ChoiceRow(stringResource(R.string.alerts_fuel), settings.grade, Grade.entries, { gradeLabel(it) }, "alerts-grade") {
                update(settings.copy(grade = it))
            }
            HorizontalDivider(color = Rule)
            SettingRow(stringResource(R.string.alerts_price)) {
                OutlinedTextField(priceText, { text ->
                    priceText = text.take(6)
                    FuelCore.parsePrice(priceText)?.let { if (it != settings.maxPrice) update(settings.copy(maxPrice = it)) }
                }, suffix = { Text("¢/L") }, singleLine = true, isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(132.dp).padding(vertical = 6.dp).testTag("alerts-price"))
            }
            if (parsed == null) Text(stringResource(R.string.alerts_price_error), color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            HorizontalDivider(color = Rule)
            ChoiceRow(stringResource(R.string.alerts_distance), settings.radiusMetres, AlertSettings.RADII, { "${it / 1000} km" }, "alerts-radius") {
                update(settings.copy(radiusMetres = it))
            }
            HorizontalDivider(color = Rule)
            ChoiceRow(stringResource(R.string.alerts_interval), settings.intervalMinutes, AlertSettings.INTERVALS,
                { if (it < 60) stringResource(R.string.alerts_minutes, it) else stringResource(R.string.alerts_hour) }, "alerts-interval") {
                update(settings.copy(intervalMinutes = it))
            }
            HorizontalDivider(color = Rule)
            SettingRow(stringResource(R.string.alerts_recent_only), Modifier.toggleable(settings.recentOnly, role = Role.Switch) {
                update(settings.copy(recentOnly = it))
            }.testTag("alerts-recent")) { Switch(settings.recentOnly, onCheckedChange = null) }
        }
    }

    OutlinedButton(onClick = {
        checking = true; result = null
        scope.launch { result = PriceAlerts.check(context, manual = true); checking = false }
    }, enabled = !checking && hasLocationPermission(context), modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("alerts-check")) {
        if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.alerts_check_now))
    }
    // Each result describes the settings it was checked with, even after they change.
    result?.let { r ->
        Text(when (r) {
            is AlertCheck.Found -> stringResource(if (r.notified) R.string.alerts_found_sent else R.string.alerts_found,
                r.station.name, FuelCore.priceText(r.price), FuelCore.distanceText(r.station.distanceMetres))
            is AlertCheck.NoneFound -> stringResource(R.string.alerts_none, gradeLabel(r.settings.grade),
                FuelCore.priceText(r.settings.maxPrice), "${r.settings.radiusMetres / 1000} km")
            AlertCheck.NoLocation -> stringResource(R.string.alerts_no_location)
            AlertCheck.MissingPermission -> stringResource(R.string.alerts_need_location)
            is AlertCheck.Failed -> r.message
        }, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).testTag("alerts-result"))
    }
    Text(stringResource(R.string.alerts_privacy), fontSize = 11.sp, color = Muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
}

/** A row of the alert card: [label] on the left, [value] on the right. */
@Composable
private fun SettingRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        value()
    }
}

/** A row whose value opens a menu of [options]. TalkBack reads it as one dropdown with its label and value. */
@Composable
private fun <T> ChoiceRow(label: String, selected: T, options: List<T>, name: @Composable (T) -> String, tag: String, select: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val value = name(selected)
    SettingRow(label, Modifier.clickable(role = Role.DropdownList) { open = true }
        .semantics(mergeDescendants = true) { stateDescription = value }.testTag(tag)) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Muted)
            }
            DropdownMenu(open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surface) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(name(option)) }, onClick = { open = false; if (option != selected) select(option) },
                        trailingIcon = if (option == selected) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null)
                }
            }
        }
    }
}
