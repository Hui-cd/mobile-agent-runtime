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
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ai.mobileagent.ui.GlacierTheme
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
    private var hiddenEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        refreshNotificationAccess()
        hiddenEnabled = ai.mobileagent.virtualdisplay.HiddenExecutionSession.enabled(this)
        val endpointStore = ModelEndpointStore(this)
        setContent {
            GlacierTheme { MobileAgentScreen(
                keyConfigured = ApiKeyStore(this).isConfigured(),
                initialEndpoint = endpointStore.load(),
                accessibilityConnected = accessibilityEnabled,
                notificationsEnabled = notificationsEnabled,
                hiddenEnabled = hiddenEnabled,
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
                onToggleHidden = {
                    val enabled = ai.mobileagent.virtualdisplay.HiddenExecutionSession.enabled(this)
                    if (!enabled && !rikka.shizuku.Shizuku.pingBinder()) {
                        Toast.makeText(this, "请先在 Shizuku 中启动服务", Toast.LENGTH_LONG).show()
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                    } else if (!enabled && rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        rikka.shizuku.Shizuku.requestPermission(100)
                        Toast.makeText(this, "授权后再次点后台执行", Toast.LENGTH_LONG).show()
                    } else {
                        ai.mobileagent.virtualdisplay.HiddenExecutionSession.setEnabled(this, !enabled)
                        hiddenEnabled = !enabled
                        AgentSession.newTask()
                        Toast.makeText(this, if (!enabled) "已开启后台执行（Shizuku）" else "已切回主屏执行", Toast.LENGTH_LONG).show()
                    }
                },
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
    hiddenEnabled: Boolean,
    onSaveConfiguration: (String, String, String) -> String?,
    onOpenAccessibility: () -> Unit,
    onOpenNotifications: () -> Unit,
    onRun: (String) -> Boolean,
    onExportBenchmark: () -> Unit,
    onToggleHidden: () -> Unit,
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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Smartphone, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("Mobile Agent", style = MaterialTheme.typography.titleLarge)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        actions = {
            IconButton(onClick = { if (AgentSession.newTask()) prompt = "" }, enabled = !state.running) {
                Icon(Icons.Default.Add, contentDescription = "新任务", tint = MaterialTheme.colorScheme.primary)
            }
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
                DropdownMenuItem(text = { Text(if (hiddenEnabled) "后台执行已开启 · 切回主屏" else "开启后台执行（Shizuku）") }, enabled = !state.running, onClick = { menuOpen = false; onToggleHidden() })
                DropdownMenuItem(text = { Text("导出诊断记录") }, onClick = { menuOpen = false; onExportBenchmark() })
            }
        },
    ) }) { padding ->
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceContainerLow))).padding(padding).padding(horizontal = 20.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.clickable(enabled = !state.running && !editingConfiguration, onClick = onToggleHidden)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                        Text(if (hiddenEnabled) "后台模式" else "前台模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text(when {
                    editingConfiguration -> "模型设置"
                    !accessibilityConnected || !notificationsEnabled -> "需要设置"
                    state.running -> "任务进行中"
                    state.error != null -> "需要处理"
                    else -> "准备就绪"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!configured || editingConfiguration) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("配置模型", style = MaterialTheme.typography.titleMedium)
                            Text("默认使用 Kimi。密钥仅保存在这台手机，模型请求可能产生账户费用。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置：更换模型服务") }
                            if (advanced) {
                                OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("服务地址（HTTPS）") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                            }
                            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (configured) "新密钥（留空保留原密钥）" else "模型密钥（API Key）") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, shape = RoundedCornerShape(16.dp))
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
                if (state.messages.isEmpty()) {
                    EmptyTask(Modifier.weight(1f), onExample = { prompt = it })
                } else {
                    Chat(state, Modifier.weight(1f))
                }
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
                    Surface(Modifier.fillMaxWidth().padding(vertical = 10.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(state.currentStep, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = AgentSession::stop) { Text("停止") }
                        }
                    }
                } else {
                    Text(if (hiddenEnabled) "后台操作 · 主屏可以继续使用" else "前台操作 · 执行时会打开目标应用",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                }
                val canRun = prompt.isNotBlank() && !state.running && configured && accessibilityConnected && notificationsEnabled
                val submit = {
                    val value = prompt.trim()
                    if (value.isNotEmpty() && canRun && onRun(value)) prompt = ""
                }
                Surface(Modifier.fillMaxWidth().padding(bottom = 14.dp), shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 1.dp) {
                    Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(prompt, { prompt = it }, Modifier.weight(1f),
                            placeholder = { Text("描述你想完成的任务…", style = MaterialTheme.typography.bodyMedium) },
                            maxLines = 4, enabled = !state.running,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }))
                        FilledIconButton(onClick = submit, enabled = canRun, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(18.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "运行任务", modifier = Modifier.size(21.dp))
                        }
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
            if (message.role == MessageRole.STATUS) {
                Text(message.text, Modifier.padding(horizontal = 6.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val user = message.role == MessageRole.USER
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(Modifier.fillMaxWidth(if (user) .86f else .97f),
                        shape = if (user) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp),
                        color = when (message.role) {
                            MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
                            MessageRole.ERROR -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerLowest
                        }, border = if (user) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!user) Text(if (message.role == MessageRole.ERROR) "需要处理" else "Mobile Agent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(message.text, style = MaterialTheme.typography.bodyLarge,
                                color = if (message.role == MessageRole.ERROR) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
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


@Composable
private fun EmptyTask(modifier: Modifier, onExample: (String) -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("从一句指令开始", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("你想让手机\n做些什么？", style = MaterialTheme.typography.headlineLarge)
        Text("打开应用、查找内容，\n把需要完成的事交给 Agent。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Text("试试这些任务", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TaskExample(Icons.Outlined.AccessTime, "看看时钟", "切到秒表，再返回闹钟", onClick = { onExample("打开时钟，进入秒表页，再返回闹钟页。") })
        TaskExample(Icons.AutoMirrored.Outlined.ManageSearch, "查找设置", "搜索蓝牙，读出前三条结果", onClick = { onExample("打开系统设置，搜索 bluetooth，读取前三条可见结果，再返回设置首页。") })
        TaskExample(Icons.Outlined.Download, "下载应用", "提供发行方地址，下载并安装 APK", onClick = { onExample("请帮我下载并安装应用。先向我确认发行方 APK 地址和预期包名。") })
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun TaskExample(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}
