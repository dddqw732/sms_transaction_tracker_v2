package com.example.smstransactiontracker.ui.main

import androidx.lifecycle.ViewModel
import com.example.smstransactiontracker.Transaction
import com.example.smstransactiontracker.TransactionHistory
import kotlinx.coroutines.flow.StateFlow

class MainScreenViewModel : ViewModel() {
    val transactions: StateFlow<List<Transaction>> = TransactionHistory.transactions
}
