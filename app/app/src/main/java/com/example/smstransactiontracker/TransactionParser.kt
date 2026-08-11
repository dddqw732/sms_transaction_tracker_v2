package com.example.smstransactiontracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

data class Transaction(
    val amount: Double,
    val currency: String,
    val sender: String,
    val receiver: String,
    val provider: String,
    val transactionId: String?,
    val timestamp: String, // ISO 8601 format
    val type: String,      // "Sent" or "Received"
    val rawSms: String
)

object TransactionParser {
    fun parse(smsBody: String, senderNumber: String): Transaction? {
        val normalizedBody = smsBody.replace("\n", " ").trim()
        
        // Identify the payment provider (e.g. EVC Plus, eDahab, ZAAD, Sahal)
        var provider = senderNumber
        if (normalizedBody.contains("EVC Plus", ignoreCase = true) || senderNumber.contains("EVCPlus", ignoreCase = true)) {
            provider = "EVC Plus"
        } else if (normalizedBody.contains("eDahab", ignoreCase = true) || senderNumber.contains("eDahab", ignoreCase = true)) {
            provider = "eDahab"
        } else if (normalizedBody.contains("ZAAD", ignoreCase = true) || senderNumber.contains("ZAAD", ignoreCase = true)) {
            provider = "ZAAD"
        } else if (normalizedBody.contains("Sahal", ignoreCase = true) || senderNumber.contains("Sahal", ignoreCase = true)) {
            provider = "Sahal"
        } else if (normalizedBody.contains("MPESA", ignoreCase = true) || senderNumber.contains("MPESA", ignoreCase = true)) {
            provider = "M-Pesa"
        } else if (normalizedBody.contains("PayPal", ignoreCase = true) || senderNumber.contains("PayPal", ignoreCase = true)) {
            provider = "PayPal"
        } else if (normalizedBody.contains("Stripe", ignoreCase = true)) {
            provider = "Stripe"
        } else if (normalizedBody.contains("Venmo", ignoreCase = true)) {
            provider = "Venmo"
        } else if (normalizedBody.contains("CashApp", ignoreCase = true) || normalizedBody.contains("Cash App", ignoreCase = true)) {
            provider = "CashApp"
        } else if (normalizedBody.contains("Zelle", ignoreCase = true)) {
            provider = "Zelle"
        } else if (provider.all { it.isDigit() || it == '+' }) {
            provider = "SMS Notification ($senderNumber)"
        }

        // 1. Extract Transaction ID
        var txnId: String? = null
        val txnPatterns = listOf(
            Pattern.compile("Ref(?:erence)?:?\\s*([A-Z0-9]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Transaction\\s*ID:?\\s*([A-Z0-9]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Txn\\s*ID:?\\s*([A-Z0-9]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Ref\\s*ID:?\\s*([A-Z0-9]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Code\\s*([A-Z0-9]+)", Pattern.CASE_INSENSITIVE)
        )
        for (pattern in txnPatterns) {
            val matcher = pattern.matcher(normalizedBody)
            if (matcher.find()) {
                txnId = matcher.group(1)
                break
            }
        }
        
        // 2. Extract Type (Sent vs Received) — check received indicators first
        var type = when {
            normalizedBody.contains("received", ignoreCase = true) ||
            normalizedBody.contains("credited", ignoreCase = true) ||
            normalizedBody.contains("deposited", ignoreCase = true) -> "Received"
            normalizedBody.contains("sent to", ignoreCase = true) ||
            normalizedBody.contains("paid to", ignoreCase = true) ||
            normalizedBody.contains("transferred to", ignoreCase = true) ||
            normalizedBody.contains("withdrew", ignoreCase = true) ||
            normalizedBody.contains("you sent", ignoreCase = true) ||
            (normalizedBody.contains(" sent ", ignoreCase = true) &&
                !normalizedBody.contains("received", ignoreCase = true)) -> "Sent"
            else -> "Received"
        }

        // 3. Extract Amount & Currency
        var amount = 0.0
        var currency = "USD"
        
        val amountPatterns = listOf(
            // Currencies represented by code or symbol followed by value, e.g., USD 100.00 or $100.00
            Pattern.compile("([A-Z\\$€£¥]{1,3})\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)"),
            // Value followed by code or symbol, e.g., 100.00 USD
            Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)\\s*([A-Z\\$€£¥]{1,3})")
        )
        
        var foundAmount = false
        for (pattern in amountPatterns) {
            val matcher = pattern.matcher(normalizedBody)
            if (matcher.find()) {
                val group1 = matcher.group(1)!!
                val group2 = matcher.group(2)!!
                
                val doubleVal1 = group1.replace(",", "").toDoubleOrNull()
                val doubleVal2 = group2.replace(",", "").toDoubleOrNull()
                
                if (doubleVal1 != null) {
                    amount = doubleVal1
                    currency = group2.trim()
                    foundAmount = true
                } else if (doubleVal2 != null) {
                    amount = doubleVal2
                    currency = group1.trim()
                    foundAmount = true
                }
                if (foundAmount) break
            }
        }
        
        if (!foundAmount) {
            // General fallback search for numbers
            val numPattern = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]+)?)")
            val numMatcher = numPattern.matcher(normalizedBody)
            if (numMatcher.find()) {
                amount = numMatcher.group(1)!!.replace(",", "").toDoubleOrNull() ?: 0.0
            }
        }

        // 4. Extract Sender / Receiver names
        var senderName = "Unknown"
        var receiverName = "Unknown"
        
        if (type == "Sent") {
            senderName = "You"
            val toIndex = normalizedBody.indexOf("to ", ignoreCase = true)
            val paidIndex = normalizedBody.indexOf("paid ", ignoreCase = true)
            val startIndex = when {
                toIndex != -1 -> toIndex + 3
                paidIndex != -1 -> paidIndex + 5
                else -> -1
            }
            if (startIndex != -1) {
                val remaining = normalizedBody.substring(startIndex)
                receiverName = extractNameFromRemaining(remaining)
            }
        } else {
            receiverName = "You"
            val fromIndex = normalizedBody.indexOf("from ", ignoreCase = true)
            if (fromIndex != -1) {
                val remaining = normalizedBody.substring(fromIndex + 5)
                senderName = extractNameFromRemaining(remaining)
            }
        }

        if (!foundAmount || amount <= 0.0) {
            return null
        }

        senderName = cleanName(senderName)
        receiverName = cleanName(receiverName)

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())

        return Transaction(
            amount = amount,
            currency = currency,
            sender = senderName,
            receiver = receiverName,
            provider = provider,
            transactionId = txnId,
            timestamp = timestamp,
            type = type,
            rawSms = smsBody
        )
    }

    private fun extractNameFromRemaining(remaining: String): String {
        var name = remaining.trim()
        val stopKeywords = listOf("Ref", "Transaction", "Txn", "ID", "Code", "Fee", "via", "on", "at", "date")
        
        var earliestStop = name.length
        for (kw in stopKeywords) {
            val pattern = Pattern.compile("\\b$kw\\b", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(name)
            if (matcher.find()) {
                if (matcher.start() < earliestStop) {
                    earliestStop = matcher.start()
                }
            }
        }
        
        val punctPattern = Pattern.compile("[\\.,](?:\\s|$)")
        val punctMatcher = punctPattern.matcher(name)
        if (punctMatcher.find()) {
            if (punctMatcher.start() < earliestStop) {
                earliestStop = punctMatcher.start()
            }
        }
        
        if (earliestStop < name.length) {
            name = name.substring(0, earliestStop)
        }
        
        return cleanName(name)
    }

    private fun cleanName(name: String): String {
        var n = name.trim()
        val removeKeywords = listOf("Sent", "Received", "Ref", "Transaction", "Txn", "ID", "Code", "Fee", "Balance", "date", "on", "at")
        for (kw in removeKeywords) {
            if (n.endsWith(" $kw", ignoreCase = true)) {
                n = n.substring(0, n.length - kw.length).trim()
            }
        }
        n = n.removeSuffix(".")
        n = n.removeSuffix(",")
        return n.trim()
    }
}
