package com.pricetracker.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pricetracker.app.ui.screens.AddProductScreen
import com.pricetracker.app.ui.screens.NotificationsScreen
import com.pricetracker.app.ui.screens.ProductDetailScreen
import com.pricetracker.app.ui.screens.ProductListScreen
import com.pricetracker.app.ui.screens.SettingsScreen
import com.pricetracker.app.ui.theme.PriceTrackerTheme

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PriceTrackerTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "products") {
        composable("products") {
            ProductListScreen(
                onAddProduct = { navController.navigate("add") },
                onOpenProduct = { id -> navController.navigate("product/$id") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenNotifications = { navController.navigate("notifications") },
            )
        }
        composable("add") {
            AddProductScreen(onDone = { navController.popBackStack() })
        }
        composable(
            "product/{id}",
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            ProductDetailScreen(
                productId = entry.arguments?.getInt("id") ?: 0,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("notifications") {
            NotificationsScreen(onBack = { navController.popBackStack() })
        }
    }
}
