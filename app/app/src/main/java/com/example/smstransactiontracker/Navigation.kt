package com.example.smstransactiontracker

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.smstransactiontracker.ui.main.MainScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack     = backStack,
        onBack        = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            // Main is the single root — the bottom nav is built into MainScreen
            entry<Main> {
                MainScreen(onItemClick = { navKey -> backStack.add(navKey) })
            }
        }
    )
}
