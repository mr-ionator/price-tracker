package com.pricetracker.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pricetracker.app.data.PricePoint
import com.pricetracker.app.data.ProductDetail
import com.pricetracker.app.data.Repository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(productId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<ProductDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var newTarget by remember { mutableStateOf("") }

    LaunchedEffect(reloadKey) {
        try {
            detail = repository.getProduct(productId)
            error = null
        } catch (e: Exception) {
            error = "Failed to load: ${e.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.name ?: "Product") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    repository.refreshProduct(productId)
                                    reloadKey++
                                } catch (e: Exception) {
                                    error = "Refresh failed: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Icon(Icons.Default.Refresh, contentDescription = "Check prices now") }
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    repository.deleteProduct(productId)
                                    onBack()
                                } catch (e: Exception) {
                                    error = "Delete failed: ${e.message}"
                                }
                            }
                        },
                    ) { Icon(Icons.Default.Delete, contentDescription = "Stop tracking") }
                },
            )
        },
    ) { padding ->
        val current = detail
        if (current == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) {
                Text("Checking prices…", style = MaterialTheme.typography.bodySmall)
            }

            current.urls.forEach { tracked ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                tracked.site,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                tracked.latestPrice?.let { "€%.2f".format(it.price) } ?: "—",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        tracked.lastCheckedAt?.let {
                            Text(
                                "Checked ${prettyTimestamp(it)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        tracked.lastError?.let {
                            Text(
                                "Last check failed: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        val points = current.history[tracked.id.toString()].orEmpty()
                        if (points.size >= 2) {
                            Spacer(modifier = Modifier.height(8.dp))
                            PriceChart(points = points)
                        }
                    }
                }
            }

            Text("Alerts", style = MaterialTheme.typography.titleMedium)
            if (current.alerts.isEmpty()) {
                Text(
                    "No alerts. Set a target price below and you'll be notified " +
                        "when any shop reaches it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            current.alerts.forEach { alert ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Below €%.2f".format(alert.targetPrice) +
                            if (alert.triggered) "  ✓ reached" else "",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = alert.active,
                            onCheckedChange = {
                                scope.launch {
                                    try {
                                        repository.toggleAlert(alert.id)
                                        reloadKey++
                                    } catch (e: Exception) {
                                        error = "Failed: ${e.message}"
                                    }
                                }
                            },
                        )
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    repository.deleteAlert(alert.id)
                                    reloadKey++
                                } catch (e: Exception) {
                                    error = "Failed: ${e.message}"
                                }
                            }
                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete alert") }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newTarget,
                    onValueChange = { newTarget = it },
                    label = { Text("Target price €") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val target = newTarget.replace(",", ".").toDoubleOrNull()
                        if (target == null || target <= 0) {
                            error = "Enter a valid target price."
                        } else {
                            scope.launch {
                                try {
                                    repository.createAlert(productId, target)
                                    newTarget = ""
                                    error = null
                                    reloadKey++
                                } catch (e: Exception) {
                                    error = "Failed: ${e.message}"
                                }
                            }
                        }
                    },
                ) { Text("Set alert") }
            }
        }
    }
}

private fun prettyTimestamp(iso: String): String =
    iso.take(16).replace('T', ' ')

@Composable
private fun PriceChart(points: List<PricePoint>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val minPrice = points.minOf { it.price }
    val maxPrice = points.maxOf { it.price }
    val range = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0

    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            val chartHeight = size.height
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = index * stepX
                val normalized = ((point.price - minPrice) / range).toFloat()
                val y = chartHeight * (1f - normalized) * 0.85f + chartHeight * 0.075f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(color = lineColor, radius = 6f, center = Offset(x, y))
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 4f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "min €%.2f".format(minPrice),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Text(
                "max €%.2f".format(maxPrice),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}
