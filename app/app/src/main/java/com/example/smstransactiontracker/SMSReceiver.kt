package com.example.smstransactiontracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SMSReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val sender = messages.firstOrNull()?.originatingAddress ?: "Unknown"
            val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
            
            Log.d("SMSReceiver", "Received SMS from $sender: $body")
            
            val transaction = TransactionParser.parse(body, sender)
            if (transaction != null) {
                Log.d("SMSReceiver", "Parsed transaction: $transaction")
                
                // Save to local list for UI updates
                TransactionHistory.addTransaction(transaction)
                
                // Dispatch background thread network request
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sendToServer(context, transaction)
                    } catch (e: Exception) {
                        Log.e("SMSReceiver", "Error sending transaction to server", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        fun sendToServer(context: Context, t: Transaction) {
            val sharedPrefs = context.getSharedPreferences("sms_tracker_prefs", Context.MODE_PRIVATE)
            var backendUrl = sharedPrefs.getString("backend_url", "http://192.168.123.33:8000") ?: "http://192.168.123.33:8000"
            
            backendUrl = backendUrl.trim().removeSuffix("/")
            val urlString = "$backendUrl/api/transactions"
            
            Log.d("SMSReceiver", "Connecting to backend URL: $urlString")
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val cleanRaw = t.rawSms.replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
            val jsonPayload = """
            {
              "amount": ${t.amount},
              "currency": "${t.currency}",
              "sender": "${t.sender.replace("\"", "\\\"")}",
              "receiver": "${t.receiver.replace("\"", "\\\"")}",
              "provider": "${t.provider.replace("\"", "\\\"")}",
              "transaction_id": ${if (t.transactionId == null) "null" else "\"${t.transactionId.replace("\"", "\\\"")}\""},
              "timestamp": "${t.timestamp}",
              "type": "${t.type}",
              "raw_sms": "$cleanRaw"
            }
            """.trimIndent()

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val responseCode = conn.responseCode
            Log.d("SMSReceiver", "HTTP Response Code: $responseCode")
            if (responseCode !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("SMSReceiver", "HTTP Error body: $errorMsg")
            }
            conn.disconnect()
        }
    }
}
