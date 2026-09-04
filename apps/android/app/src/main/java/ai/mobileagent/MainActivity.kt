package ai.mobileagent

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.mobileagent.accessibility.AccessibilityBridge
import ai.mobileagent.agent.ModelEndpoint
import ai.mobileagent.agent.ModelEndpointStore
import ai.mobileagent.benchmark.BenchmarkRunStore
import ai.mobileagent.model.AgentState
import ai.mobileagent.model.MessageRole
import ai.mobileagent.pi.PiCoreFixtureRunner
import ai.mobileagent.security.ApiKeyStore
import ai.mobileagent.session.AgentSession
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    }
    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshNotificationAccess()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val endpointStore = ModelEndpointStore(this)
        setContent {
            MaterialTheme { MobileAgentScreen(
                keyConfigured = ApiKeyStore(this).isConfigured(),
                initialEndpoint = endpointStore.load(),
                accessibilityConnected = accessibilityEnabled,
                notificationsEnabled = notificationsEnabled,
                onSaveConfiguration = { key, baseUrl, model ->
                    runCatching {
                        val endpoint = endpointStore.save(baseUrl, model)
                        ApiKeyStore(this).save(key)
                        endpoint
                    }.fold(onSuccess = { null }, onFailure = { it.message ?: "模型配置保存失败" })
                },
                onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onOpenNotifications = { openNotificationSettings() },
                onRun = { AgentSession.start(this, it) },
                onExportBenchmark = { shareBenchmark() },
            ) }
        }
        PiCoreFixtureRunner.start(this)
    }

    override fun onResume() {
        super.onResume()
        AccessibilityBridge.refresh(this)
        accessibilityEnabled = AccessibilityBridge.isEnabled(this)
        refreshNotificationAccess()
    }

    private fun refreshNotificationAccess() {
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun openNotificationSettings() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun shareBenchmark() {
        val file = BenchmarkRunStore.file(this)
        if (!file.exists()) {
            Toast.makeText(this, "还没有 benchmark 记录", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.files", file) }.getOrElse {
            Toast.makeText(this, "benchmark 导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("benchmark-runs.jsonl", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "导出 benchmark JSONL")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileAgentScreen(
    keyConfigured: Boolean,
    initialEndpoint: ModelEndpoint,
    accessibilityConnected: Boolean,
    notificationsEnabled: Boolean,
    onSaveConfiguration: (String, String, String) -> String?,
    onOpenAccessibility: () -> Unit,
    onOpenNotifications: () -> Unit,
    onRun: (String) -> Unit,
    onExportBenchmark: () -> Unit,
) {
    val state by AgentSession.state.collectAsState()
    var configured by remember { mutableStateOf(keyConfigured) }
    var editingConfiguration by remember { mutableStateOf(!keyConfigured) }
    var activeEndpoint by remember { mutableStateOf(initialEndpoint) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(initialEndpoint.baseUrl) }
    var model by remember { mutableStateOf(initialEndpoint.model) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(
        title = { Text("Mobile Agent") },
        actions = { IconButton(onClick = onExportBenchmark) {
            Icon(Icons.Default.Share, contentDescription = "导出 benchmark")
        } },
    ) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (configured) "模型已配置" else "需要模型配置", configured)
                StatusPill(if (accessibilityConnected) "设备控制已启用" else "需要无障碍权限", accessibilityConnected)
                StatusPill(if (notificationsEnabled) "执行通知已启用" else "需要通知权限", notificationsEnabled)
            }
            if (configured && !editingConfiguration) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${activeEndpoint.model} · ${activeEndpoint.host}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        baseUrl = activeEndpoint.baseUrl
                        model = activeEndpoint.model
                        editingConfiguration = true
                    }) { Text("模型设置") }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!configured || editingConfiguration) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("连接兼容 Chat Completions 的模型", style = MaterialTheme.typography.titleMedium)
                        Text("默认使用 Kimi 中国区；Key 仅保存在本机 Android Keystore。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
                        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true)
                        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                        configurationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (apiKey.isNotBlank()) {
                                    configurationError = onSaveConfiguration(apiKey.trim(), baseUrl, model)
                                    if (configurationError == null) {
                                        activeEndpoint = ModelEndpoint.parse(baseUrl, model)
                                        apiKey = ""
                                        configured = true
                                        editingConfiguration = false
                                    }
                                }
                            }, enabled = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()) { Text("保存并连接") }
                            if (configured) OutlinedButton(onClick = {
                                baseUrl = activeEndpoint.baseUrl
                                model = activeEndpoint.model
                                configurationError = null
                                editingConfiguration = false
                            }) { Text("取消") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (!accessibilityConnected) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("启用设备控制"); Text("允许 Agent 观察并操作你明确发起的任务。", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = onOpenAccessibility) { Text("去设置") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (!notificationsEnabled) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("启用执行通知")
                            Text("跨应用执行时显示当前步骤和停止入口。", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = onOpenNotifications) { Text("去开启") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Chat(state, Modifier.weight(1f))
            state.approval?.let { approval ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("需要你的确认", style = MaterialTheme.typography.titleMedium)
                        Text(approval.description)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { AgentSession.resolveApproval(true) }) { Text("允许") }
                            OutlinedButton(onClick = { AgentSession.resolveApproval(false) }) { Text("拒绝") }
                        }
                    }
                }
            }
            if (state.running) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text(state.currentStep, Modifier.weight(1f))
                    OutlinedButton(onClick = AgentSession::stop) { Text("停止") }
                }
            } else {
                Text(
                    "界面操作会打开目标 App；推理在后台进行，可从通知随时停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val canRun = prompt.isNotBlank() && !state.running && configured && accessibilityConnected && notificationsEnabled
                val submit = {
                    val value = prompt.trim()
                    if (value.isNotEmpty() && canRun) { prompt = ""; onRun(value) }
                }
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.weight(1f),
                    placeholder = { Text("例如：打开时钟，进入闹钟页面，告诉我有哪些闹钟") },
                    maxLines = 4,
                    enabled = !state.running,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                IconButton(onClick = submit, enabled = canRun) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "运行任务")
                }
            }
        }
    }
}

@Composable
private fun Chat(state: AgentState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        items(state.messages, key = { it.id }) { message ->
            val user = message.role == MessageRole.USER
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                Box(Modifier.fillMaxWidth(if (user) .86f else .94f).background(
                    when (message.role) {
                        MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
                        MessageRole.ERROR -> MaterialTheme.colorScheme.errorContainer
                        MessageRole.STATUS -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }, RoundedCornerShape(16.dp)).padding(12.dp)) { Text(message.text) }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, ok: Boolean) {
    Text(text, modifier = Modifier.background(if (ok) Color(0xFFDDF5E3) else Color(0xFFFFE7D6), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
}
