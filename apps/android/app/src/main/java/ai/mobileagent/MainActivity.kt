package ai.mobileagent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.mobileagent.accessibility.AccessibilityBridge
import ai.mobileagent.model.AgentState
import ai.mobileagent.model.MessageRole
import ai.mobileagent.security.ApiKeyStore
import ai.mobileagent.session.AgentSession

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme { MobileAgentScreen(
                keyConfigured = ApiKeyStore(this).isConfigured(),
                accessibilityConnected = accessibilityEnabled,
                onSaveKey = { ApiKeyStore(this).save(it) },
                onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onRun = { AgentSession.start(this, it) },
            ) }
        }
    }

    override fun onResume() {
        super.onResume()
        AccessibilityBridge.refresh(this)
        accessibilityEnabled = AccessibilityBridge.isEnabled(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileAgentScreen(
    keyConfigured: Boolean,
    accessibilityConnected: Boolean,
    onSaveKey: (String) -> Unit,
    onOpenAccessibility: () -> Unit,
    onRun: (String) -> Unit,
) {
    val state by AgentSession.state.collectAsState()
    var configured by remember { mutableStateOf(keyConfigured) }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Mobile Agent") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (configured) "Kimi 已连接" else "需要 API Key", configured)
                StatusPill(if (accessibilityConnected) "设备控制已启用" else "需要无障碍权限", accessibilityConnected)
            }
            Spacer(Modifier.height(12.dp))
            if (!configured) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("连接 Kimi", style = MaterialTheme.typography.titleMedium)
                        Text("Key 仅保存在本机 Android Keystore。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                        Button(onClick = { if (apiKey.isNotBlank()) { onSaveKey(apiKey.trim()); apiKey = ""; configured = true } }, enabled = apiKey.isNotBlank()) { Text("保存并连接") }
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
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(prompt, { prompt = it }, Modifier.weight(1f), placeholder = { Text("例如：打开时钟应用") }, maxLines = 4, enabled = !state.running)
                IconButton(onClick = { val value = prompt.trim(); if (value.isNotEmpty()) { prompt = ""; onRun(value) } }, enabled = prompt.isNotBlank() && !state.running && configured && accessibilityConnected) {
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
