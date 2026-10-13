package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.TermuxCommandRunner
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
    val scope = rememberCoroutineScope()
    var configTestState by remember { mutableStateOf("") }
    var configTestBusy by remember { mutableStateOf(false) }
    var requestPermissionGranted by remember { mutableStateOf<Boolean?>(null) }

    val termuxInstalled = remember {
        runCatching { context.packageManager.getApplicationInfo(TERMUX_PACKAGE_NAME, 0) }.isSuccess
    }
    val hasPermission = ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestPermissionGranted = granted
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateIntegration(transform: (me.rerere.rikkahub.data.datastore.TermuxIntegrationSettings) -> me.rerere.rikkahub.data.datastore.TermuxIntegrationSettings) {
        scope.launch {
            settingsStore.update { current ->
                current.copy(termuxIntegrationSettings = transform(current.termuxIntegrationSettings).normalized())
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
                            requestPermissionGranted == false -> R.string.v2_termux_permission_missing
                            else -> R.string.v2_termux_permission_missing
                        },
                    ),
                )
                if (termuxInstalled && !hasPermission) {
                    Button(
                        onClick = { permissionLauncher.launch(TERMUX_RUN_COMMAND_PERMISSION) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(stringResource(R.string.v2_termux_request_permission))
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
                    onClick = {
                        scope.launch {
                            configTestBusy = true
                            configTestState = ""
                            val runner = TermuxCommandRunner(
                                context = context,
                                defaultTimeoutSeconds = 10,
                                callTimeoutMinutes = 1,
                                maxOutputLines = 20,
                                maxOutputBytes = 4_096,
                            )
                            val response = runCatching {
                                runner.run(
                                    "grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true([[:space:]]*(#.*)?)?$' \"\$HOME/.termux/termux.properties\" && echo enabled || { echo missing; exit 1; }",
                                    ubuntu = false,
                                )
                            }.getOrNull()
                            val exitCode = response?.get("exitCode")?.jsonPrimitive?.intOrNull
                            configTestState = when {
                                !termuxInstalled || !hasPermission || response == null -> "failed"
                                exitCode == 0 -> "ready"
                                exitCode == 1 -> "missing"
                                else -> "failed"
                            }
                            configTestBusy = false
                        }
                    },
                    enabled = termuxInstalled && hasPermission && !configTestBusy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.v2_termux_check_external_apps))
                }
                if (configTestBusy) {
                    Text(
                        stringResource(R.string.v2_termux_check_external_apps),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (configTestState.isNotBlank()) {
                    val statusRes = when (configTestState) {
                        "ready" -> R.string.v2_termux_check_external_apps_ready
                        "missing" -> R.string.v2_termux_check_external_apps_missing
                        else -> R.string.v2_termux_check_external_apps_failed
                    }
                    Text(
                        stringResource(statusRes),
                        color = if (configTestState == "ready") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
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
