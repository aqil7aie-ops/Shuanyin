package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.TermuxIntegration
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.koin.compose.koinInject

private const val TERMUX_PACKAGE_NAME = "com.termux"
private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

@Composable
fun TermuxIntegrationPage() {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val integration = settings.termuxIntegrationSettings.normalized()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var configTestStatus by remember { mutableStateOf(TermuxExternalAppsStatus.NOT_CHECKED) }
    var configTestBusy by remember { mutableStateOf(false) }
    var termuxInstalled by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var permissionRequestDenied by remember { mutableStateOf(false) }

    suspend fun runExternalAppsProbe() {
        val installedNow = runCatching {
            context.packageManager.getApplicationInfo(TERMUX_PACKAGE_NAME, 0)
        }.isSuccess
        val permissionGrantedNow = ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED
        termuxInstalled = installedNow
        hasPermission = permissionGrantedNow
        if (permissionGrantedNow) permissionRequestDenied = false
        if (!installedNow || !permissionGrantedNow) {
            configTestStatus = resolveTermuxExternalAppsStatus(installedNow, permissionGrantedNow, null)
            return
        }
        val result = withContext(Dispatchers.IO) { TermuxIntegration.verify(context) }
        configTestStatus = when (result) {
            TermuxIntegration.VerifyResult.NotInstalled -> TermuxExternalAppsStatus.NOT_INSTALLED
            TermuxIntegration.VerifyResult.NoPermission -> TermuxExternalAppsStatus.RUN_COMMAND_PERMISSION_REQUIRED
            TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> TermuxExternalAppsStatus.ALLOW_EXTERNAL_APPS_MISSING
            TermuxIntegration.VerifyResult.Ok -> TermuxExternalAppsStatus.READY
            is TermuxIntegration.VerifyResult.UnexpectedOutput,
            is TermuxIntegration.VerifyResult.OtherError -> TermuxExternalAppsStatus.CHECK_FAILED
        }
    }

    fun queueExternalAppsProbe() {
        if (configTestBusy) return
        configTestBusy = true
        configTestStatus = TermuxExternalAppsStatus.CHECKING
        scope.launch {
            try {
                runExternalAppsProbe()
            } finally {
                configTestBusy = false
            }
        }
    }

    fun refreshTermuxState() {
        val installedNow = runCatching {
            context.packageManager.getApplicationInfo(TERMUX_PACKAGE_NAME, 0)
        }.isSuccess
        val permissionGrantedNow = ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED
        termuxInstalled = installedNow
        hasPermission = permissionGrantedNow
        if (permissionGrantedNow) permissionRequestDenied = false
        if (installedNow && permissionGrantedNow) {
            queueExternalAppsProbe()
        } else {
            configTestStatus = resolveTermuxExternalAppsStatus(installedNow, permissionGrantedNow, null)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTermuxState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refreshTermuxState()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionRequestDenied = !granted
        if (granted) queueExternalAppsProbe()
        else configTestStatus = TermuxExternalAppsStatus.RUN_COMMAND_PERMISSION_REQUIRED
    }
    val permissionAction = resolveTermuxPermissionAction(termuxInstalled, hasPermission, permissionRequestDenied)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateIntegration(transform: (me.rerere.rikkahub.data.datastore.TermuxIntegrationSettings) -> me.rerere.rikkahub.data.datastore.TermuxIntegrationSettings) {
        scope.launch {
            settingsStore.update { current ->
                val updated = transform(current.termuxIntegrationSettings).normalized()
                current.copy(
                    termuxIntegrationSettings = updated,
                    termuxEnabled = updated.enabled,
                    termuxCommandTimeoutSec = updated.defaultCommandTimeoutSeconds.coerceIn(5, 600),
                    termuxToolTimeoutMin = updated.toolCallTimeoutMinutes.coerceIn(1, 60),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.v2_termux_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            SettingsGroup(title = stringResource(R.string.v2_termux_page_title)) {
                SettingGroupItem(
                    title = stringResource(R.string.v2_termux_enabled_title),
                    subtitle = stringResource(R.string.v2_termux_enabled_description),
                    trailing = {
                        HapticSwitch(
                            checked = integration.enabled,
                            onCheckedChange = { enabled -> updateIntegration { it.copy(enabled = enabled) } },
                        )
                    },
                )
                SettingGroupItem(
                    title = stringResource(if (termuxInstalled) R.string.v2_termux_installed else R.string.v2_termux_not_installed),
                    subtitle = stringResource(
                        when {
                            hasPermission -> R.string.v2_termux_permission_granted
                            else -> R.string.v2_termux_permission_missing
                        },
                    ),
                )
                when (permissionAction) {
                    TermuxPermissionAction.HIDDEN -> Unit
                    TermuxPermissionAction.REQUEST -> {
                        Button(
                            onClick = { permissionLauncher.launch(TERMUX_RUN_COMMAND_PERMISSION) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(R.string.v2_termux_request_permission))
                        }
                    }
                    TermuxPermissionAction.OPEN_APP_SETTINGS -> {
                        Button(
                            onClick = {
                                val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                runCatching { context.startActivity(appSettingsIntent) }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(R.string.v2_termux_open_permission_settings))
                        }
                        Text(
                            text = stringResource(R.string.v2_termux_permission_settings_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (termuxInstalled) {
                    Button(
                        onClick = {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME)
                            if (launchIntent != null) context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(stringResource(R.string.v2_termux_open_app))
                    }
                }
                Text(
                    text = stringResource(R.string.v2_termux_external_apps_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Button(
                    onClick = { queueExternalAppsProbe() },
                    enabled = termuxInstalled && hasPermission && !configTestBusy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.v2_termux_check_external_apps))
                }
                val statusRes = when (configTestStatus) {
                    TermuxExternalAppsStatus.NOT_CHECKED -> R.string.v2_termux_check_external_apps_not_checked
                    TermuxExternalAppsStatus.CHECKING -> R.string.v2_termux_check_external_apps_checking
                    TermuxExternalAppsStatus.NOT_INSTALLED -> R.string.v2_termux_check_external_apps_not_installed
                    TermuxExternalAppsStatus.RUN_COMMAND_PERMISSION_REQUIRED -> R.string.v2_termux_check_external_apps_permission_required
                    TermuxExternalAppsStatus.READY -> R.string.v2_termux_check_external_apps_ready
                    TermuxExternalAppsStatus.ALLOW_EXTERNAL_APPS_MISSING -> R.string.v2_termux_check_external_apps_missing
                    TermuxExternalAppsStatus.CHECK_FAILED -> R.string.v2_termux_check_external_apps_failed
                }
                Text(
                    stringResource(statusRes),
                    color = when (configTestStatus) {
                        TermuxExternalAppsStatus.READY -> MaterialTheme.colorScheme.primary
                        TermuxExternalAppsStatus.NOT_CHECKED, TermuxExternalAppsStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            SettingsGroup(title = stringResource(R.string.v2_termux_page_title)) {
                NumericIntegrationField(
                    label = stringResource(R.string.v2_termux_default_timeout),
                    value = integration.defaultCommandTimeoutSeconds.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let { n -> updateIntegration { it.copy(defaultCommandTimeoutSeconds = n) } } },
                )
                NumericIntegrationField(
                    label = stringResource(R.string.v2_termux_call_timeout),
                    value = integration.toolCallTimeoutMinutes.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let { n -> updateIntegration { it.copy(toolCallTimeoutMinutes = n) } } },
                )
                NumericIntegrationField(
                    label = stringResource(R.string.v2_termux_max_output_lines),
                    value = integration.maxOutputLines.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let { n -> updateIntegration { it.copy(maxOutputLines = n) } } },
                )
                NumericIntegrationField(
                    label = stringResource(R.string.v2_termux_max_output_bytes),
                    value = integration.maxOutputBytes.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let { n -> updateIntegration { it.copy(maxOutputBytes = n) } } },
                )
                Text(
                    text = stringResource(R.string.v2_termux_approval_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun NumericIntegrationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.length <= 7 && next.all(Char::isDigit)) onValueChange(next) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
