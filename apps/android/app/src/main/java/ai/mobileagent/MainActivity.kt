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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import ai.mobileagent.session.AgentForegroundService
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = AgentForegroundService.notificationsAvailable(this)
    }
    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshNotificationAccess()
        val endpointStore = ModelEndpointStore(this)
        setContent {
            MaterialTheme { MobileAgentScreen(
                keyConfigured = ApiKeyStore(this).isConfigured(),
                initialEndpoint = endpointStore.load(),
                accessibilityConnected = accessibilityEnabled,
                notificationsEnabled = notificationsEnabled,
                onSaveConfiguration = { key, baseUrl, model ->
                    runCatching {
                        val endpoint = ModelEndpoint.parse(baseUrl, model)
                        val store = ApiKeyStore(this)
                        if (key.isNotBlank()) store.save(key) else require(store.isConfigured()) { "请填写模型密钥" }
                        endpointStore.save(endpoint.baseUrl, endpoint.model)
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
        notificationsEnabled = AgentForegroundService.notificationsAvailable(this)
    }

    private fun openNotificationSettings() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
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
            Toast.makeText(this, "还没有诊断记录", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.files", file) }.getOrElse {
            Toast.makeText(this, "诊断记录导出失败", Toast.LENGTH_SHORT).show()
            return
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("benchmark-runs.jsonl", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "导出诊断记录")
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
    onRun: (String) -> Boolean,
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
    var menuOpen by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(initialEndpoint.baseUrl != ModelEndpoint.DEFAULT_BASE_URL || initialEndpoint.model != ModelEndpoint.DEFAULT_MODEL) }

    Scaffold(topBar = { TopAppBar(
        title = { Text("Mobile Agent") },
        actions = {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "设置与诊断") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("新任务") }, enabled = !state.running, onClick = {
                    menuOpen = false
                    if (AgentSession.newTask()) prompt = ""
                })
                DropdownMenuItem(text = { Text("模型设置") }, enabled = !state.running, onClick = {
                    menuOpen = false
                    baseUrl = activeEndpoint.baseUrl
                    model = activeEndpoint.model
                    configurationError = null
                    editingConfiguration = true
                })
                DropdownMenuItem(text = { Text("导出诊断记录") }, onClick = { menuOpen = false; onExportBenchmark() })
            }
        },
    ) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding()) {
            Text(
                when {
                    editingConfiguration && configured -> "模型设置，仅在需要时调整。"
                    editingConfiguration -> "首次设置后，就可以直接描述任务。"
                    !accessibilityConnected -> "还需要开启设备控制"
                    !notificationsEnabled -> "还需要开启执行通知"
                    else -> "已就绪，描述你想让手机做的事"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (!configured || editingConfiguration) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("配置模型", style = MaterialTheme.typography.titleMedium)
                            Text("默认使用 Kimi。密钥仅保存在这台手机，模型请求可能产生账户费用。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置：更换模型服务") }
                            if (advanced) {
                                OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("服务地址（HTTPS）") }, singleLine = true)
                                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true)
                            }
                            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (configured) "新密钥（留空保留原密钥）" else "模型密钥（API Key）") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                            configurationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (configured || apiKey.isNotBlank()) {
                                        configurationError = onSaveConfiguration(apiKey.trim(), baseUrl, model)
                                        if (configurationError == null) {
                                            activeEndpoint = ModelEndpoint.parse(baseUrl, model)
                                            apiKey = ""
                                            configured = true
                                            editingConfiguration = false
                                        }
                                    }
                                }, enabled = (configured || apiKey.isNotBlank()) && baseUrl.isNotBlank() && model.isNotBlank()) { Text("保存设置") }
                                if (configured) OutlinedButton(onClick = {
                                    baseUrl = activeEndpoint.baseUrl
                                    model = activeEndpoint.model
                                    configurationError = null
                                    apiKey = ""
                                    editingConfiguration = false
                                }) { Text("取消") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (!editingConfiguration) {
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
                if (state.messages.isEmpty() && configured && accessibilityConnected && notificationsEnabled) {
                    OutlinedButton(onClick = { prompt = "打开时钟，进入秒表页，再返回闹钟页。" }, modifier = Modifier.padding(vertical = 12.dp)) {
                        Text("试试：切换时钟页签")
                    }
                }
                Chat(state, Modifier.weight(1f))
                state.approval?.let { approval ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("需要你的确认", style = MaterialTheme.typography.titleMedium)
                            if (approval.action.isNotBlank() || approval.target.isNotBlank() || approval.consequence.isNotBlank()) {
                                ApprovalRow("做什么", approval.action)
                                ApprovalRow("对谁", approval.target)
                                ApprovalRow("后果", approval.consequence)
                            } else {
                                Text(approval.description)
                            }
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        "操作时会切到目标应用。你可以从通知停止任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val canRun = prompt.isNotBlank() && !state.running && configured && accessibilityConnected && notificationsEnabled
                    val submit = {
                        val value = prompt.trim()
                        if (value.isNotEmpty() && canRun && onRun(value)) { prompt = "" }
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
private fun ApprovalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
