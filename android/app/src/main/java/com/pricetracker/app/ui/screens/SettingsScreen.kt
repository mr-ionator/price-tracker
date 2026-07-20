package com.pricetracker.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pricetracker.app.data.Repository
import com.pricetracker.app.data.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val repository = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { url = settings.currentBackendUrl() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Backend server", style = MaterialTheme.typography.titleMedium)
            Text(
                "URL of the price-tracker backend. Use http://10.0.2.2:8000 on the " +
                    "Android emulator, or http://<your-PC's-LAN-IP>:8000 from a real " +
                    "phone on the same Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Backend URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        settings.setBackendUrl(url)
                        status = try {
                            val health = repository.health()
                            statusIsError = false
                            "Connected ✓  (checks every ${health.checkIntervalMinutes} min, " +
                                "email ${if (health.emailConfigured) "on" else "off"}, " +
                                "ntfy ${if (health.ntfyConfigured) "on" else "off"})"
                        } catch (e: Exception) {
                            statusIsError = true
                            "Connection failed: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save & test connection") }

            status?.let {
                Text(
                    it,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                "Notifications are checked in the background roughly every 15 minutes. " +
                    "For instant pushes and email alerts, configure ntfy or SMTP in the " +
                    "backend's .env file.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
