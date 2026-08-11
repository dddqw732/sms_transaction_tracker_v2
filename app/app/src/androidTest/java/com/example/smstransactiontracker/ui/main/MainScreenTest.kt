package com.example.smstransactiontracker.ui.main

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/** UI tests for [MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun syncSettings_visible() {
    composeTestRule.setContent { MainScreen(onItemClick = {}) }
    composeTestRule.onNodeWithText("Sync Settings").assertExists()
    composeTestRule.onNodeWithText("SMS Simulator Sandbox").assertExists()
  }
}
