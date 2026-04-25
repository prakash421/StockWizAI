package com.example.financestreamai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ==========================================
// SECTOR ROTATION SCREEN
// ==========================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectorRotationScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<SectorRotationResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedPeriod by remember { mutableStateOf("3mo") }
    val periods = listOf("1mo", "3mo", "6mo")
    // Backend accepts "1m" / "3m" / "6m" (not "1mo")
    fun periodParam(p: String) = p.replace("mo", "m")

    fun loadData() {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null
                data = withContext(Dispatchers.IO) { apiService.getSectorRotation(periodParam(selectedPeriod)) }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load sector rotation data"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(selectedPeriod) { loadData() }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Sector Rotation") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Period selector
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    periods.forEach { period ->
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(period.uppercase()) }
                        )
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (errorMessage != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(errorMessage!!, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // Rotation signals
            if (data?.rotationSignals != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Market Rotation Signals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                            Spacer(modifier = Modifier.height(6.dp))
                            data?.rotationSignals?.forEach { signal ->
                                val icon = when {
                                    signal.contains("INTO") -> "🟢"
                                    signal.contains("OUT OF") -> "🔴"
                                    signal.contains("Defensive") || signal.contains("⚠") -> "⚠️"
                                    signal.contains("Risk-on") -> "📈"
                                    else -> "•"
                                }
                                Text("$icon $signal", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // Top / Bottom sectors summary
            if (data?.topSectors != null || data?.bottomSectors != null) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (data?.topSectors != null) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.08f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Top Sectors", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                    data?.topSectors?.forEachIndexed { i, s ->
                                        Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (data?.bottomSectors != null) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828).copy(alpha = 0.08f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Bottom Sectors", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                                    data?.bottomSectors?.forEachIndexed { i, s ->
                                        Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sector table
            if (data?.sectors != null) {
                items(data!!.sectors) { sector ->
                    SectorCard(sector)
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectorCard(sector: SectorData) {
    val returnColor = if (sector.returnPeriod >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
    val flowColor = when (sector.moneyFlow) {
        "inflow" -> Color(0xFF2E7D32)
        "outflow" -> Color(0xFFC62828)
        else -> Color.Gray
    }
    val flowIcon = when (sector.moneyFlow) {
        "inflow" -> "💰"
        "outflow" -> "📤"
        else -> "➖"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("#${sector.rank}", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(sector.sector, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                            Text(sector.etf, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = returnColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "${if (sector.returnPeriod >= 0) "+" else ""}${"%.1f".format(sector.returnPeriod)}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = returnColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = flowColor.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                    Text("$flowIcon ${sector.moneyFlow}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = flowColor)
                }
                val recentColor = if (sector.returnRecent >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                Card(colors = CardDefaults.cardColors(containerColor = recentColor.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                    Text("Recent: ${"%+.1f".format(sector.returnRecent)}%", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = recentColor)
                }
                val volColor = if (sector.volumeChangePct > 5) Color(0xFFEF6C00) else Color.Gray
                Card(colors = CardDefaults.cardColors(containerColor = volColor.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                    Text("Vol: ${"%+.1f".format(sector.volumeChangePct)}%", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = volColor)
                }
            }
        }
    }
}

// ==========================================
// AI LEARNINGS SCREEN
// ==========================================
@Composable
fun AiLearningsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<RecommendationStats?>(null) }
    var learnings by remember { mutableStateOf<LearningsResponse?>(null) }
    var history by remember { mutableStateOf<List<RecommendationItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val statsResult = withContext(Dispatchers.IO) { apiService.getRecommendationStats() }
            stats = statsResult
            val learningsResult = withContext(Dispatchers.IO) { apiService.getLearnings() }
            learnings = learningsResult
            val historyResult = withContext(Dispatchers.IO) { apiService.getRecommendationHistory(days = 30, limit = 50) }
            history = historyResult
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load AI data"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("AI Learnings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Stats") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Signals") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("History") })
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                }
            } else if (stats?.enabled == false && learnings?.enabled == false) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(56.dp), tint = Color(0xFF7C3AED).copy(alpha = 0.5f))
                        Text("Learning in progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "The AI learning module hasn't accumulated enough data yet. Run daily scans and check back after a week — the system evaluates recommendations every Monday.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                when (selectedTab) {
                    0 -> StatsTab(stats)
                    1 -> SignalsTab(learnings)
                    2 -> HistoryTab(history)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsTab(stats: RecommendationStats?) {
    if (stats == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No stats available yet", color = Color.Gray)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Recommendations", style = MaterialTheme.typography.labelLarge)
                    Text("${stats.totalRecommendations ?: 0}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Last ${stats.horizonDays ?: 90} days", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }

        // By Strategy
        if (stats.byStrategy != null) {
            item {
                Text("Win Rate by Strategy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            stats.byStrategy.entries.sortedByDescending { it.value.winRate }.forEach { (strategy, s) ->
                item {
                    WinRateCard(label = strategy.uppercase(), winning = s.winning, losing = s.losing, total = s.total, winRate = s.winRate)
                }
            }
        }

        // By Verdict
        if (stats.byVerdict != null) {
            item {
                Text("Win Rate by Verdict", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            stats.byVerdict.entries.sortedByDescending { it.value.winRate }.forEach { (verdict, s) ->
                item {
                    WinRateCard(label = verdict, winning = s.winning, losing = s.losing, total = s.total, winRate = s.winRate)
                }
            }
        }
    }
}

@Composable
fun WinRateCard(label: String, winning: Int, losing: Int, total: Int, winRate: Double) {
    val winColor = when {
        winRate >= 70 -> Color(0xFF2E7D32)
        winRate >= 50 -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("$winning W / $losing L / $total total", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Card(colors = CardDefaults.cardColors(containerColor = winColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(8.dp)) {
                Text("${"%.1f".format(winRate)}%", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, color = winColor, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
fun SignalsTab(learnings: LearningsResponse?) {
    if (learnings == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No learnings available yet", color = Color.Gray)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Verdict baselines
        if (!learnings.verdictBaselines.isNullOrEmpty()) {
            item { Text("Verdict Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            learnings.verdictBaselines.forEach { vb ->
                item {
                    WinRateCard(label = "${vb.strategy.uppercase()} / ${vb.verdict}", winning = vb.winning, losing = vb.total - vb.winning, total = vb.total, winRate = vb.winRate)
                }
            }
        }

        // Top winning signals
        if (!learnings.topWinningSignals.isNullOrEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("🏆 Top Winning Signals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
            }
            learnings.topWinningSignals.forEach { sig ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.06f))) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sig.signal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                if (sig.strategy != null) Text(sig.strategy.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            Text("${"%.0f".format(sig.winRate)}% (${sig.total})", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Top losing signals
        if (!learnings.topLosingSignals.isNullOrEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("⚠ Top Losing Signals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
            }
            learnings.topLosingSignals.forEach { sig ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828).copy(alpha = 0.06f))) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sig.signal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                if (sig.strategy != null) Text(sig.strategy.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            Text("${"%.0f".format(sig.winRate)}% (${sig.total})", fontWeight = FontWeight.Bold, color = Color(0xFFC62828), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Suggested adjustments
        if (!learnings.suggestedAdjustments.isNullOrEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("AI Adjustments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            learnings.suggestedAdjustments.forEach { adj ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Text(adj, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun HistoryTab(history: List<RecommendationItem>) {
    if (history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No recommendation history yet", color = Color.Gray)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(history) { rec ->
            val verdictColor = when {
                rec.verdict?.contains("STRONG BUY", true) == true -> Color(0xFF1B5E20)
                rec.verdict?.contains("BUY", true) == true -> Color(0xFF2E7D32)
                rec.verdict?.contains("SELL", true) == true -> Color(0xFFC62828)
                rec.verdict?.contains("HOLD", true) == true -> Color(0xFFEF6C00)
                else -> Color.Gray
            }
            val statusColor = when (rec.finalStatus) {
                "winning" -> Color(0xFF2E7D32)
                "losing" -> Color(0xFFC62828)
                else -> Color.Gray
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(rec.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            if (rec.strategy != null) {
                                Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                    Text(rec.strategy.uppercase(), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                        if (rec.verdict != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = verdictColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                Text(rec.verdict, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = verdictColor)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rec.entryPrice != null) Text("Entry: $${"%.2f".format(rec.entryPrice)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        if (rec.scanDate != null) Text(rec.scanDate, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        if (rec.closed) {
                            Card(colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                Text(rec.finalStatus?.uppercase() ?: "CLOSED", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = statusColor)
                            }
                        } else {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                Text("ACTIVE", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                            }
                        }
                    }
                    // Outcome history
                    if (!rec.outcomeHistory.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rec.outcomeHistory.forEach { outcome ->
                                val oc = when (outcome.status) {
                                    "winning" -> Color(0xFF2E7D32)
                                    "losing" -> Color(0xFFC62828)
                                    else -> Color.Gray
                                }
                                Card(colors = CardDefaults.cardColors(containerColor = oc.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                    Text(
                                        "W${outcome.week}: ${"%+.1f".format(outcome.priceChangePct ?: 0.0)}%",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = oc
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
