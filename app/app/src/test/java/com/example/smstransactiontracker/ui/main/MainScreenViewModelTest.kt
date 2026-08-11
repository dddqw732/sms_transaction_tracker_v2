package com.example.smstransactiontracker.ui.main

import com.example.smstransactiontracker.Transaction
import com.example.smstransactiontracker.TransactionHistory
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class MainScreenViewModelTest {
  @After
  fun tearDown() {
    TransactionHistory.clear()
  }

  @Test
  fun transactions_initiallyEmpty() = runTest {
    TransactionHistory.clear()
    val viewModel = MainScreenViewModel()
    assertEquals(emptyList<Transaction>(), viewModel.transactions.first())
  }

  @Test
  fun transactions_reflectHistoryUpdates() = runTest {
    TransactionHistory.clear()
    val viewModel = MainScreenViewModel()
    val txn = Transaction(
      amount = 10.0,
      currency = "USD",
      sender = "Jane",
      receiver = "You",
      provider = "PayPal",
      transactionId = "TXN1",
      timestamp = "2026-06-30T00:00:00Z",
      type = "Received",
      rawSms = "test"
    )
    TransactionHistory.addTransaction(txn)
    assertEquals(1, viewModel.transactions.first().size)
    assertEquals("Jane", viewModel.transactions.first().first().sender)
  }
}
