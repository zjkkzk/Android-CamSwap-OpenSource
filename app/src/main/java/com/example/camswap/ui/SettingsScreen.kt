package com.example.camswap.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.camswap.BuildConfig
import com.example.camswap.R
import com.example.camswap.ui.MainViewModel

@Composable
fun SettingSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle, 
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // General Settings
        SettingsGroup(title = stringResource(R.string.settings_category_general)) {
            SettingSwitchItem(
                title = stringResource(R.string.settings_notification_control),
                subtitle = stringResource(R.string.settings_notification_control_desc),
                checked = uiState.notificationControlEnabled,
                onCheckedChange = { 
                    viewModel.setNotificationControlEnabled(it)
                    val intent = Intent(context, com.example.camswap.NotificationService::class.java)
                    if (it) {
                        context.startForegroundService(intent)
                    } else {
                        context.stopService(intent)
                    }
                }
            )
            SettingSwitchItem(
                title = stringResource(R.string.settings_play_sound),
                subtitle = stringResource(R.string.settings_play_sound_desc),
                checked = uiState.playVideoSound,
                onCheckedChange = { viewModel.setPlayVideoSound(it) }
            )
            SettingSwitchItem(
                title = stringResource(R.string.settings_mic_hook),
                subtitle = stringResource(R.string.settings_mic_hook_desc),
                checked = uiState.enableMicHook,
                onCheckedChange = { viewModel.setEnableMicHook(it) }
            )
            // 麦克风 Hook 模式选择（仅在开关开启时显示）
            androidx.compose.animation.AnimatedVisibility(visible = uiState.enableMicHook) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setMicHookMode("mute") }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = uiState.micHookMode == "mute",
                            onClick = { viewModel.setMicHookMode("mute") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.mic_mode_mute),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.mic_mode_mute_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setMicHookMode("replace") }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = uiState.micHookMode == "replace",
                            onClick = { viewModel.setMicHookMode("replace") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.mic_mode_replace),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.mic_mode_replace_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setMicHookMode("video_sync") }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = uiState.micHookMode == "video_sync",
                            onClick = { viewModel.setMicHookMode("video_sync") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.mic_mode_video_sync),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.mic_mode_video_sync_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            SettingSwitchItem(
                title = stringResource(R.string.settings_random_play),
                subtitle = stringResource(R.string.settings_random_play_desc),
                checked = uiState.enableRandomPlay,
                onCheckedChange = { viewModel.setEnableRandomPlay(it) }
            )
        }

        // Advanced Settings
        SettingsGroup(title = stringResource(R.string.settings_category_advanced)) {
            SettingSwitchItem(
                title = stringResource(R.string.settings_disable_module),
                subtitle = stringResource(R.string.settings_disable_module_desc),
                checked = uiState.isModuleDisabled,
                onCheckedChange = { viewModel.setModuleDisabled(it) }
            )
            SettingSwitchItem(
                title = "强制显示警告",
                subtitle = "在Hook成功时显示Toast提示",
                checked = uiState.forceShowWarning,
                onCheckedChange = { viewModel.setForceShowWarning(it) }
            )
            SettingSwitchItem(
                title = "强制私有目录",
                subtitle = "解决部分应用无法读取文件的问题",
                checked = uiState.forcePrivateDir,
                onCheckedChange = { viewModel.setForcePrivateDir(it) }
            )
            SettingSwitchItem(
                title = "禁用Toast",
                subtitle = "隐藏所有操作提示",
                checked = uiState.disableToast,
                onCheckedChange = { viewModel.setDisableToast(it) }
            )
            
            ListItem(
                headlineContent = { Text("系统权限设置") },
                supportingContent = { Text("管理应用的存储和通知权限") },
                modifier = Modifier.clickable {
                     val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                         data = Uri.fromParts("package", context.packageName, null)
                     }
                     context.startActivity(intent)
                }
            )
        }

// ...

        // About
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "关于",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Android CamSwap")
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (BuildConfig.BUILD_TIME.isNotEmpty()) {
                    Text(
                        text = "Build Time: ${BuildConfig.BUILD_TIME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "基于 Xposed 框架的虚拟摄像头模块",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
