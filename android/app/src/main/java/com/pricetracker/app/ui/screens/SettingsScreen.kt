package com.pricetracker.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pricetracker.app.notifications.CHECK_INTERVAL_OPTIONS
import com.pricetracker.app.notifications.applyCheckIntervalMinutes
import com.pricetracker.app.notifications.enqueueImmediateCheck
import com.pricetracker.app.notifications.getCheckIntervalMinutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var checkStarted by remember { mutableStateOf(false) }
    var intervalMinutes by remember { mutableIntStateOf(getCheckIntervalMinutes(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & about") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("How it works", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Everything runs on this phone. Prices are fetched directly from " +
                            "amazon.ie, paradigit.ie and currys.ie and stored locally — no " +
                            "account, no server, nothing to configure.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "When a price changes or drops below a target you set, you'll get a " +
                            "notification.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Check frequency", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "How often prices are re-checked in the background. Shorter intervals " +
                            "update sooner but use more battery and data.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                    CHECK_INTERVAL_OPTIONS.forEach { option ->
                        val selected = option.minutes == intervalMinutes
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    intervalMinutes = option.minutes
                                    applyCheckIntervalMinutes(context, option.minutes)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    intervalMinutes = option.minutes
                                    applyCheckIntervalMinutes(context, option.minutes)
                                },
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(
                        "Android may batch background work, so checks can run a little later " +
                            "than scheduled.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Button(
                onClick = {
                    enqueueImmediateCheck(context)
                    checkStarted = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check all prices now") }

            if (checkStarted) {
                Text(
                    "Started a check in the background. Prices and any alerts will update " +
                        "shortly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                "Tip: allow notifications so price alerts can reach you when the app is " +
                    "closed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
