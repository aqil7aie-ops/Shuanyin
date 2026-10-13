package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.listGithubRepositories
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import org.koin.compose.koinInject

@Composable
fun ConnectorsPage() {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val storageGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.v2_connectors_title),
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
            Text(
                text = stringResource(R.string.v2_connectors_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            SettingsGroup(title = stringResource(R.string.v2_connectors_title)) {
                SettingGroupItem(
                    title = stringResource(R.string.v2_connectors_github_title),
                    subtitle = stringResource(
                        if (settings.githubPat.isBlank()) R.string.v2_connector_status_unlinked
                        else R.string.v2_connector_status_token_saved,
                    ),
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    onClick = { navController.navigate(Screen.ConnectorDetail("github")) },
                )
                SettingGroupItem(
                    title = stringResource(R.string.v2_connectors_storage_title),
                    subtitle = stringResource(
                        if (storageGranted) R.string.v2_connector_status_storage_ready
                        else R.string.v2_connector_status_storage_missing,
                    ),
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    onClick = { navController.navigate(Screen.ConnectorDetail("storage")) },
                )
                SettingGroupItem(
                    title = stringResource(R.string.v2_connectors_drive_title),
                    subtitle = stringResource(R.string.v2_connector_status_unlinked),
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    onClick = { navController.navigate(Screen.ConnectorDetail("drive")) },
                )
                SettingGroupItem(
                    title = stringResource(R.string.v2_connectors_gmail_title),
                    subtitle = stringResource(R.string.v2_connector_status_unlinked),
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    onClick = { navController.navigate(Screen.ConnectorDetail("gmail")) },
                )
                SettingGroupItem(
                    title = stringResource(R.string.v2_connectors_calendar_title),
                    subtitle = stringResource(R.string.v2_connector_status_unlinked),
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    onClick = { navController.navigate(Screen.ConnectorDetail("calendar")) },
                )
                SettingGroupItem(
                    title = stringResource(R.string.v2_connectors_instagram_title),
                    subtitle = stringResource(R.string.v2_connector_status_unlinked),
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    onClick = { navController.navigate(Screen.ConnectorDetail("instagram")) },
                )
                SettingGroupItem(
                    title = stringResource(R.string.v2_connectors_facebook_title),
                    subtitle = stringResource(R.string.v2_connector_status_unlinked),
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    onClick = { navController.navigate(Screen.ConnectorDetail("facebook")) },
                )
            }
        }
    }
}

@Composable
fun ConnectorDetailPage(connectorId: String) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectorLinkSettings = settings.connectorLinkSettings
    var githubStatus by remember { mutableStateOf("") }
    var isTestingGithub by remember { mutableStateOf(false) }
    var repositories by remember { mutableStateOf(emptyList<String>()) }
    var oauthStatusTested by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val titleRes = when (connectorId) {
        "github" -> R.string.v2_connectors_github_title
        "storage" -> R.string.v2_connectors_storage_title
        "drive" -> R.string.v2_connectors_drive_title
        "gmail" -> R.string.v2_connectors_gmail_title
        "calendar" -> R.string.v2_connectors_calendar_title
        "instagram" -> R.string.v2_connectors_instagram_title
        "facebook" -> R.string.v2_connectors_facebook_title
        else -> R.string.v2_connectors_title
    }
    val descriptionRes = when (connectorId) {
        "github" -> R.string.v2_connectors_github_description
        "storage" -> R.string.v2_connectors_storage_description
        "drive" -> R.string.v2_connectors_drive_description
        "gmail" -> R.string.v2_connectors_gmail_description
        "calendar" -> R.string.v2_connectors_calendar_description
        "instagram" -> R.string.v2_connectors_instagram_description
        "facebook" -> R.string.v2_connectors_facebook_description
        else -> R.string.v2_connectors_description
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(titleRes),
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (connectorId) {
                "github" -> {
                    var patText by remember(settings.githubPat) { mutableStateOf(settings.githubPat) }
                    OutlinedTextField(
                        value = patText,
                        onValueChange = { value ->
                            patText = value
                            scope.launch {
                                settingsStore.update { current ->
                                    if (current.githubPat == value) current else current.copy(githubPat = value)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.v2_github_pat_title)) },
                        placeholder = { Text(stringResource(R.string.v2_github_pat_hint)) },
                        supportingText = { Text(stringResource(R.string.v2_github_pat_description)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                isTestingGithub = true
                                repositories = emptyList()
                                val result = listGithubRepositories(patText.trim())
                                result.fold(
                                    onSuccess = { names ->
                                        repositories = names
                                        githubStatus = context.getString(R.string.v2_github_test_success)
                                    },
                                    onFailure = { error ->
                                        githubStatus = "${context.getString(R.string.v2_github_test_failure)} ${error.message.orEmpty()}"
                                    },
                                )
                                isTestingGithub = false
                            }
                        },
                        enabled = patText.isNotBlank() && !isTestingGithub,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (isTestingGithub) R.string.v2_github_testing
                                else R.string.v2_github_test_connection,
                            )
                        )
                    }
                    if (githubStatus.isNotBlank()) {
                        Text(githubStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (repositories.isNotEmpty()) {
                        Text(stringResource(R.string.v2_github_repositories_heading), style = MaterialTheme.typography.titleSmall)
                        repositories.forEach { repository ->
                            Text(repository, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                "storage" -> {
                    Text(stringResource(R.string.v2_storage_permission_explanation))
                    val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
                    Text(
                        stringResource(
                            if (granted) R.string.v2_connector_status_storage_ready
                            else R.string.v2_connector_status_storage_missing,
                        ),
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (!granted) {
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                runCatching { context.startActivity(intent) }.onFailure {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.v2_storage_permission_grant))
                        }
                    }
                }
                "drive", "gmail", "calendar" -> {
                    OutlinedTextField(
                        value = connectorLinkSettings.googleOAuthClientId,
                        onValueChange = { value ->
                            scope.launch {
                                settingsStore.update { current ->
                                    current.copy(
                                        connectorLinkSettings = current.connectorLinkSettings.copy(googleOAuthClientId = value),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.v2_google_client_id)) },
                        singleLine = true,
                    )
                    Text(stringResource(R.string.v2_oauth_not_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { oauthStatusTested = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.v2_connector_test_connection))
                    }
                    if (oauthStatusTested) {
                        Text(stringResource(R.string.v2_connector_test_unlinked), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "instagram" -> {
                    OutlinedTextField(
                        value = connectorLinkSettings.instagramAppId,
                        onValueChange = { value ->
                            scope.launch {
                                settingsStore.update { current ->
                                    current.copy(
                                        connectorLinkSettings = current.connectorLinkSettings.copy(instagramAppId = value),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.v2_instagram_app_id)) },
                        singleLine = true,
                    )
                    Text(stringResource(R.string.v2_oauth_not_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { oauthStatusTested = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.v2_connector_test_connection))
                    }
                    if (oauthStatusTested) {
                        Text(stringResource(R.string.v2_connector_test_unlinked), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "facebook" -> {
                    OutlinedTextField(
                        value = connectorLinkSettings.facebookAppId,
                        onValueChange = { value ->
                            scope.launch {
                                settingsStore.update { current ->
                                    current.copy(
                                        connectorLinkSettings = current.connectorLinkSettings.copy(facebookAppId = value),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.v2_facebook_app_id)) },
                        singleLine = true,
                    )
                    Text(stringResource(R.string.v2_oauth_not_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { oauthStatusTested = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.v2_connector_test_connection))
                    }
                    if (oauthStatusTested) {
                        Text(stringResource(R.string.v2_connector_test_unlinked), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
