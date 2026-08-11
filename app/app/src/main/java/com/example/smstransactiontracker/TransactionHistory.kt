package com.example.smstransactiontracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PeriodSummary(
    val label: String,         // e.g. "Week 26, 2026" or "June 2026"
    val received: Double,
    val sent: Double,
    val net: Double,
    val count: Int,
    val currency: String
)

object TransactionHistory {
    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    fun addTransaction(t: Transaction) {
        _transactions.value = listOf(t) + _transactions.value
    }

    fun clear() {
        _transactions.value = emptyList()
    }

    // ── Weekly Summary ────────────────────────────────────────────────────────
    fun getWeeklySummary(): List<PeriodSummary> {
        val txns = _transactions.value
        if (txns.isEmpty()) return emptyList()

        val weekFmt  = SimpleDateFormat("yyyy-'W'ww", Locale.US)
        val labelFmt = SimpleDateFormat("'Week 'w, yyyy", Locale.US)
        val parseFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

        return txns
            .groupBy { txn ->
                runCatching { weekFmt.format(parseFmt.parse(txn.timestamp) ?: Date()) }
                    .getOrDefault(weekFmt.format(Date()))
            }
            .map { (key, group) ->
                val date = runCatching { weekFmt.parse(key) ?: Date() }.getOrDefault(Date())
                val received = group.filter { it.type.equals("Received", ignoreCase = true) }.sumOf { it.amount }
                val sent     = group.filter { it.type.equals("Sent",     ignoreCase = true) }.sumOf { it.amount }
                val currency = group.firstOrNull()?.currency ?: "USD"
                PeriodSummary(
                    label    = labelFmt.format(date),
                    received = received,
                    sent     = sent,
                    net      = received - sent,
                    count    = group.size,
                    currency = currency
                )
            }
            .sortedByDescending { it.label }
    }

    // ── Monthly Summary ───────────────────────────────────────────────────────
    fun getMonthlySummary(): List<PeriodSummary> {
        val txns = _transactions.value
        if (txns.isEmpty()) return emptyList()

        val monthKey  = SimpleDateFormat("yyyy-MM", Locale.US)
        val labelFmt  = SimpleDateFormat("MMMM yyyy", Locale.US)
        val parseFmt  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

        return txns
            .groupBy { txn ->
                runCatching { monthKey.format(parseFmt.parse(txn.timestamp) ?: Date()) }
                    .getOrDefault(monthKey.format(Date()))
            }
            .map { (key, group) ->
                val date = runCatching { monthKey.parse(key) ?: Date() }.getOrDefault(Date())
                val received = group.filter { it.type.equals("Received", ignoreCase = true) }.sumOf { it.amount }
                val sent     = group.filter { it.type.equals("Sent",     ignoreCase = true) }.sumOf { it.amount }
                val currency = group.firstOrNull()?.currency ?: "USD"
                PeriodSummary(
                    label    = labelFmt.format(date),
                    received = received,
                    sent     = sent,
                    net      = received - sent,
                    count    = group.size,
                    currency = currency
                )
            }
            .sortedByDescending { it.label }
    }
}
