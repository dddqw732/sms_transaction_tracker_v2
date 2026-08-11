package com.example.smstransactiontracker.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.NavKey
import com.example.smstransactiontracker.PeriodSummary
import com.example.smstransactiontracker.SMSReceiver
import com.example.smstransactiontracker.Transaction
import com.example.smstransactiontracker.TransactionHistory
import com.example.smstransactiontracker.TransactionParser
import com.example.smstransactiontracker.theme.AmberAccent
import com.example.smstransactiontracker.theme.GreenPositive
import com.example.smstransactiontracker.theme.RedNegative
import com.example.smstransactiontracker.theme.TealPrimary
import com.example.smstransactiontracker.theme.TextMuted
import com.example.smstransactiontracker.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Somali provider brand colours ───────────────────────────────────────────
private val SomaliProviders = listOf("EVC Plus", "eDahab", "ZAAD", "Sahal")

private fun providerColor(provider: String): Color = when {
    provider.contains("EVC",   ignoreCase = true) -> Color(0xFF00897B) // teal
    provider.contains("eDahab", ignoreCase = true) -> Color(0xFFF59E0B) // amber
    provider.contains("ZAAD",  ignoreCase = true) -> Color(0xFF3B82F6) // blue
    provider.contains("Sahal", ignoreCase = true) -> Color(0xFFEC4899) // pink
    else                                           -> Color(0xFF64748B)
}

private fun providerInitials(provider: String): String = when {
    provider.contains("EVC",   ignoreCase = true) -> "EVC"
    provider.contains("eDahab", ignoreCase = true) -> "eD"
    provider.contains("ZAAD",  ignoreCase = true) -> "ZD"
    provider.contains("Sahal", ignoreCase = true) -> "SHL"
    else -> provider.take(3).uppercase()
}

private fun JSONObject.optNullableString(name: String): String? = if (isNull(name)) null else optString(name)

private fun parseBackendTransaction(json: JSONObject): Transaction? {
    val amount = json.optDouble("amount", Double.NaN)
    if (amount.isNaN()) return null

    val currency = json.optString("currency", "USD")
    val sender = json.optString("sender", "Unknown")
    val receiver = json.optString("receiver", "Unknown")
    val provider = json.optString("provider", "Unknown")
    val txnId = json.optNullableString("transaction_id")
    val timestamp = json.optString("timestamp", "")
    val type = json.optString("type", "Received")
    val rawSms = json.optString("raw_sms", "")

    return Transaction(
        amount = amount,
        currency = currency,
        sender = sender,
        receiver = receiver,
        provider = provider,
        transactionId = txnId,
        timestamp = timestamp.ifBlank { java.time.Instant.now().toString() },
        type = type,
        rawSms = rawSms
    )
}

@Throws(Exception::class)
private fun loadWebsiteTransactions(backendUrl: String): List<Transaction> {
    val baseUrl = backendUrl.trim().removeSuffix("/")
    val url = URL("$baseUrl/api/transactions")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
        connectTimeout = 5000
        readTimeout = 5000
    }

    connection.inputStream.bufferedReader().use { reader ->
        val body = reader.readText()
        val jsonArray = JSONArray(body)
        return (0 until jsonArray.length())
            .mapNotNull { idx -> parseBackendTransaction(jsonArray.getJSONObject(idx)) }
            .sortedWith(compareByDescending<Transaction> { it.timestamp })
    }
}

// ─── Tab definition ───────────────────────────────────────────────────────────
private enum class AppTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Home),
    LEDGER   ("Ledger",    Icons.Default.FormatListBulleted),
    SUMMARY  ("Summary",   Icons.Default.Assessment),
    SETTINGS ("Settings",  Icons.Default.Settings)
}

// ─── Root composable ─────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context     = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("sms_tracker_prefs", Context.MODE_PRIVATE) }

    // ── Shared state ──────────────────────────────────────────────────────────
    var backendUrl by remember {
        mutableStateOf(sharedPrefs.getString("backend_url", "http://192.168.1.1:8000") ?: "http://192.168.1.1:8000")
    }
    var hasSmsPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)    == PackageManager.PERMISSION_GRANTED
        )
    }
    var connectionStatus by remember { mutableStateOf<Boolean?>(null) }
    var syncMode by rememberSaveable { mutableStateOf("website") }
    var backendTransactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var websiteFetchError by remember { mutableStateOf<String?>(null) }
    var selectedTab      by remember { mutableStateOf(AppTab.DASHBOARD) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasSmsPermissions = permissions[Manifest.permission.RECEIVE_SMS] == true &&
                            permissions[Manifest.permission.READ_SMS]    == true
        if (hasSmsPermissions) {
            Toast.makeText(context, "SMS permission granted ✓", Toast.LENGTH_SHORT).show()
        }
    }

    val transactions by TransactionHistory.transactions.collectAsState()
    val displayTransactions = if (syncMode == "website") backendTransactions else transactions

    LaunchedEffect(syncMode, backendUrl) {
        if (syncMode == "website") {
            websiteFetchError = null
            connectionStatus = null
            try {
                val loaded = withContext(Dispatchers.IO) { loadWebsiteTransactions(backendUrl) }
                backendTransactions = loaded
                connectionStatus = true
            } catch (error: Exception) {
                websiteFetchError = error.message ?: "Unable to load website data"
                connectionStatus = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(TealPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("FS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        }
                        Column {
                            Text("FinSMS", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color.White)
                            Text("Smart SMS finance dashboard", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                },
                actions = {
                    ConnectionPulseDot(status = connectionStatus)
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF071B2E)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1E293B),
                tonalElevation = 0.dp
            ) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected    = selectedTab == tab,
                        onClick     = { selectedTab = tab },
                        icon        = { Icon(tab.icon, contentDescription = tab.label) },
                        label       = { Text(tab.label, fontSize = 11.sp) },
                        colors      = NavigationBarItemDefaults.colors(
                            selectedIconColor   = TealPrimary,
                            selectedTextColor   = TealPrimary,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor      = Color(0xFF0F3330)
                        )
                    )
                }
            }
        },
        containerColor = Color(0xFF0F172A),
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTab) {
                AppTab.DASHBOARD -> DashboardTab(
                    transactions       = displayTransactions,
                    hasSmsPermissions  = hasSmsPermissions,
                    connectionStatus  = connectionStatus,
                    backendUrl        = backendUrl,
                    syncMode          = syncMode,
                    websiteFetchError = websiteFetchError,
                    onSyncModeChange   = { syncMode = it },
                    onConnectionResult = { connectionStatus = it },
                    onGrantPermission = {
                        val activity = context as? Activity
                        if (activity != null &&
                            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECEIVE_SMS)
                        ) {
                            Toast.makeText(context, "SMS permission is needed only for SMS capture mode.", Toast.LENGTH_LONG).show()
                        }
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                    }
                )
                AppTab.LEDGER    -> LedgerTab(transactions = displayTransactions)
                AppTab.SUMMARY   -> SummaryTab(transactions = displayTransactions)
                AppTab.SETTINGS  -> SettingsTab(
                    backendUrl        = backendUrl,
                    hasSmsPermissions = hasSmsPermissions,
                    connectionStatus  = connectionStatus,
                    syncMode          = syncMode,
                    onSyncModeChange  = { syncMode = it },
                    onUrlChange       = { url ->
                        backendUrl = url
                        sharedPrefs.edit().putString("backend_url", url).apply()
                    },
                    onConnectionResult = { connectionStatus = it },
                    onGrantPermission  = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                    }
                )
            }
        }
    }
}

// ─── Animated Connection Dot ──────────────────────────────────────────────────
@Composable
fun ConnectionPulseDot(status: Boolean?) {
    val color = when (status) {
        true  -> GreenPositive
        false -> RedNegative
        null  -> Color(0xFF64748B)
    }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse_scale"
    )
    Box(
        modifier = Modifier
            .size(if (status == true) (10 * scale).dp else 10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// TAB 1 – DASHBOARD
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun DashboardTab(
    transactions: List<Transaction>,
    hasSmsPermissions: Boolean,
    connectionStatus: Boolean?,
    backendUrl: String,
    syncMode: String,
    websiteFetchError: String?,
    onSyncModeChange: (String) -> Unit,
    onConnectionResult: (Boolean) -> Unit,
    onGrantPermission: () -> Unit
) {
    val context = LocalContext.current
    val totalCount    = transactions.size
    val totalReceived = transactions.filter { it.type.equals("Received", ignoreCase = true) }.sumOf { it.amount }
    val totalSent     = transactions.filter { it.type.equals("Sent",     ignoreCase = true) }.sumOf { it.amount }
    val netBalance    = totalReceived - totalSent
    val currency      = transactions.firstOrNull()?.currency ?: "USD"
    val balanceColor  = if (netBalance >= 0) GreenPositive else RedNegative

    val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US)
    val liveDate = remember { dateFmt.format(Date()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Dashboard Overview", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                DashboardHeroCard(
                    totalTransactions = totalCount,
                    totalReceived = totalReceived,
                    totalSent = totalSent,
                    netBalance = netBalance,
                    currency = currency,
                    balanceColor = balanceColor,
                    dateLabel = liveDate,
                    connectionStatus = connectionStatus
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Data source", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("website" to "Website", "sms" to "SMS").forEach { (value, label) ->
                            Button(
                                onClick = { onSyncModeChange(value) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (syncMode == value) TealPrimary else Color(0xFF1E293B),
                                    contentColor = if (syncMode == value) Color.White else TextSecondary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(label)
                            }
                        }
                    }
                    if (syncMode == "website" && websiteFetchError != null) {
                        Text(
                            "Website sync error: $websiteFetchError",
                            color = RedNegative,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (!hasSmsPermissions && syncMode == "sms") {
            item { PermissionBanner(onGrantPermission) }
        }

        item { SomaliProviderRow() }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        label = "Total Transactions",
                        value = totalCount.toString(),
                        iconBg = Color(0xFF1E3A5F),
                        iconTint = Color(0xFF60A5FA),
                        icon = Icons.Default.Receipt,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "Total Received",
                        value = "$currency ${String.format("%.2f", totalReceived)}",
                        iconBg = Color(0xFF14532D),
                        iconTint = GreenPositive,
                        icon = Icons.Default.TrendingDown,
                        valueColor = GreenPositive,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        label = "Total Sent",
                        value = "$currency ${String.format("%.2f", totalSent)}",
                        iconBg = Color(0xFF7F1D1D),
                        iconTint = RedNegative,
                        icon = Icons.Default.TrendingUp,
                        valueColor = RedNegative,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "Net Balance",
                        value = "${if (netBalance >= 0) "+" else ""}$currency ${String.format("%.2f", netBalance)}",
                        iconBg = Color(0xFF0F3330),
                        iconTint = TealPrimary,
                        icon = Icons.Default.AccountBalance,
                        valueColor = balanceColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item { ConnectButton(backendUrl = backendUrl, connectionStatus = connectionStatus, onConnectionResult = onConnectionResult) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recent Transactions", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                if (transactions.isNotEmpty()) {
                    TextButton(onClick = { TransactionHistory.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = RedNegative, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", color = RedNegative, fontSize = 12.sp)
                    }
                }
            }
        }

        if (transactions.isEmpty()) {
            item { EmptyState() }
        } else {
            items(transactions.take(5)) { txn -> TransactionCard(txn) }
            if (transactions.size > 5) {
                item {
                    Text(
                        "View all ${transactions.size} in Ledger →",
                        color = TealPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardHeroCard(
    totalTransactions: Int,
    totalReceived: Double,
    totalSent: Double,
    netBalance: Double,
    currency: String,
    balanceColor: Color,
    dateLabel: String,
    connectionStatus: Boolean?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Live transaction pulse", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("$currency ${String.format("%.2f", netBalance)}", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (netBalance >= 0) "Positive net flow" else "Negative net flow",
                        color = balanceColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background((connectionStatus?.let { if (it) GreenPositive else RedNegative } ?: TextMuted).copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            when (connectionStatus) {
                                true -> "Connected"
                                false -> "Offline"
                                null -> "Waiting"
                            },
                            color = when (connectionStatus) {
                                true -> GreenPositive
                                false -> RedNegative
                                null -> TextMuted
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(dateLabel, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStatCard("Transactions", totalTransactions.toString(), Icons.Default.Receipt, Color(0xFF2563EB))
                MiniStatCard("Received", "$currency ${String.format("%.2f", totalReceived)}", Icons.Default.TrendingDown, GreenPositive)
                MiniStatCard("Sent", "$currency ${String.format("%.2f", totalSent)}", Icons.Default.TrendingUp, RedNegative)
            }
        }
    }
}

@Composable
fun MiniStatCard(label: String, value: String, icon: ImageVector, iconColor: Color) {
    Card(
        modifier = Modifier.weight(1f),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── Somali provider logo row ─────────────────────────────────────────────────
@Composable
fun SomaliProviderRow() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Somali Payment Networks", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SomaliProviders.forEach { name ->
                ProviderBadge(name = name, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ProviderBadge(name: String, modifier: Modifier = Modifier) {
    val color = providerColor(name)
    val initials = providerInitials(name)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(initials, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(name.replace(" ", "\n"), fontSize = 9.sp, color = color, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ─── Connect button ───────────────────────────────────────────────────────────
@Composable
fun ConnectButton(backendUrl: String, connectionStatus: Boolean?, onConnectionResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    var isTesting by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isTesting) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "btn_scale"
    )

    val statusLabel = when (connectionStatus) {
        true  -> "Connected"
        false -> "Offline / Error"
        null  -> "Tap to Connect"
    }
    val btnColor = when (connectionStatus) {
        true  -> GreenPositive
        false -> RedNegative
        null  -> TealPrimary
    }

    Button(
        onClick = {
            if (!isTesting) {
                isTesting = true
                CoroutineScope(Dispatchers.IO).launch {
                    val baseUrl = backendUrl.trim().removeSuffix("/")
                    val success = try {
                        val url  = java.net.URL("$baseUrl/api/health")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 4000
                        conn.readTimeout    = 4000
                        val code = conn.responseCode
                        conn.disconnect()
                        code in 200..299
                    } catch (e: Exception) { false }
                    launch(Dispatchers.Main) {
                        isTesting = false
                        onConnectionResult(success)
                        val msg = if (success) "✓ Connected to server!" else "✗ Connection failed. Check IP & Wi-Fi."
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(52.dp).scale(scale),
        colors   = ButtonDefaults.buttonColors(containerColor = btnColor),
        shape    = RoundedCornerShape(12.dp),
        enabled  = !isTesting
    ) {
        if (isTesting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("Testing connection...", fontWeight = FontWeight.SemiBold)
        } else {
            Icon(
                imageVector = when (connectionStatus) {
                    true  -> Icons.Default.CheckCircle
                    false -> Icons.Default.Error
                    null  -> Icons.Default.Wifi
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(statusLabel, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

// ─── Metric card ─────────────────────────────────────────────────────────────
@Composable
fun MetricCard(
    label: String,
    value: String,
    iconBg: Color,
    iconTint: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White
) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ─── Permission banner ────────────────────────────────────────────────────────
@Composable
fun PermissionBanner(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(22.dp))
                Text("SMS Permission Required", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "FinSMS needs access to your SMS messages to automatically detect EVC Plus, eDahab, ZAAD, and Sahal transactions.",
                color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick  = onGrant,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = RedNegative),
                shape    = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Grant SMS Permission", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────
@Composable
fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Inbox, contentDescription = null, tint = TextMuted, modifier = Modifier.size(32.dp))
            Text("No transactions yet.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            Text("Send a payment to see it appear here.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TAB 2 – LEDGER (mirrors the website's main content area)
// ═══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerTab(transactions: List<Transaction>) {
    var search       by remember { mutableStateOf("") }
    var typeFilter   by remember { mutableStateOf("") }       // "", "Received", "Sent"
    var providerFilter by remember { mutableStateOf("") }
    var sortBy       by remember { mutableStateOf("timestamp") }
    var sortAsc      by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    val allProviders = remember(transactions) {
        listOf("") + transactions.map { it.provider }.distinct().sortedWith(
            Comparator { a, b ->
                val aIsSomali = SomaliProviders.any { a.contains(it, true) }
                val bIsSomali = SomaliProviders.any { b.contains(it, true) }
                when {
                    aIsSomali && !bIsSomali -> -1
                    !aIsSomali && bIsSomali ->  1
                    else -> a.compareTo(b)
                }
            }
        )
    }

    val filtered = transactions
        .filter { txn ->
            (search.isBlank() || txn.sender.contains(search, true) || txn.receiver.contains(search, true) || txn.transactionId?.contains(search, true) == true)
            && (typeFilter.isBlank()     || txn.type.equals(typeFilter, ignoreCase = true))
            && (providerFilter.isBlank() || txn.provider.equals(providerFilter, ignoreCase = true))
        }
        .let { list ->
            when (sortBy) {
                "amount"    -> if (sortAsc) list.sortedBy { it.amount }       else list.sortedByDescending { it.amount }
                "provider"  -> if (sortAsc) list.sortedBy { it.provider }     else list.sortedByDescending { it.provider }
                "sender"    -> if (sortAsc) list.sortedBy { it.sender }       else list.sortedByDescending { it.sender }
                else        -> if (sortAsc) list.sortedBy { it.timestamp }    else list.sortedByDescending { it.timestamp }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Transaction Ledger", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)

        // Search
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search sender, receiver, or ID…", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = null, tint = TextMuted)
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = TealPrimary,
                unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
                cursorColor          = TealPrimary
            ),
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        // Filter by type chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "All", "Received" to "Received", "Sent" to "Sent").forEach { (value, label) ->
                FilterChip(
                    selected = typeFilter == value,
                    onClick  = { typeFilter = value },
                    label    = { Text(label, fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealPrimary,
                        selectedLabelColor     = Color.White,
                        containerColor         = Color(0xFF1E293B),
                        labelColor             = TextSecondary
                    )
                )
            }
        }

        // Provider + Sort row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Provider dropdown
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { providerMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (providerFilter.isEmpty()) TextSecondary else TealPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
                ) {
                    Text(if (providerFilter.isEmpty()) "All Providers" else providerFilter, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                    modifier = Modifier.background(Color(0xFF1E293B))
                ) {
                    allProviders.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(if (p.isEmpty()) "All Providers" else p, color = if (providerFilter == p) TealPrimary else Color.White) },
                            onClick = { providerFilter = p; providerMenuExpanded = false },
                            leadingIcon = if (p.isNotEmpty()) ({
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(providerColor(p)))
                            }) else null
                        )
                    }
                }
            }

            // Sort field
            val sortOptions = listOf("timestamp" to "Date", "amount" to "Amount", "provider" to "Provider", "sender" to "Sender")
            var sortMenuExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { sortMenuExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                ) {
                    Text(sortOptions.find { it.first == sortBy }?.second ?: "Date", fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                    modifier = Modifier.background(Color(0xFF1E293B))
                ) {
                    sortOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = if (sortBy == value) TealPrimary else Color.White) },
                            onClick = { sortBy = value; sortMenuExpanded = false }
                        )
                    }
                }
            }

            // ASC/DESC toggle
            IconButton(onClick = { sortAsc = !sortAsc }) {
                Icon(
                    imageVector = if (sortAsc) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle sort order",
                    tint = TextSecondary
                )
            }
        }

        // Results count + reset
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} transactions", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            if (search.isNotEmpty() || typeFilter.isNotEmpty() || providerFilter.isNotEmpty()) {
                TextButton(onClick = { search = ""; typeFilter = ""; providerFilter = "" }) {
                    Text("Reset Filters", color = TealPrimary, fontSize = 12.sp)
                }
            }
        }

        // Transaction list
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) {
                item { EmptyState() }
            } else {
                items(filtered) { txn -> TransactionCard(txn) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TAB 3 – SUMMARY (Weekly / Monthly)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun SummaryTab(transactions: List<Transaction>) {
    var isWeekly by remember { mutableStateOf(true) }
    val summaries = remember(transactions, isWeekly) {
        if (isWeekly) summarizeTransactionsByWeek(transactions)
        else          summarizeTransactionsByMonth(transactions)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Transaction Summary", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)

        // Weekly / Monthly toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E293B))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(true to "Weekly", false to "Monthly").forEach { (weekly, label) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isWeekly == weekly) TealPrimary else Color.Transparent)
                        .clickable { isWeekly = weekly }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (isWeekly == weekly) Color.White else TextSecondary,
                        fontWeight = if (isWeekly == weekly) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (summaries.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(summaries) { period ->
                    PeriodCard(period)
                }
            }
        }
    }
}

@Composable
fun PeriodCard(period: PeriodSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(period.label, fontWeight = FontWeight.SemiBold, color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text("${period.count} txns", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryStatBox("Received", "+${period.currency} ${String.format("%.2f", period.received)}", GreenPositive, Modifier.weight(1f))
                SummaryStatBox("Sent",     "-${period.currency} ${String.format("%.2f", period.sent)}",     RedNegative,   Modifier.weight(1f))
                SummaryStatBox(
                    "Net",
                    "${if (period.net >= 0) "+" else ""}${period.currency} ${String.format("%.2f", period.net)}",
                    if (period.net >= 0) GreenPositive else RedNegative,
                    Modifier.weight(1f)
                )
            }

            // Simple bar representing received vs sent
            if (period.received + period.sent > 0) {
                val recvRatio = (period.received / (period.received + period.sent)).toFloat().coerceIn(0f, 1f)
                Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                    Box(Modifier.weight(recvRatio.coerceAtLeast(0.02f)).fillMaxHeight().background(GreenPositive))
                    Box(Modifier.weight((1f - recvRatio).coerceAtLeast(0.02f)).fillMaxHeight().background(RedNegative))
                }
            }
        }
    }
}

@Composable
fun SummaryStatBox(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun summarizeTransactionsByWeek(transactions: List<Transaction>): List<PeriodSummary> {
    if (transactions.isEmpty()) return emptyList()
    val monthKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
    val labelFmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US)
    val parseFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    return transactions
        .groupBy { txn ->
            runCatching { monthKey.format(parseFmt.parse(txn.timestamp) ?: java.util.Date()) }
                .getOrDefault(monthKey.format(java.util.Date()))
        }
        .map { (key, group) ->
            val date = runCatching { monthKey.parse(key) ?: java.util.Date() }.getOrDefault(java.util.Date())
            val received = group.filter { it.type.equals("Received", ignoreCase = true) }.sumOf { it.amount }
            val sent = group.filter { it.type.equals("Sent", ignoreCase = true) }.sumOf { it.amount }
            PeriodSummary(
                label = labelFmt.format(date),
                received = received,
                sent = sent,
                net = received - sent,
                count = group.size,
                currency = group.firstOrNull()?.currency ?: "USD"
            )
        }
        .sortedByDescending { it.label }
}

private fun summarizeTransactionsByMonth(transactions: List<Transaction>): List<PeriodSummary> {
    if (transactions.isEmpty()) return emptyList()
    val monthKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
    val labelFmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US)
    val parseFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    return transactions
        .groupBy { txn ->
            runCatching { monthKey.format(parseFmt.parse(txn.timestamp) ?: java.util.Date()) }
                .getOrDefault(monthKey.format(java.util.Date()))
        }
        .map { (key, group) ->
            val date = runCatching { monthKey.parse(key) ?: java.util.Date() }.getOrDefault(java.util.Date())
            val received = group.filter { it.type.equals("Received", ignoreCase = true) }.sumOf { it.amount }
            val sent = group.filter { it.type.equals("Sent", ignoreCase = true) }.sumOf { it.amount }
            PeriodSummary(
                label = labelFmt.format(date),
                received = received,
                sent = sent,
                net = received - sent,
                count = group.size,
                currency = group.firstOrNull()?.currency ?: "USD"
            )
        }
        .sortedByDescending { it.label }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TAB 4 – SETTINGS
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun SettingsTab(
    backendUrl: String,
    hasSmsPermissions: Boolean,
    connectionStatus: Boolean?,
    syncMode: String,
    onSyncModeChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onConnectionResult: (Boolean) -> Unit,
    onGrantPermission: () -> Unit
) {
    val context = LocalContext.current
    var simulationSmsText by remember { mutableStateOf("Received USD 120.50 from Fadumo Ali via EVC Plus. Ref: TXN987654321") }
    var simulationSender  by remember { mutableStateOf("EVCPlus") }
    var isSandboxExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold) }

        // ── Server Connection ──────────────────────────────────────────────────
        item {
            SettingsSection(title = "Server Connection") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Your phone and laptop must be on the same Wi-Fi network. Enter your laptop's local IP address below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value       = backendUrl,
                        onValueChange = onUrlChange,
                        label       = { Text("Server URL", color = TextMuted) },
                        placeholder = { Text("http://192.168.1.100:8000", color = TextMuted) },
                        singleLine  = true,
                        modifier    = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = TealPrimary,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White,
                            focusedLabelColor    = TealPrimary,
                            cursorColor          = TealPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
                    )
                    ConnectButton(backendUrl = backendUrl, connectionStatus = connectionStatus, onConnectionResult = onConnectionResult)
                }
            }
        }

        // ── SMS Permission ─────────────────────────────────────────────────────
        item {
            SettingsSection(title = "Sync Mode") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("website" to "Website", "sms" to "SMS").forEach { (value, label) ->
                            Button(
                                onClick = { onSyncModeChange(value) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (syncMode == value) TealPrimary else Color(0xFF1E293B),
                                    contentColor = if (syncMode == value) Color.White else TextSecondary
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(label)
                            }
                        }
                    }
                    Text(
                        if (syncMode == "website") "Website mode loads transactions from your backend API. SMS permissions are optional." else "SMS mode reads new messages and syncs parsed transactions.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            SettingsSection(title = "SMS Permission") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (hasSmsPermissions) "Permission granted" else "Permission not granted",
                            color = if (hasSmsPermissions) GreenPositive else RedNegative,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text("Needed only for SMS capture mode", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!hasSmsPermissions) {
                        Button(
                            onClick = onGrantPermission,
                            colors  = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                            shape   = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Grant", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenPositive, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }

        // ── SMS Sandbox Simulator ──────────────────────────────────────────────
        item {
            SettingsSection(title = "SMS Simulator Sandbox") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Simulate an incoming SMS transaction", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { isSandboxExpanded = !isSandboxExpanded }) {
                            Icon(
                                if (isSandboxExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null, tint = TextSecondary
                            )
                        }
                    }
                    AnimatedVisibility(visible = isSandboxExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val fieldColors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = TealPrimary,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor     = Color.White,
                                unfocusedTextColor   = Color.White,
                                cursorColor          = TealPrimary,
                                focusedLabelColor    = TealPrimary
                            )
                            OutlinedTextField(
                                value = simulationSender,
                                onValueChange = { simulationSender = it },
                                label = { Text("Sender / Provider ID", color = TextMuted) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors,
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = simulationSmsText,
                                onValueChange = { simulationSmsText = it },
                                label = { Text("SMS Message Body", color = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = fieldColors,
                                shape = RoundedCornerShape(10.dp),
                                minLines = 2
                            )
                            Button(
                                onClick = {
                                    val t = TransactionParser.parse(simulationSmsText, simulationSender)
                                    if (t != null) {
                                        TransactionHistory.addTransaction(t)
                                        CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                SMSReceiver.sendToServer(context, t)
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "✓ Simulated & synced to server!", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                Log.e("MainScreen", "Sync error", e)
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "Parsed locally, sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, "Could not parse transaction from this SMS.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors   = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                                shape    = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF0F172A))
                                Spacer(Modifier.width(8.dp))
                                Text("Simulate & Sync", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            }
                        }
                    }
                }
            }
        }

        // ── About / Network info ───────────────────────────────────────────────
        item {
            SettingsSection(title = "About Local Network Sync") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow(Icons.Default.Wifi, "LAN only — phone & laptop share the same Wi-Fi")
                    InfoRow(Icons.Default.Speed, "Fastest option: no internet round-trip")
                    InfoRow(Icons.Default.Security, "Most secure: traffic never leaves your router")
                    InfoRow(Icons.Default.PhoneAndroid, "App detects EVC Plus, eDahab, ZAAD & Sahal automatically")
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)
            content()
        }
    }
}

@Composable
fun InfoRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(16.dp))
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SHARED – Transaction Card
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun TransactionCard(txn: Transaction) {
    val isReceived  = txn.type.equals("Received", ignoreCase = true)
    val amountColor = if (isReceived) GreenPositive else RedNegative
    val pColor      = providerColor(txn.provider)
    val initials    = providerInitials(txn.provider)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape    = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Provider avatar
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(pColor.copy(alpha = 0.15f))
                    .border(1.dp, pColor.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(initials, color = pColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(txn.provider, fontWeight = FontWeight.SemiBold, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        "${if (isReceived) "+" else "-"} ${txn.currency} ${String.format("%.2f", txn.amount)}",
                        color = amountColor,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (isReceived) "From: ${txn.sender}" else "To: ${txn.receiver}",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        txn.timestamp.substringBefore("Z").replace("T", " ").take(16),
                        color = TextMuted, style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!txn.transactionId.isNullOrEmpty()) {
                    Text("Ref: ${txn.transactionId}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Type badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(amountColor.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(txn.type.uppercase(), color = amountColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
