package com.pricetracker.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pricetracker.app.data.Repository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { Repository(context) }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    val urls = remember { mutableStateListOf("", "", "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track a product") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Product name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Paste the product page URL from amazon.ie, paradigit.ie and/or " +
                    "currys.ie. At least one is required.",
                style = MaterialTheme.typography.bodySmall,
            )
            urls.forEachIndexed { index, value ->
                OutlinedTextField(
                    value = value,
                    onValueChange = { urls[index] = it },
                    label = { Text("Product URL ${index + 1}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = { urls.add("") }) { Text("Add another URL") }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (saving) {
                CircularProgressIndicator()
                Text(
                    "Fetching current prices, this can take a moment…",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Button(
                    onClick = {
                        val cleaned = urls.map { it.trim() }.filter { it.isNotEmpty() }
                        when {
                            name.isBlank() -> error = "Give the product a name."
                            cleaned.isEmpty() -> error = "Add at least one product URL."
                            cleaned.any { !it.startsWith("http") } ->
                                error = "URLs must start with http(s)://"
                            else -> {
                                saving = true
                                error = null
                                scope.launch {
                                    try {
                                        repository.createProduct(name.trim(), cleaned)
                                        onDone()
                                    } catch (e: Exception) {
                                        error = "Failed to save: ${e.message}"
                                        saving = false
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start tracking") }
            }
        }
    }
}
