package com.example.financestreamai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.Constraints
import androidx.work.NetworkType

import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption

// ==========================================
// 1. API DATA MODELS (Matching New Backend)
// ==========================================
data class LongLeapsResult(
    @SerializedName("strike") val strike: Double,
    @SerializedName("expiry") val expiry: String,
    @SerializedName("premium") val premium: Double,
    @SerializedName("delta") val delta: Double,
    @SerializedName(value = "intrinsic_buffer", alternate = ["intrinsic"]) val intrinsicBuffer: String?,
    @SerializedName("leverage") val leverage: String?,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("profile") val profile: String? = null,
    @SerializedName("otm_pct") val otmPct: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class CspResult(
    @SerializedName("strike") val strike: Double,
    @SerializedName("premium") val premium: Double,
    @SerializedName("delta") val delta: Double,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName(value = "roc", alternate = ["monthly_roc"]) val roc: String?,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class DiagonalResult(
    @SerializedName(value = "long", alternate = ["long_strike", "long_leg"]) val longLeg: String?,
    @SerializedName(value = "short", alternate = ["short_strike", "short_leg"]) val shortLeg: String?,
    @SerializedName(value = "net_debt", alternate = ["net_debit", "debit"]) val netDebt: Double,
    @SerializedName(value = "yield", alternate = ["yield_ratio"]) val yieldRatio: String?,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("short_strike_breach") val shortStrikeBreach: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class VerticalResult(
    @SerializedName(value = "strikes", alternate = ["strike"]) val strikes: String?,
    @SerializedName(value = "net_debit", alternate = ["net_debt", "debit"]) val netDebit: Double,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

/**
 * Put Credit Spread — bull put spread, defined-risk bullish. Capital efficient
 * alternative to a naked CSP: same directional exposure, ~5-10x less capital.
 * Backend field: `put_credit_spreads` (alternates: `pcs`, `put_credit_spread`).
 */
data class PutCreditSpreadResult(
    @SerializedName(value = "short_strike", alternate = ["shortStrike"]) val shortStrike: Double,
    @SerializedName(value = "long_strike", alternate = ["longStrike"]) val longStrike: Double,
    @SerializedName("width") val width: Double? = null,
    @SerializedName(value = "credit", alternate = ["net_credit"]) val credit: Double,
    @SerializedName(value = "max_loss", alternate = ["maxLoss"]) val maxLoss: Double,
    @SerializedName(value = "capital", alternate = ["margin"]) val capital: Double? = null,
    @SerializedName("delta") val delta: Double? = null,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName(value = "roc", alternate = ["monthly_roc", "roc_monthly"]) val roc: String?,
    @SerializedName(value = "roc_on_risk", alternate = ["rocOnRisk"]) val rocOnRisk: String? = null,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class StockLevels(
    @SerializedName("atr") val atr: Double? = null,
    @SerializedName("support") val support: Double? = null,
    @SerializedName("resistance") val resistance: Double? = null,
    @SerializedName("swing_high_60d") val swingHigh60d: Double? = null,
    @SerializedName("swing_low_60d") val swingLow60d: Double? = null,
    @SerializedName("high_52w") val high52w: Double? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_reward") val riskReward: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class ScanResultItem(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("price") val price: Double,
    @SerializedName(value = "company_name", alternate = ["name"]) val name: String? = null,
    @SerializedName(value = "daily_change_pct", alternate = ["change_percent", "changePercent", "pct_change"]) val changePercent: Double? = null,
    @SerializedName("rsi") val rsi: Double?,
    @SerializedName("beta") val beta: Double?,
    @SerializedName("sector") val sector: String? = null,
    @SerializedName("next_earnings_date") val nextEarningsDate: String? = null,
    @SerializedName("analyst_target") val analystTarget: AnalystTarget? = null,
    @SerializedName("sma50") val sma50: Double? = null,
    @SerializedName(value = "csps", alternate = ["csp", "csp_results"]) val csps: List<CspResult>?,
    @SerializedName(value = "diagonals", alternate = ["diagonal", "diagonal_results"]) val diagonals: List<DiagonalResult>?,
    @SerializedName(value = "verticals", alternate = ["vertical", "vertical_results"]) val verticals: List<VerticalResult>?,
    @SerializedName(value = "long_leaps", alternate = ["long_leaps_results", "leaps"]) val longLeaps: List<LongLeapsResult>?,
    @SerializedName(value = "put_credit_spreads", alternate = ["pcs", "put_credit_spread", "put_credit_spread_results"]) val putCreditSpreads: List<PutCreditSpreadResult>? = null,
    @SerializedName(value = "iv_rank", alternate = ["ivRank"]) val ivRank: String? = null,
    @SerializedName(value = "discount_from_high", alternate = ["discountFromHigh"]) val discountFromHigh: String? = null,
    @SerializedName("sma200") val sma200: Double? = null,
    @SerializedName("overall") val overall: String? = null,
    @SerializedName("stock_recommendation") val stockRecommendation: String? = null,
    @SerializedName("stock_summary") val stockSummary: String? = null,
    @SerializedName("bullish_signals") val bullishSignals: List<String>? = null,
    @SerializedName("bearish_signals") val bearishSignals: List<String>? = null,
    @SerializedName("levels") val levels: StockLevels? = null,
    @SerializedName("trending_badge") val trendingBadge: String? = null,
    @SerializedName("trending_history") val trendingHistory: TrendingHistoryInfo? = null
)

data class TrendingHistoryInfo(
    @SerializedName("appearances") val appearances: Int? = null,
    @SerializedName("consecutive_days") val consecutiveDays: Int? = null
)

data class CapitalHealth(
    @SerializedName("committed") val committed: Double
)

data class PerformanceMetrics(
    @SerializedName("monthly_realized") val monthlyRealized: Double,
    @SerializedName("monthly_goal_progress") val progress: String
)

data class ActivePosition(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("ticker") val ticker: String,
    @SerializedName("strategy") val strategy: String,
    @SerializedName("contracts") val contracts: Int,
    @SerializedName("strike") val strike: Double,
    @SerializedName("expiry") val expiry: String,
    @SerializedName("entry_premium") val entryPremium: Double
)

data class ClosedPosition(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("ticker") val ticker: String,
    @SerializedName("strategy") val strategy: String,
    @SerializedName("contracts") val contracts: Int,
    @SerializedName("strike") val strike: Double,
    @SerializedName("expiry") val expiry: String,
    @SerializedName("entry_premium") val entryPremium: Double,
    @SerializedName("exit_price") val exitPrice: Double,
    @SerializedName("exit_date") val exitDate: String
)

data class HealthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("capital_health") val capitalHealth: CapitalHealth,
    @SerializedName("performance") val performance: PerformanceMetrics,
    @SerializedName("active_positions") val activePositions: List<ActivePosition>,
    @SerializedName("closed_positions") val closedPositions: List<ClosedPosition>? = emptyList()
)

data class TradeEntry(
    val ticker: String, val strike: Double, val expiry: String, val trigger_price: Double,
    val entry_premium: Double, val contracts: Int, val strategy: String, val is_call: Int, val is_buy: Int,
    val exit_price: Double? = null, val exit_date: String? = null
)

// Backtest / AI Guru models
data class BacktestRequest(
    val ticker: String,
    val strategy: String,         // "csp", "sell_call", "vertical", "diagonal", "long_leaps"
    val action: String,           // "buy" or "sell"
    val strike: Double? = null,
    val strike_sell: Double? = null,
    val expiry: String? = null,
    val expiry_sell: String? = null,
    val premium: Double? = null
)

data class BacktestResponse(
    @SerializedName("verdict") val verdict: String,           // "BUY", "SELL", "HOLD", "AVOID"
    @SerializedName("confidence") val confidence: String,     // "High", "Medium", "Low"
    @SerializedName("summary") val summary: String,
    @SerializedName("backtest_score") val backtestScore: String? = null,
    @SerializedName("price") val price: Double? = null,
    @SerializedName("rsi") val rsi: Double? = null,
    @SerializedName("signals") val signals: List<String>? = null,
    @SerializedName("warnings") val warnings: List<String>? = null,
    @SerializedName("levels") val levels: StockLevels? = null,
    @SerializedName("learning") val learning: BacktestLearning? = null
)

// Analyst target from scan results
data class AnalystTarget(
    @SerializedName("mean") val mean: Double? = null,
    @SerializedName("low") val low: Double? = null,
    @SerializedName("high") val high: Double? = null,
    @SerializedName("num_analysts") val numAnalysts: Int? = null,
    @SerializedName("upside_pct") val upsidePct: Double? = null,
    @SerializedName("consensus") val consensus: String? = null   // e.g. "Strong Buy", "Buy", "Hold"
)

// Backtest learning info
data class BacktestLearning(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("applied") val applied: Boolean = false,
    @SerializedName("original_verdict") val originalVerdict: String? = null,
    @SerializedName("adjusted_verdict") val adjustedVerdict: String? = null,
    @SerializedName("adjustment_reason") val adjustmentReason: String? = null
)

// Async scan models
data class AsyncScanResponse(
    @SerializedName("status") val status: String,
    @SerializedName("job_id") val jobId: String,
    @SerializedName("total_tickers") val totalTickers: Int? = null,
    @SerializedName("strong_only") val strongOnly: Boolean? = null,
    @SerializedName("poll_url") val pollUrl: String? = null,
    @SerializedName("tickers") val tickers: List<String>? = null
)

data class AsyncScanStatus(
    @SerializedName("status") val status: String,
    @SerializedName("progress") val progress: String? = null,
    @SerializedName("tickers_scanned") val tickersScanned: Int? = null,
    @SerializedName("total_tickers") val totalTickers: Int? = null,
    // Per-ticker wall-time in seconds, published incrementally by the
    // backend as each ticker completes (2026-07-04 timing instrumentation
    // in _run_scan_job → _timed_process_ticker). Used by the client to
    // render "avg 3.2s/ticker" hints during a slow scan so the user can
    // see WHERE the time is going without needing Render log access.
    @SerializedName("ticker_timings") val tickerTimings: Map<String, Double>? = null
)

// Watchlist models
data class WatchlistResponse(
    @SerializedName("tickers") val tickers: List<String>,
    @SerializedName("is_default") val isDefault: Boolean? = null,
    @SerializedName("count") val count: Int? = null
)

data class WatchlistSetRequest(
    @SerializedName("tickers") val tickers: List<String>
)

// Sector Rotation models
data class SectorRotationResponse(
    @SerializedName("sectors") val sectors: List<SectorData>,
    @SerializedName("rotation_signals") val rotationSignals: List<String>? = null,
    @SerializedName("period") val period: String? = null,
    @SerializedName("top_sectors") val topSectors: List<String>? = null,
    @SerializedName("bottom_sectors") val bottomSectors: List<String>? = null,
    @SerializedName("early_rotators") val earlyRotators: List<EarlyRotator>? = null
)

data class EarlyRotator(
    @SerializedName("sector") val sector: String,
    @SerializedName("direction") val direction: String,
    @SerializedName("r1w") val r1w: Double? = null,
    @SerializedName("r4w") val r4w: Double? = null
)

data class SectorMultiWindow(
    @SerializedName("r1w") val r1w: Double? = null,
    @SerializedName("r2w") val r2w: Double? = null,
    @SerializedName("r4w") val r4w: Double? = null,
    @SerializedName("accel_1v4") val accel1v4: Double? = null,
    @SerializedName("accel_2v4") val accel2v4: Double? = null
)

data class SectorData(
    @SerializedName("sector") val sector: String,
    @SerializedName("etf") val etf: String,
    @SerializedName("return_period") val returnPeriod: Double,
    @SerializedName("return_recent") val returnRecent: Double,
    @SerializedName("volume_change_pct") val volumeChangePct: Double,
    @SerializedName("money_flow") val moneyFlow: String,
    @SerializedName("acceleration") val acceleration: Double,
    @SerializedName("rank") val rank: Int,
    @SerializedName("early_signal") val earlySignal: String? = null,
    @SerializedName("multi_window") val multiWindow: SectorMultiWindow? = null
)

// Trending enhanced (Feature 5)
data class TrendingEnhancedResponse(
    @SerializedName("results") val results: List<ScanResultItem>? = null,
    @SerializedName("trending_tickers") val trendingTickers: List<String>? = null,
    @SerializedName("history_window_days") val historyWindowDays: Int? = null,
    @SerializedName("snapshot_taken") val snapshotTaken: Boolean? = null
)

// Hourly top-10 scan (Feature 7)
data class Top10HourlyResponse(
    @SerializedName("skipped") val skipped: Boolean = false,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("top10") val top10: List<String>? = null,
    @SerializedName("candidates_evaluated") val candidatesEvaluated: Int? = null,
    @SerializedName("new_options") val newOptions: List<NewOptionItem>? = null,
    @SerializedName("generated_at") val generatedAt: String? = null
)

data class NewOptionItem(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("strike") val strike: Double? = null,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("premium") val premium: Double? = null,
    @SerializedName("bt") val bt: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null,
    @SerializedName("perf_rank") val perfRank: Int? = null
)

// Daily brief (Feature 4 \u2014 categorized)
data class DailyBriefResponse(
    @SerializedName("generated_at") val generatedAt: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("summary") val summary: BriefSummary? = null,
    @SerializedName("new_buy_signals") val newBuySignals: List<BriefBuySignal>? = null,
    @SerializedName("stop_loss_watch") val stopLossWatch: List<BriefStopWatch>? = null,
    @SerializedName("earnings_this_week") val earningsThisWeek: List<BriefEarnings>? = null,
    @SerializedName("etf_status") val etfStatus: BriefEtfStatus? = null,
    @SerializedName("sector_rotation") val sectorRotation: SectorRotationResponse? = null,
    @SerializedName("trending_today") val trendingToday: List<BriefTrendingItem>? = null
)

data class BriefSummary(
    @SerializedName("tickers_scanned") val tickersScanned: Int? = null,
    @SerializedName("strong_buys") val strongBuys: Int? = null,
    @SerializedName("stop_loss_watch_count") val stopLossWatchCount: Int? = null,
    @SerializedName("earnings_this_week_count") val earningsThisWeekCount: Int? = null
)

data class BriefBuySignal(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("price") val price: Double? = null,
    @SerializedName("verdict") val verdict: String? = null,
    @SerializedName("strike") val strike: Double? = null,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("premium") val premium: Double? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class BriefStopWatch(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("price") val price: Double? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("distance_pct") val distancePct: Double? = null
)

data class BriefEarnings(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("date") val date: String? = null
)

data class BriefEtfStatus(
    @SerializedName("top_in") val topIn: List<String>? = null,
    @SerializedName("bottom_out") val bottomOut: List<String>? = null,
    @SerializedName("early_rotators") val earlyRotators: List<EarlyRotator>? = null,
    @SerializedName("signals") val signals: List<String>? = null
)

data class BriefTrendingItem(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("consecutive_days") val consecutiveDays: Int? = null,
    @SerializedName("appearances_14d") val appearances14d: Int? = null,
    @SerializedName("badge") val badge: String? = null
)

// Recommendations / AI Feedback Loop models
data class RecommendationItem(
    @SerializedName("rec_id") val recId: String,
    @SerializedName("source") val source: String? = null,
    @SerializedName("ticker") val ticker: String,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("entry_price") val entryPrice: Double? = null,
    @SerializedName("verdict") val verdict: String? = null,
    @SerializedName("strike") val strike: Double? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("scan_date") val scanDate: String? = null,
    @SerializedName("closed") val closed: Boolean = false,
    @SerializedName("eval_count") val evalCount: Int? = null,
    @SerializedName("final_status") val finalStatus: String? = null,
    @SerializedName("outcome_history") val outcomeHistory: List<OutcomeEntry>? = null,
    @SerializedName("stock_summary") val stockSummary: String? = null,
    @SerializedName("match_detail") val matchDetail: Map<String, Any?>? = null
)

data class OutcomeEntry(
    @SerializedName("week") val week: Int,
    @SerializedName("status") val status: String,
    @SerializedName("price_change_pct") val priceChangePct: Double? = null,
    @SerializedName("eval_at") val evalAt: String? = null
)

data class RecommendationStats(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("horizon_days") val horizonDays: Int? = null,
    @SerializedName("total_recommendations") val totalRecommendations: Int? = null,
    @SerializedName("by_strategy") val byStrategy: Map<String, StrategyStats>? = null,
    @SerializedName("by_verdict") val byVerdict: Map<String, StrategyStats>? = null
)

data class StrategyStats(
    @SerializedName("winning") val winning: Int = 0,
    @SerializedName("losing") val losing: Int = 0,
    @SerializedName("neutral") val neutral: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("win_rate") val winRate: Double = 0.0
)

data class LearningsResponse(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("as_of") val asOf: String? = null,
    @SerializedName("verdict_baselines") val verdictBaselines: List<VerdictBaseline>? = null,
    @SerializedName("top_winning_signals") val topWinningSignals: List<SignalStat>? = null,
    @SerializedName("top_losing_signals") val topLosingSignals: List<SignalStat>? = null,
    @SerializedName("suggested_adjustments") val suggestedAdjustments: List<String>? = null
)

data class VerdictBaseline(
    @SerializedName("strategy") val strategy: String,
    @SerializedName("verdict") val verdict: String,
    @SerializedName("winning") val winning: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("win_rate") val winRate: Double = 0.0
)

data class SignalStat(
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("signal") val signal: String,
    @SerializedName("winning") val winning: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("win_rate") val winRate: Double = 0.0
)

// ==========================================
// 2. RETROFIT API INTERFACE
// ==========================================
interface JPFinanceApi {
    // --- Scan ---
    @GET("scan")
    suspend fun getScanResults(
        @Query("tickers") tickers: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("target_delta") targetDelta: Double? = null,
        @Query("min_roc") minRoc: Double? = null,
        @Query("include_trending") includeTrending: Boolean? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): List<ScanResultItem>

    @GET("scan/async")
    suspend fun scanAsync(
        @Query("tickers") tickers: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("include_trending") includeTrending: Boolean? = null,
        @Query("strong_only") strongOnly: Boolean? = null,
        // 2026-07-04: user-triggered scans pass "high" so the backend
        // preempts any currently-running scheduled scan (daily / ETF /
        // portfolio-flip). Scheduled workers omit this param (default
        // "normal") so they don't steal priority from each other.
        @Query("priority") priority: String? = null,
    ): AsyncScanResponse

    @GET("scan/status/{jobId}")
    suspend fun getScanStatus(@Path("jobId") jobId: String): ResponseBody

    @GET("scan/trending")
    suspend fun scanTrending(
        @Query("limit") limit: Int? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): List<ScanResultItem>

    @GET("scan/trending/async")
    suspend fun scanTrendingAsync(
        @Query("limit") limit: Int? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): AsyncScanResponse

    @GET("scan/trending/enhanced")
    suspend fun scanTrendingEnhanced(
        @Query("limit") limit: Int? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): TrendingEnhancedResponse

    @GET("scan/top10-hourly")
    suspend fun scanTop10Hourly(
        @Query("user_id") userId: String? = null,
        @Query("force") force: Boolean? = null,
        @Query("dedupe") dedupe: Boolean? = null
    ): Top10HourlyResponse

    @GET("daily-brief")
    suspend fun getDailyBrief(
        @Query("user_id") userId: String? = null,
        @Query("include_trending") includeTrending: Boolean? = null
    ): DailyBriefResponse

    // --- Health / Portfolio ---
    @GET("health")
    suspend fun getHealth(): HealthResponse

    @POST("portfolio/add")
    suspend fun addPosition(@Body trade: TradeEntry): Map<String, Any>

    @DELETE("portfolio/remove/{id}")
    suspend fun removePosition(@Path("id") id: Int): Map<String, String>

    @POST("portfolio/close/{id}")
    suspend fun closePosition(@Path("id") id: Int, @Body exitDetails: Map<String, String>): Map<String, String>

    @PUT("portfolio/update/{id}")
    suspend fun updatePosition(@Path("id") id: Int, @Body trade: TradeEntry): Map<String, Any>

    @GET("portfolio/positions")
    suspend fun getPositions(): HealthResponse

    // --- Watchlist ---
    @GET("watchlist")
    suspend fun getWatchlist(): WatchlistResponse

    @PUT("watchlist")
    suspend fun setWatchlist(@Body request: WatchlistSetRequest): WatchlistResponse

    @POST("watchlist/add")
    suspend fun addToWatchlist(@Query("ticker") ticker: String): WatchlistResponse

    @DELETE("watchlist/remove")
    suspend fun removeFromWatchlist(@Query("ticker") ticker: String): WatchlistResponse

    // --- Sector Rotation ---
    @GET("sector-rotation")
    suspend fun getSectorRotation(@Query("period") period: String? = null): SectorRotationResponse

    // --- Backtest / AI Guru ---
    @POST("backtest")
    suspend fun getBacktest(@Body request: BacktestRequest): BacktestResponse

    // --- Recommendations / AI Feedback Loop ---
    @GET("recommendations/history")
    suspend fun getRecommendationHistory(
        @Query("days") days: Int? = null,
        @Query("ticker") ticker: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("include_closed") includeClosed: Boolean? = null,
        @Query("limit") limit: Int? = null
    ): List<RecommendationItem>

    @GET("recommendations/stats")
    suspend fun getRecommendationStats(@Query("days") days: Int? = null): RecommendationStats

    @GET("recommendations/{recId}")
    suspend fun getRecommendationDetail(@Path("recId") recId: String): RecommendationItem

    @GET("recommendations/learnings")
    suspend fun getLearnings(
        @Query("strategy") strategy: String? = null,
        @Query("top_n") topN: Int? = null
    ): LearningsResponse

    @POST("recommendations/learnings/refresh")
    suspend fun refreshLearnings(): Map<String, Any>

    @POST("settings/update")
    suspend fun updateSettings(@Body settings: Map<String, String>): Map<String, String>
}

// Custom TypeAdapter: handles backend returning a single object OR an array for List<ScanResultItem>
class ScanResultListAdapter : TypeAdapter<List<ScanResultItem>>() {
    private val itemAdapter: Gson = Gson()
    override fun write(out: JsonWriter, value: List<ScanResultItem>?) {
        itemAdapter.toJson(value, object : TypeToken<List<ScanResultItem>>() {}.type)
    }
    override fun read(reader: JsonReader): List<ScanResultItem> {
        return if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            val list = mutableListOf<ScanResultItem>()
            reader.beginArray()
            while (reader.hasNext()) {
                list.add(itemAdapter.fromJson(reader, ScanResultItem::class.java))
            }
            reader.endArray()
            list
        } else {
            listOf(itemAdapter.fromJson(reader, ScanResultItem::class.java))
        }
    }
}

val scanListType: java.lang.reflect.Type = object : TypeToken<List<ScanResultItem>>() {}.type
val gson: Gson = GsonBuilder()
    .registerTypeAdapter(scanListType, ScanResultListAdapter())
    .create()

// X-User-Id interceptor: attaches Firebase UID to all requests when signed in
object UserSession {
    @Volatile var userId: String? = null

    /**
     * Ensure [userId] is populated from disk before issuing backend requests
     * from a process that didn't go through [MainActivity.onCreate] (e.g.
     * WorkManager workers spun up after process death). Reads the persisted
     * user id from [GoogleAuthManager] and caches it in-memory. Safe to call
     * repeatedly — only re-reads if the in-memory slot is null/blank.
     */
    fun ensureHydrated(context: Context) {
        if (userId.isNullOrBlank()) {
            val persisted = GoogleAuthManager.getUserId(context)
            if (!persisted.isNullOrBlank()) {
                userId = persisted
            }
        }
    }
}

/**
 * In-memory holder for the most recent scan results so the Gemini chat
 * screen can use them as conversation context without re-fetching from the
 * backend. Updated by [ScanScreen] every time a scan completes; cleared
 * implicitly on process death (which is fine — chat just opens with no
 * context until the next scan).
 */
object LastScanContext {
    @Volatile var results: List<ScanResultItem> = emptyList()
    /** AI cross-validation verdicts the user is currently looking at, keyed
     *  by ticker. Surfaced to Gemini so it can reason about the same AI
     *  consensus the user sees on screen. */
    @Volatile var aiValidations: Map<String, AiCrossValidation> = emptyMap()
    /** Active recommendation filter chip ("All", "STRONG BUY", etc.) so
     *  Gemini knows the user is looking at a filtered subset. */
    @Volatile var activeFilter: String? = null
}

private val authInterceptor = Interceptor { chain ->
    val requestBuilder = chain.request().newBuilder()
    UserSession.userId?.let { uid ->
        requestBuilder.addHeader("X-User-Id", uid)
    }
    chain.proceed(requestBuilder.build())
}

// -----------------------------------------------------------------------
// Retry interceptor
// -----------------------------------------------------------------------
// The Render free-tier backend spins down after ~15 min of inactivity and
// takes 30-60s to cold-start. That window frequently produces transient
// SocketTimeoutException / UnknownHostException / IOException / 502 / 503
// on the first request, which was surfacing to the user as a hard
// "internet connection error" even though the phone was online.
//
// This interceptor:
//   • Retries ONLY safe/idempotent methods (GET, HEAD). POST / PUT /
//     DELETE are never retried — a duplicate portfolio add would be worse
//     than a failure message.
//   • Retries on IOException (SocketTimeout, UnknownHost, ConnectException,
//     SSL handshake failure, "unexpected end of stream", …).
//   • Retries on HTTP 429 and 5xx (server-side transient / cold-start).
//   • Uses capped exponential backoff with jitter so parallel chunks
//     don't stampede the server simultaneously on retry.
//
// Retries here are essentially free for the caller — Retrofit's suspend
// wrappers see a single successful response instead of an exception.
internal class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseBackoffMs: Long = 800L,
    private val maxBackoffMs: Long = 6_000L,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val retryable = request.method.equals("GET", ignoreCase = true) ||
            request.method.equals("HEAD", ignoreCase = true)

        var attempt = 0
        var lastError: java.io.IOException? = null
        var lastResponse: okhttp3.Response? = null

        while (true) {
            // Close any previous response before issuing another.
            lastResponse?.close()
            lastResponse = null
            try {
                val resp = chain.proceed(request)
                if (!retryable || attempt >= maxRetries) return resp
                val code = resp.code
                val isTransient = code == 429 || code in 500..599
                if (!isTransient) return resp
                lastResponse = resp
                Log.w("RetryInterceptor", "HTTP $code on ${request.url} — retry ${attempt + 1}/$maxRetries")
            } catch (e: java.io.IOException) {
                if (!retryable || attempt >= maxRetries) throw e
                lastError = e
                Log.w("RetryInterceptor", "${e.javaClass.simpleName} on ${request.url} — retry ${attempt + 1}/$maxRetries: ${e.message}")
            }

            attempt++
            val exp = baseBackoffMs shl (attempt - 1).coerceAtMost(6)
            val capped = exp.coerceAtMost(maxBackoffMs)
            val jitter = (Math.random() * (capped / 3.0)).toLong()
            val delay = capped + jitter
            try {
                Thread.sleep(delay)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                // Surface the original failure rather than the interrupt.
                lastResponse?.let { return it }
                throw lastError ?: java.io.InterruptedIOException("Interrupted during retry backoff")
            }
        }
    }
}

// -----------------------------------------------------------------------
// FallbackDns
// -----------------------------------------------------------------------
// UnknownHostException from a phone that otherwise has working internet
// is almost always a broken home-router recursor or a Private-DNS server
// intermittently dropping the onrender.com hostname. Symptom is the
// user-facing toast "Cannot reach the FinanceStream backend right now."
// on EVERY endpoint (not just scan).
//
// This Dns delegates to the system resolver first (fastest, no extra
// hop) and only issues a DNS-over-HTTPS query to Cloudflare's 1.1.1.1
// when the system fails. `1.1.1.1` is an IP address so the bootstrap
// client needs no DNS of its own — the chicken-and-egg problem inherent
// to a "DNS fallback" is neatly sidestepped.
//
// Cloudflare's 1.1.1.1 does NOT log query content and enforces strict
// query minimisation, so this does not leak the user's watchlist
// browsing pattern any more than the existing HTTPS calls do.
private val fallbackDns: okhttp3.Dns by lazy {
    val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    val doh = okhttp3.dnsoverhttps.DnsOverHttps.Builder()
        .client(bootstrapClient)
        // Cloudflare public DoH resolver, addressed by IP so it needs
        // no DNS of its own.
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        // Pre-seed the resolver with 1.1.1.1's own IPs so the
        // bootstrap query itself never triggers a system-DNS call.
        .bootstrapDnsHosts(
            java.net.InetAddress.getByName("1.1.1.1"),
            java.net.InetAddress.getByName("1.0.0.1"),
        )
        .includeIPv6(true)
        .build()

    object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            return try {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (systemErr: java.net.UnknownHostException) {
                // System resolver failed — try DoH before giving up.
                try {
                    val fallback = doh.lookup(hostname)
                    Log.w(
                        "FallbackDns",
                        "System DNS failed for $hostname (${systemErr.message}); " +
                            "DoH resolved to ${fallback.joinToString { it.hostAddress ?: "?" }}"
                    )
                    fallback
                } catch (dohErr: java.net.UnknownHostException) {
                    Log.e(
                        "FallbackDns",
                        "Both system DNS AND DoH failed for $hostname " +
                            "(system: ${systemErr.message}; DoH: ${dohErr.message})"
                    )
                    // Re-throw the ORIGINAL system exception so downstream
                    // error-classification (friendlyErrorMessage) keeps its
                    // familiar UnknownHostException matching.
                    throw systemErr
                }
            }
        }
    }
}

// Render backend URL. Ensure it ends with a trailing slash.
val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl("https://financestreamai-backend.onrender.com/api/v1/")
    .client(OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        // Retry BEFORE logging so the log only shows the final attempt.
        .addInterceptor(RetryInterceptor())
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        // Cold-start on Render free tier can take up to ~30s to establish
        // the first TCP+TLS connection. 15s was too aggressive and was
        // manifesting as spurious "internet" errors.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Explicit — this is the OkHttp default but we depend on it, so
        // pin it in case a future refactor swaps the client builder.
        .retryOnConnectionFailure(true)
        // Home-router / ISP-recursor DNS failure fallback. See
        // [fallbackDns] doc-comment above.
        .dns(fallbackDns)
        // OkHttp defaults to maxRequestsPerHost=5 which throttles parallel
        // watchlist scan jobs (multiple async POST + concurrent polling on
        // the same host). Raise the per-host cap so chunked scans actually
        // run in parallel instead of serialising in the dispatcher queue.
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        })
        .build()
    )
    .addConverterFactory(GsonConverterFactory.create(gson))
    .build()

val apiService: JPFinanceApi = retrofit.create(JPFinanceApi::class.java)

// Watchlist Defaults
val MASTER_WATCHLIST_DEFAULT = listOf("ALAB", "PLTR", "CRWD", "SNOW", "TSLA", "NFLX", "ARM", "MSFT", "META", "NVDA", "MSTR", "SMCI", "APP", "SHOP", "AVGO", "SITM", "HOOD", "CRWV", "IREN", "RDDT", "AMZN", "TSM", "UBER", "COIN", "SNDK", "MU", "WDC", "STX", "BE", "NOW", "CRM", "ADBE", "VRT", "TEAM", "NBIS", "CRDO")

// Helper to parse numeric values from strings like "5.4%" or "10.2"
internal fun String?.parseToDouble(): Double {
    if (this == null) return 0.0
    return try {
        val regex = """-?\d+(\.\d+)?""".toRegex()
        val match = regex.find(this)
        match?.value?.toDoubleOrNull() ?: 0.0
    } catch (e: Exception) {
        0.0
    }
}

// Helper to format date from "2026-12-18" or "2026-12-18 $530.0C" to "12.18.2026" style
internal fun String?.formatDate(): String {
    if (this == null) return "N/A"
    return try {
        // Extract the date portion (first 10 chars matching YYYY-MM-DD)
        val dateRegex = """(\d{4})-(\d{2})-(\d{2})""".toRegex()
        val match = dateRegex.find(this) ?: return this
        val (year, month, day) = match.destructured
        val formatted = "$month.$day.$year"
        // Replace the date in the original string, preserving any suffix like " $530.0C"
        this.replaceRange(match.range, formatted)
    } catch (_: Exception) { this }
}

// Helper to produce user-friendly error messages
//
// After the RetryInterceptor is added, we only see the exception here
// when EVERY retry has failed. So the messages are honest: if we say
// "server may be waking up", we've already given the server 3 tries.
//
// If a [context] is supplied and the device genuinely has no active
// network, we say so explicitly. Otherwise we assume the backend is at
// fault (much more common than a phone that's fully offline while the
// user is actively tapping the scan button).
internal fun friendlyErrorMessage(e: Exception, context: Context? = null): String {
    val offline = context != null && !AppNetwork.hasInternet(context)
    return when (e) {
        // Backend restarted mid-scan (Render free-tier redeploy or
        // idle-timeout scale-down) — the in-memory scan_jobs dict is
        // gone, so /scan/status/{jobId} returned 404. AsyncScanPoller
        // already auto-restarted the scan once; this branch fires only
        // when the SECOND attempt also lost its job (very rare — implies
        // the server bounced twice inside the same scan window).
        is ScanJobLostException -> {
            "The backend restarted mid-scan (this can happen right after a deployment). " +
                "Please tap Scan Watchlist again in ~30 seconds."
        }
        // Backend accepted the async scan and then froze mid-run
        // (typically because a scheduled scan is holding
        // `_engine_scan_lock`). Surface the specific "stalled at N/M"
        // message so the user knows what to retry — the generic
        // SocketTimeout branch below would misleadingly say "didn't
        // respond in time" even though the backend WAS responding,
        // just not advancing.
        is ScanStalledException -> {
            "The scan stalled at ${e.ticker}/${e.total} symbols (no progress for ${e.stalledForSec}s). " +
                "The backend may be running a scheduled scan — please try again in ~30 seconds."
        }
        is SocketTimeoutException -> if (offline) {
            // Only trust the "offline" verdict if BOTH the OS says so AND
            // the request timed out from the very start. Screen-lock /
            // Doze wake transitions can flip hasInternet() to false for
            // a few hundred ms right at the moment a killed socket
            // surfaces its timeout — attributing that to "offline" makes
            // users toggle wifi mid-scan (which really does break things).
            "Network appears offline. If you weren't actively using the phone, " +
                "the screen may have locked and paused the scan — try again with the screen kept on."
        } else {
            // Two dominant causes when the device IS online:
            //  1) Render free-tier cold-start (server waking from sleep, ~30-60s).
            //  2) Backend `_engine_scan_lock` is held by a currently-running
            //     scheduled scan (daily / noon ETF / hourly flip) which
            //     serialises every scan request until it completes.
            // Since we can't tell them apart from the client, name both so
            // the user knows the request wasn't lost — just delayed.
            "The scan service didn't respond in time. It may be waking from sleep or busy running a scheduled background scan — please try again in ~30 seconds."
        }
        is UnknownHostException -> {
            // Historically this branch said "No internet connection..." when
            // AppNetwork.hasInternet() reported the device offline. In
            // practice that produced FALSE POSITIVES during long scans:
            // cellular tower handoffs, wifi transitions and brief OS Doze
            // partial-restrict states all momentarily set VALIDATED=false
            // even when the device is actually online, and the misleading
            // "you're offline" toast then had users toggling wifi mid-scan
            // (which really did break things). We now always attribute
            // UnknownHostException to backend / dispatcher unreachability;
            // if the phone is truly offline the user will see the same
            // problem in every other app and self-diagnose.
            //
            // 2026-07-04: append the underlying detail so field reports
            // include the actual host that failed to resolve. This is
            // critical when the [fallbackDns] DoH resolver also can't
            // reach the host (rare: means both system DNS and Cloudflare
            // failed), because it tells us whether the issue is
            // hostname-specific or a total loss of DNS.
            val detail = e.message?.take(120)?.trim().takeUnless { it.isNullOrBlank() }
            if (detail != null) {
                "Cannot reach the FinanceStream backend right now ($detail). " +
                    "It may be restarting or briefly unreachable — please try again in a moment."
            } else {
                "Cannot reach the FinanceStream backend right now. " +
                    "It may be restarting or briefly unreachable — please try again in a moment."
            }
        }
        is HttpException -> {
            when (e.code()) {
                429 -> "Too many requests. Please wait a moment before trying again."
                in 500..599 -> "Server error (${e.code()}). The backend may be restarting — please retry shortly."
                else -> "Server returned error ${e.code()}. Please try again."
            }
        }
        is java.io.IOException -> if (offline) {
            // Same caveat as SocketTimeoutException above: hasInternet()
            // is unreliable during Doze wake transitions and cell
            // handoffs. If we're calling this AFTER a scan has been
            // running for a minute+, the more likely cause is a
            // screen-lock kill than actual airplane mode. Give the
            // user both hypotheses instead of the definitive
            // "you're offline" line that pushed them to toggle wifi.
            "Network appears offline. If you weren't actively using the phone, " +
                "the screen may have locked and paused the scan — try again with the screen kept on."
        } else {
            // Include exception class + message so a user report contains
            // enough info to distinguish stale-pooled-connection
            // (EOFException / StreamResetException) from real transport
            // failures (ConnectException / SSLException). Historically
            // this branch printed only the generic phrase which made
            // production debugging guess-work.
            val cls = e.javaClass.simpleName
            val detail = e.message?.take(120)?.trim().takeUnless { it.isNullOrBlank() }
            if (detail != null) {
                "Connection to the backend was interrupted ($cls: $detail). Please try again."
            } else {
                "Connection to the backend was interrupted ($cls). Please try again."
            }
        }
        else -> {
            // Same diagnostic reasoning as above — tag the class so
            // unexpected error paths are self-identifying in bug reports.
            val cls = e.javaClass.simpleName
            val msg = e.message?.take(120)?.trim().takeUnless { it.isNullOrBlank() }
            if (msg != null) "$cls: $msg" else "An unexpected error occurred ($cls). Please try again."
        }
    }
}

/**
 * Thin wrapper around [ConnectivityManager] used by [friendlyErrorMessage]
 * to distinguish "phone is offline" from "backend is misbehaving". Kept
 * separate from the message helper so it can also be used pre-flight by
 * scan buttons that want to fail fast.
 */
object AppNetwork {
    fun hasInternet(context: Context): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return true // permission-denied / null service — assume online, let the request try
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            // NOTE: intentionally DOES NOT require NET_CAPABILITY_VALIDATED.
            // VALIDATED is set only after Android's captive-portal probe
            // succeeds and it flips false transiently during cellular
            // handoffs / weak-signal windows / brief wifi drops — even
            // when the device is genuinely online. Relying on it produced
            // spurious "No internet" diagnoses mid-scan. The network call
            // itself is the source of truth for whether we can reach the
            // backend; this helper only exists to soften wording when the
            // phone is DEFINITIVELY offline (airplane mode, no active
            // network at all).
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            // Never let a connectivity probe fail the actual scan.
            true
        }
    }
}

/**
 * Split a watchlist into chunks for parallel async-scan jobs. Backend processes
 * each job's tickers serially (Tradier rate-limit per worker), so N parallel
 * jobs ≈ Nx wall-clock speedup on large watchlists.
 *
 * Strategy:
 *   - <= 6 tickers: single chunk (parallelism overhead > benefit)
 *   - else: target [maxParallel] (default 6) chunks, balanced ±1 in size,
 *     with chunk size at least [minChunk] (default 4) to avoid death-by-a-
 *     thousand-tiny-jobs.
 *
 * Returned chunks are non-empty and partition the input order-preserving.
 */
internal fun chunkWatchlistForParallelScan(
    tickers: List<String>,
    maxParallel: Int = 6,
    minChunk: Int = 4
): List<List<String>> {
    if (tickers.isEmpty()) return emptyList()
    if (tickers.size <= minChunk + 2) return listOf(tickers.toList())
    val target = maxParallel.coerceAtLeast(1)
    // numChunks = min(target, ceil(size / minChunk))
    val numChunks = minOf(target, (tickers.size + minChunk - 1) / minChunk).coerceAtLeast(1)
    val baseSize = tickers.size / numChunks
    val remainder = tickers.size % numChunks
    val chunks = ArrayList<List<String>>(numChunks)
    var start = 0
    for (i in 0 until numChunks) {
        val take = baseSize + if (i < remainder) 1 else 0
        chunks.add(tickers.subList(start, start + take).toList())
        start += take
    }
    return chunks
}

// ==========================================
// Recommendation filter + metric color helpers
// ==========================================

/** Cap on tickers auto-validated by AI engines after a scan completes.
 *  AiCrossValidator fans out to ~5 LLM engines per ticker, so this limits
 *  total LLM calls to ~5 * MAX = ~75 per scan. Anything past this cap can
 *  still be validated on demand via the per-card "Run AI" button. */
const val MAX_AUTO_AI_VALIDATIONS = 15

/** True if [recommendation]/[overall] is a buy-bias signal eligible for
 *  automatic AI cross-validation. Loosely matches "STRONG BUY" and "BUY"
 *  variants emitted by the backend. */
internal fun isBuyRated(recommendation: String?, overall: String?): Boolean {
    val bucket = recommendationBucket(recommendation, overall)
    return bucket == "STRONG BUY" || bucket == "BUY"
}

/** Bucket a free-form recommendation/overall string into one of:
 *  STRONG BUY / BUY / HOLD / SELL / AVOID / OTHER.
 *
 *  Precedence (negative-first) matters: a verdict like
 *  "AVOID — STRONG BUY ZONE BELOW $X" or "HOLD; BUY DIPS" must NOT
 *  classify as a buy just because it contains the substring "BUY".
 *  Historical bug (2026-06-25, SPCK in "Best to BUY" while per-ticker
 *  scan returned AVOID) was caused by checking "STRONG BUY"/"BUY"
 *  before AVOID/SELL/HOLD. */
internal fun recommendationBucket(recommendation: String?, overall: String?): String {
    val s = ((recommendation ?: "") + " " + (overall ?: "")).uppercase()
    return when {
        s.isBlank() -> "OTHER"
        // Negative verdicts take precedence — they describe the actionable
        // stance even when the prose mentions a conditional "BUY" zone.
        "AVOID" in s -> "AVOID"
        "STRONG SELL" in s || "SELL" in s -> "SELL"
        "HOLD" in s || "NEUTRAL" in s || "CAUTION" in s -> "HOLD"
        // Positive verdicts only after no negative signal was present.
        "STRONG BUY" in s || ("STRONG" in s && "BUY" in s) -> "STRONG BUY"
        "BUY" in s || "OPPORTUNITY" in s -> "BUY"
        else -> "OTHER"
    }
}

/** True when the stock's analyst stance is explicitly AVOID or SELL.
 *  Such names must never appear in any "best to buy" surface (Best-to-BUY
 *  R:R list, NEW BUY SIGNALS) regardless of how attractive an individual
 *  option strategy (CSP / diagonal / vertical / LEAPS) looks on them. */
internal fun isStockAvoidOrSell(recommendation: String?, overall: String?): Boolean {
    val b = recommendationBucket(recommendation, overall)
    return b == "AVOID" || b == "SELL"
}

/** True if bearish technical signals materially outweigh bullish ones.
 *  Used as a secondary sanity veto in BUY-bucket selection so that, even
 *  when a (possibly stale or learner-upgraded) verdict bucketed as BUY
 *  slips through, a name where the live technicals are breaking down does
 *  NOT get promoted into "Best to BUY". */
internal fun hasBearishVeto(bullishCount: Int, bearishCount: Int): Boolean =
    bearishCount >= 2 && bearishCount > bullishCount

/** Display color for a recommendation filter chip. */
internal fun recommendationChipColor(bucket: String): androidx.compose.ui.graphics.Color = when (bucket) {
    "STRONG BUY" -> androidx.compose.ui.graphics.Color(0xFF1B5E20)
    "BUY" -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
    "HOLD" -> androidx.compose.ui.graphics.Color(0xFFEF6C00)
    "SELL" -> androidx.compose.ui.graphics.Color(0xFFC62828)
    "AVOID" -> androidx.compose.ui.graphics.Color(0xFF7F1D1D)
    else -> androidx.compose.ui.graphics.Color(0xFF1565C0) // "All"
}

enum class MetricKind { RSI, BETA, IV }

/** Bucket label for a metric value (used in chip text and unit tests). */
internal fun metricBucket(kind: MetricKind, value: Double): String = when (kind) {
    MetricKind.RSI -> when {
        value < 30 -> "Oversold"
        value < 40 -> "Cooling"
        value <= 60 -> "Healthy"
        value <= 70 -> "Climbing"
        else -> "Overbought"
    }
    MetricKind.BETA -> when {
        value < 0.7 -> "Defensive"
        value <= 1.3 -> "Balanced"
        value <= 2.0 -> "High"
        else -> "Very High"
    }
    MetricKind.IV -> when {
        value < 25 -> "Thin"
        value < 50 -> "Modest"
        value <= 75 -> "Juicy"
        else -> "Rich"
    }
}

/**
 * Pair of (color, short hint) for a metric value, biased towards the
 * options-seller perspective:
 *   - Green = favourable (healthy RSI, balanced β, juicy/rich IV premium)
 *   - Blue  = caution / wait (oversold RSI, defensive β, thin IV)
 *   - Orange / red = risk (overbought RSI, very high β, etc.)
 *
 * Designed to be consistent across the three metric types: green always
 * means "this is what you want as a premium seller".
 */
internal fun metricColor(kind: MetricKind, value: Double): Pair<androidx.compose.ui.graphics.Color, String> {
    val green = androidx.compose.ui.graphics.Color(0xFF2E7D32)
    val deepGreen = androidx.compose.ui.graphics.Color(0xFF1B5E20)
    val blue = androidx.compose.ui.graphics.Color(0xFF1565C0)
    val orange = androidx.compose.ui.graphics.Color(0xFFEF6C00)
    val red = androidx.compose.ui.graphics.Color(0xFFC62828)
    val bucket = metricBucket(kind, value)
    val color = when (kind) {
        MetricKind.RSI -> when (bucket) {
            "Healthy" -> green
            "Climbing" -> orange
            "Overbought" -> red
            "Cooling" -> blue
            else -> blue // Oversold
        }
        MetricKind.BETA -> when (bucket) {
            "Balanced" -> green
            "High" -> orange
            "Very High" -> red
            else -> blue // Defensive
        }
        MetricKind.IV -> when (bucket) {
            "Rich" -> deepGreen
            "Juicy" -> green
            "Modest" -> orange
            else -> red // Thin
        }
    }
    return color to bucket
}

/** Modal explaining the colored RSI/β/IV chips. */
@Composable
fun MetricLegendDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("Color legend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Colors are biased toward the premium-seller view: green = favourable, blue = caution/wait, orange/red = risk.", style = MaterialTheme.typography.bodySmall)
                LegendRow("RSI", listOf(
                    "Oversold (<30)" to androidx.compose.ui.graphics.Color(0xFF1565C0),
                    "Cooling (30–40)" to androidx.compose.ui.graphics.Color(0xFF1565C0),
                    "Healthy (40–60)" to androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    "Climbing (60–70)" to androidx.compose.ui.graphics.Color(0xFFEF6C00),
                    "Overbought (>70)" to androidx.compose.ui.graphics.Color(0xFFC62828)
                ))
                LegendRow("Beta (β) — relative to S&P", listOf(
                    "Defensive (<0.7)" to androidx.compose.ui.graphics.Color(0xFF1565C0),
                    "Balanced (0.7–1.3)" to androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    "High (1.3–2.0)" to androidx.compose.ui.graphics.Color(0xFFEF6C00),
                    "Very High (>2.0)" to androidx.compose.ui.graphics.Color(0xFFC62828)
                ))
                LegendRow("IV Rank — premium richness", listOf(
                    "Thin (<25%)" to androidx.compose.ui.graphics.Color(0xFFC62828),
                    "Modest (25–50%)" to androidx.compose.ui.graphics.Color(0xFFEF6C00),
                    "Juicy (50–75%)" to androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    "Rich (>75%)" to androidx.compose.ui.graphics.Color(0xFF1B5E20)
                ))
            }
        }
    )
}

@Composable
private fun LegendRow(title: String, entries: List<Pair<String, androidx.compose.ui.graphics.Color>>) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        entries.forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(c, RoundedCornerShape(2.dp))
                )
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Shared launcher used by both auto-validate and the manual "Run AI"
 *  button so dedup behaviour matches. */
private fun triggerAiValidation(
    item: ScanResultItem,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    aiValidations: androidx.compose.runtime.snapshots.SnapshotStateMap<String, AiCrossValidation>,
    aiValidatingTickers: Set<String>,
    setValidating: (Set<String>) -> Unit
) {
    if (aiValidations.containsKey(item.ticker)) return
    if (aiValidatingTickers.contains(item.ticker)) return
    setValidating(aiValidatingTickers + item.ticker)
    scope.launch {
        try {
            val strategies = buildList {
                if (!item.csps.isNullOrEmpty()) add("CSP")
                if (!item.putCreditSpreads.isNullOrEmpty()) add("PCS")
                if (!item.diagonals.isNullOrEmpty()) add("Diagonal")
                if (!item.verticals.isNullOrEmpty()) add("Vertical")
                if (!item.longLeaps.isNullOrEmpty()) add("LEAPS")
            }.joinToString(", ")
            val result = AiCrossValidator.validate(
                context = context,
                ticker = item.ticker,
                price = item.price,
                recommendation = item.stockRecommendation ?: item.overall ?: "STRONG BUY",
                signals = item.bullishSignals ?: emptyList(),
                warnings = item.bearishSignals ?: emptyList(),
                levels = item.levels,
                sector = item.sector,
                strategies = strategies
            )
            aiValidations[item.ticker] = result
        } catch (e: Exception) {
            Log.e("AiValidation", "Failed for ${item.ticker}: ${e.message}")
        } finally {
            // Re-read current set since other launches may have mutated it.
            // The compose state setter handles atomic swap.
            setValidating((aiValidatingTickers - item.ticker))
        }
    }
}

/**
 * Merge two backtest responses (long-call BUY + CSP SELL on a shared expiry)
 * into a single synthetic verdict for the CSP-Funded Call combo.
 *
 * Verdict logic:
 *   - both legs return STRONG (BUY for the call, SELL for the put) -> STRONG BUY
 *   - both legs return positive (BUY + SELL)                       -> BUY
 *   - one leg negative                                              -> HOLD
 *   - both legs negative or missing                                 -> AVOID
 *
 * Confidence is the lower of the two leg confidences (or "Low" if one is missing).
 *
 * Net debit = call premium - put premium (per share). When the put premium
 * fully covers the call, the position opens at zero or negative cost — that
 * fact is the headline of the summary.
 */
internal fun mergeCfComboResponses(
    callRes: BacktestResponse?,
    putRes: BacktestResponse?,
    callStrike: Double,
    putStrike: Double,
    callPrem: Double,
    putPrem: Double,
    expiry: String
): BacktestResponse {
    fun tier(verdict: String?, isSell: Boolean): Int {
        val v = verdict?.uppercase() ?: return -2
        return when {
            isSell && v.contains("STRONG SELL") -> 2
            isSell && v.contains("SELL") -> 1
            !isSell && v.contains("STRONG BUY") -> 2
            !isSell && v.contains("BUY") -> 1
            v.contains("HOLD") -> 0
            else -> -1
        }
    }
    val callTier = tier(callRes?.verdict, isSell = false)
    val putTier = tier(putRes?.verdict, isSell = true)

    val mergedVerdict = when {
        callTier >= 2 && putTier >= 2 -> "STRONG BUY"
        callTier >= 1 && putTier >= 1 -> "BUY"
        callTier <= -1 && putTier <= -1 -> "AVOID"
        callTier <= 0 || putTier <= 0 -> "HOLD"
        else -> "HOLD"
    }
    val rank = mapOf("High" to 3, "Medium" to 2, "Low" to 1, "None" to 0)
    val cConf = callRes?.confidence ?: "None"
    val pConf = putRes?.confidence ?: "None"
    val mergedConfidence = if ((rank[cConf] ?: 0) <= (rank[pConf] ?: 0)) cConf else pConf

    val netDebitPerShare = callPrem - putPrem
    val netDebitContract = netDebitPerShare * 100.0
    val coveragePct = if (callPrem > 0) (putPrem / callPrem * 100.0).coerceAtMost(999.0) else 0.0
    val maxLossPerShare = putStrike - putPrem + maxOf(0.0, netDebitPerShare)

    val summaryHeader = buildString {
        append("CSP-Funded Call combo · exp $expiry · ")
        append("BUY \$${"%.2f".format(callStrike)} call @ \$${"%.2f".format(callPrem)} · ")
        append("SELL \$${"%.2f".format(putStrike)} put @ \$${"%.2f".format(putPrem)}")
    }
    val economics = buildString {
        append("Net debit ≈ \$${"%.2f".format(netDebitPerShare)}/share ")
        append("(\$${"%.0f".format(netDebitContract)}/contract). ")
        append("Put premium covers ${"%.0f".format(coveragePct)}% of call cost. ")
        append("Max loss if assigned ≈ \$${"%.2f".format(maxLossPerShare)}/share at put strike.")
    }
    val mergedSummary = buildString {
        append(summaryHeader)
        append("\n\n")
        append(economics)
        callRes?.summary?.takeIf { it.isNotBlank() }?.let { append("\n\nCall leg: ").append(it) }
        putRes?.summary?.takeIf { it.isNotBlank() }?.let { append("\n\nPut leg: ").append(it) }
    }

    val signals = buildList<String> {
        if (netDebitPerShare <= 0) add("Self-funded combo (put premium ≥ call cost)")
        if (coveragePct >= 50) add("Put premium covers ${"%.0f".format(coveragePct)}% of call debit")
        callRes?.signals?.forEach { add("Call: $it") }
        putRes?.signals?.forEach { add("Put: $it") }
    }
    val warnings = buildList<String> {
        if (callTier <= 0) add("Long call leg backtest is weak (verdict: ${callRes?.verdict ?: "N/A"})")
        if (putTier <= 0) add("CSP leg backtest is weak (verdict: ${putRes?.verdict ?: "N/A"})")
        if (putStrike >= callStrike) add("Put strike should be below call strike for this combo")
        callRes?.warnings?.forEach { add("Call: $it") }
        putRes?.warnings?.forEach { add("Put: $it") }
    }

    return BacktestResponse(
        verdict = mergedVerdict,
        confidence = mergedConfidence,
        summary = mergedSummary,
        backtestScore = listOfNotNull(
            callRes?.backtestScore?.let { "Call BT $it" },
            putRes?.backtestScore?.let { "Put BT $it" }
        ).joinToString(" · ").ifBlank { null },
        price = callRes?.price ?: putRes?.price,
        rsi = callRes?.rsi ?: putRes?.rsi,
        signals = signals,
        warnings = warnings,
        levels = callRes?.levels ?: putRes?.levels,
        learning = null
    )
}

// Helper to translate Credential Manager exceptions into actionable messages.
private fun friendlyCredentialError(e: androidx.credentials.exceptions.GetCredentialException): String {
    val type = e.type
    val msg = e.message.orEmpty()
    return when {
        e is androidx.credentials.exceptions.GetCredentialCancellationException ->
            "Sign-in was cancelled."
        e is androidx.credentials.exceptions.NoCredentialException ->
            "No Google accounts available on this device. Add a Google account in " +
                "Android Settings, then try again."
        e is androidx.credentials.exceptions.GetCredentialInterruptedException ->
            "Sign-in was interrupted. Please try again."
        type.contains("DEVELOPER", true) || msg.contains("DEVELOPER_ERROR", true) ->
            "Sign-in misconfigured (DEVELOPER_ERROR). Verify the Web Client ID and that " +
                "this app's package name + SHA-1 fingerprint are registered in Google Cloud Console."
        msg.contains("16:", true) || msg.contains("network", true) ->
            "Network problem during sign-in. Check connectivity and retry."
        else -> "Sign-in failed: ${msg.ifBlank { type }}"
    }
}

/**
 * Normalise user-typed expiry dates to YYYY-MM-DD expected by the backend.
 * Accepted input examples:
 *   2026-06-18   → 2026-06-18  (already correct)
 *   18Jun2026    → 2026-06-18
 *   18Jun26      → 2026-06-18
 *   18-Jun-2026  → 2026-06-18
 *   Jun 18 2026  → 2026-06-18
 *   06/18/2026   → 2026-06-18
 *   06/18/26     → 2026-06-18
 * Returns null if the string cannot be parsed.
 */
private fun normaliseExpiry(raw: String): String? {
    val s = raw.trim().ifBlank { return null }
    // Already in YYYY-MM-DD
    val isoRe = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    isoRe.matchEntire(s)?.let { return s }

    val months = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
    )

    fun expandYear(y: String) = if (y.length == 2) "20$y" else y

    // DDMonYYYY or DDMonYY or DD-Mon-YYYY etc.: 18Jun2026, 18-Jun-2026
    val dmy = Regex("""^(\d{1,2})[-\s]?([A-Za-z]{3,9})[-\s]?(\d{2,4})$""")
    dmy.matchEntire(s)?.let { m ->
        val day = m.groupValues[1].padStart(2, '0')
        val mon = months[m.groupValues[2].lowercase().take(3)] ?: return null
        val yr  = expandYear(m.groupValues[3])
        return "$yr-$mon-$day"
    }

    // MonDDYYYY or Mon-DD-YYYY: Jun182026, Jun 18 2026
    val mdy = Regex("""^([A-Za-z]{3,9})[-\s]?(\d{1,2})[-\s]?(\d{2,4})$""")
    mdy.matchEntire(s)?.let { m ->
        val mon = months[m.groupValues[1].lowercase().take(3)] ?: return null
        val day = m.groupValues[2].padStart(2, '0')
        val yr  = expandYear(m.groupValues[3])
        return "$yr-$mon-$day"
    }

    // MM/DD/YYYY or MM/DD/YY
    val slash = Regex("""^(\d{1,2})/(\d{1,2})/(\d{2,4})$""")
    slash.matchEntire(s)?.let { m ->
        val mon = m.groupValues[1].padStart(2, '0')
        val day = m.groupValues[2].padStart(2, '0')
        val yr  = expandYear(m.groupValues[3])
        return "$yr-$mon-$day"
    }

    return null // unrecognised
}

// ==========================================
// SIGNAL ABBREVIATION (compact display)
// ==========================================
private fun abbreviateSignal(signal: String): String {
    val s = signal.trim()
    return when {
        s.contains("200-day SMA", true) || s.contains("SMA200", true) || s.contains("200 SMA", true) ->
            if (s.contains("above", true)) "↑SMA200" else "↓SMA200"
        s.contains("50-day SMA", true) || s.contains("SMA50", true) || s.contains("50 SMA", true) ->
            if (s.contains("above", true)) "↑SMA50" else "↓SMA50"
        s.contains("oversold", true) -> "RSI Oversold"
        s.contains("overbought", true) -> "RSI Overbought"
        s.contains("golden cross", true) -> "Golden Cross"
        s.contains("death cross", true) -> "Death Cross"
        s.contains("MACD", true) -> if (s.contains("bull", true) || s.contains("above", true) || s.contains("positive", true)) "MACD Bullish" else "MACD Bearish"
        s.contains("momentum", true) -> if (s.contains("positive", true) || s.contains("bull", true) || s.contains("strong", true)) "Momentum +" else "Momentum −"
        s.contains("volume", true) -> if (s.contains("high", true) || s.contains("above", true) || s.contains("increas", true)) "Volume ↑" else "Volume ↓"
        s.contains("52-week", true) || s.contains("52 week", true) ->
            if (s.contains("near", true) || s.contains("high", true)) "Near 52W High" else "Near 52W Low"
        s.contains("breakout", true) -> "Breakout"
        s.contains("breakdown", true) -> "Breakdown"
        s.contains("support", true) -> "At Support"
        s.contains("resistance", true) -> "At Resistance"
        s.contains("dividend", true) -> "Dividend"
        s.contains("earnings", true) -> "Earnings"
        s.contains("uptrend", true) || s.contains("up trend", true) -> "Uptrend"
        s.contains("downtrend", true) || s.contains("down trend", true) -> "Downtrend"
        s.contains("bollinger", true) -> "Bollinger Band"
        s.contains("win r", true) -> s  // Keep full: "60-day win rate: 75%"
        s.contains("revenue", true) -> s  // Keep full: "Revenue growth +12%"
        s.contains("price", true) && s.contains("%", true) -> s  // Keep: "Price +2.0%"
        s.contains("below", true) && s.contains("%", true) -> s  // Keep: "25% below 52..."
        s.contains("growth", true) -> s
        s.contains("decline", true) -> s
        else -> s  // Show full text — never truncate
    }
}

// ==========================================
// LOCAL PORTFOLIO CACHE (survives app restart)
// ==========================================
object PortfolioCache {
    private const val PREFS_NAME = "PortfolioCache"
    private const val KEY_ACTIVE = "active_positions"
    private const val KEY_CLOSED = "closed_positions"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePositions(context: Context, active: List<ActivePosition>, closed: List<ClosedPosition>) {
        prefs(context).edit()
            .putString(KEY_ACTIVE, gson.toJson(active))
            .putString(KEY_CLOSED, gson.toJson(closed))
            .apply()
    }

    fun addPosition(context: Context, pos: ActivePosition) {
        val current = loadActivePositions(context).toMutableList()
        current.add(pos)
        prefs(context).edit().putString(KEY_ACTIVE, gson.toJson(current)).apply()
    }

    fun updatePosition(context: Context, index: Int, pos: ActivePosition) {
        val current = loadActivePositions(context).toMutableList()
        if (index in current.indices) {
            current[index] = pos
            prefs(context).edit().putString(KEY_ACTIVE, gson.toJson(current)).apply()
        }
    }

    fun removePosition(context: Context, index: Int) {
        val current = loadActivePositions(context).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            prefs(context).edit().putString(KEY_ACTIVE, gson.toJson(current)).apply()
        }
    }

    fun loadActivePositions(context: Context): List<ActivePosition> {
        val json = prefs(context).getString(KEY_ACTIVE, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<ActivePosition>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }

    fun loadClosedPositions(context: Context): List<ClosedPosition> {
        val json = prefs(context).getString(KEY_CLOSED, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<ClosedPosition>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }
}

// ==========================================
// NOTIFICATION HISTORY CACHE
// ==========================================
data class NotificationRecord(
    val title: String,
    val body: String,
    val timestamp: Long
)

object NotificationCache {
    private const val PREFS_NAME = "NotificationHistory"
    private const val KEY_NOTIFICATIONS = "notifications"
    private const val MAX_NOTIFICATIONS = 50

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, title: String, body: String) {
        val current = load(context).toMutableList()
        current.add(0, NotificationRecord(title, body, System.currentTimeMillis()))
        if (current.size > MAX_NOTIFICATIONS) current.subList(MAX_NOTIFICATIONS, current.size).clear()
        prefs(context).edit().putString(KEY_NOTIFICATIONS, gson.toJson(current)).apply()
    }

    fun load(context: Context): List<NotificationRecord> {
        val json = prefs(context).getString(KEY_NOTIFICATIONS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<NotificationRecord>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }
}

// ==========================================
// TRENDING ACTIONABLE-ALERT HELPERS
// ==========================================
/**
 * Filters the trending-stocks dump down to actionable items and posts a
 * pop-up notification.
 *
 * STRATEGY (revised May 2026):
 * Trending names are momentum plays — selling cash-secured puts caps your
 * upside on a stock that's running. Instead, surface two more aligned
 * tactics for each pick:
 *
 *   1) **4-8 week long call** — capture the move with leverage. We pick the
 *      long-call entry (`long_leaps` payload) whose expiry falls in the
 *      28-63 day window (or the nearest if none in window).
 *   2) **~40Δ CSP for premium harvest, then use the premium to buy that
 *      call.** A 40-delta put writes more premium than a 25Δ put, and the
 *      cash funds the long-call leg — net debit drops while you keep
 *      directional exposure. We pick the CSP entry whose |delta| is closest
 *      to 0.40 (band 0.30-0.50).
 *
 * If neither leg is available the item is dropped from the actionable list.
 */
object TrendingAlerts {
    private const val CHANNEL_ID = "trending_alerts"
    private const val CHANNEL_NAME = "Trending Stock Alerts"
    private const val NOTIFICATION_ID = 9003
    private const val MAX_ACTIONABLE = 5

    private const val MIN_CALL_DAYS = 28      // 4 weeks
    private const val MAX_CALL_DAYS = 63      // 9 weeks (gives a small overshoot for liquidity)
    private const val PREFERRED_CSP_DELTA = 0.40
    private const val MIN_CSP_DELTA = 0.30
    private const val MAX_CSP_DELTA = 0.50

    // CSP-Funded Call combo (high-IV trending only)
    // Selling a 6-8wk CSP and using its premium to buy a same-expiry call gives
    // a near-zero-cost long synthetic on the underlying. Only worth doing when
    // implied volatility is rich enough that the put premium is meaningful.
    private const val MIN_IV_RANK_FOR_COMBO = 35.0   // %
    private const val MIN_COMBO_DAYS = 28
    private const val MAX_COMBO_DAYS = 70
    private const val MIN_COMBO_CSP_DELTA = 0.20
    private const val MAX_COMBO_CSP_DELTA = 0.45

    /** Pick the best long call in the 4-8 week window. Returns null if no
     *  call entry has an expiry inside that window. (We deliberately do NOT
     *  fall back to LEAPS here — the backend's `long_leaps` array is filled
     *  with 1-2 year out contracts, and labelling a 89-week LEAP as a
     *  "near-term momentum call" is misleading.) */
    private fun pickNearTermCall(item: ScanResultItem): LongLeapsResult? {
        val leaps = item.longLeaps?.takeIf { it.isNotEmpty() } ?: return null
        val today = java.time.LocalDate.now()
        val withDays = leaps.mapNotNull { l ->
            val d = parseExpiryDays(l.expiry, today) ?: return@mapNotNull null
            l to d
        }
        val inWindow = withDays.filter { it.second in MIN_CALL_DAYS..MAX_CALL_DAYS }
        if (inWindow.isEmpty()) return null
        // Best bt% among in-window calls
        return inWindow.maxByOrNull { parseBt(it.first.bt) }?.first
    }

    /** Pick the best LEAPS-style long call (>= 6 months out) for items where
     *  no near-term call is offered. Returns null if there are no leaps. */
    private fun pickLeapsCall(item: ScanResultItem): LongLeapsResult? {
        val leaps = item.longLeaps?.takeIf { it.isNotEmpty() } ?: return null
        val today = java.time.LocalDate.now()
        val withDays = leaps.mapNotNull { l ->
            val d = parseExpiryDays(l.expiry, today) ?: return@mapNotNull null
            l to d
        }
        // Prefer the highest bt%; tie-break on shortest expiry (less time decay risk).
        return withDays.maxWithOrNull(
            compareBy<Pair<LongLeapsResult, Int>>({ parseBt(it.first.bt) }, { -it.second })
        )?.first
    }

    /** Pick the CSP whose |delta| is closest to ~0.40 (band 0.30-0.50). */
    private fun pickPremiumHarvestCsp(item: ScanResultItem): CspResult? {
        val csps = item.csps?.takeIf { it.isNotEmpty() } ?: return null
        return csps
            .filter { kotlin.math.abs(it.delta) in MIN_CSP_DELTA..MAX_CSP_DELTA }
            .minByOrNull { kotlin.math.abs(kotlin.math.abs(it.delta) - PREFERRED_CSP_DELTA) }
            ?: csps.minByOrNull { kotlin.math.abs(kotlin.math.abs(it.delta) - PREFERRED_CSP_DELTA) }
    }

    /**
     * Pick a CSP suitable for the CSP-Funded Call combo.
     *
     * The combo: sell a 6-8wk CSP, take the premium, immediately buy a
     * same-expiry call (near ATM) with that cash. Result: a near-zero-cost
     * synthetic-long position with capped tail risk (the put strike).
     *
     * Only worth doing when:
     *  - IV rank >= 35% (otherwise the put premium is too thin to fund a call)
     *  - CSP expiry falls in the 28-70 day window (so the call we'll pair
     *    with is also a short-dated 6-8wk contract, not a LEAP)
     *  - CSP |delta| in 0.20-0.45 (out-of-the-money but not too far)
     *
     * Returns null when the trade isn't well-formed; caller should treat
     * null as "do not surface the combo line".
     */
    private fun pickComboCsp(item: ScanResultItem): CspResult? {
        val ivRank = parseIvRank(item.ivRank) ?: return null
        if (ivRank < MIN_IV_RANK_FOR_COMBO) return null
        val csps = item.csps?.takeIf { it.isNotEmpty() } ?: return null
        val today = java.time.LocalDate.now()
        return csps
            .filter { csp ->
                val days = parseExpiryDays(csp.expiry, today) ?: return@filter false
                val absDelta = kotlin.math.abs(csp.delta)
                days in MIN_COMBO_DAYS..MAX_COMBO_DAYS &&
                    absDelta in MIN_COMBO_CSP_DELTA..MAX_COMBO_CSP_DELTA
            }
            .maxByOrNull { it.premium }   // richest premium = best funding for the call leg
    }

    private fun parseIvRank(s: String?): Double? =
        s?.replace("%", "")?.trim()?.toDoubleOrNull()

    private fun parseExpiryDays(expiry: String?, today: java.time.LocalDate): Int? {
        if (expiry.isNullOrBlank()) return null
        val rx = """(\d{4})-(\d{2})-(\d{2})""".toRegex()
        val m = rx.find(expiry) ?: return null
        return try {
            val (y, mo, d) = m.destructured
            val exp = java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            java.time.temporal.ChronoUnit.DAYS.between(today, exp).toInt()
        } catch (_: Exception) { null }
    }

    private fun parseBt(bt: String?): Double = bt?.replace("%", "")?.trim()?.toDoubleOrNull() ?: 0.0

    /** A trending result is actionable when:
     *  - recommendation is bullish and not stretched (RSI 28..78), AND
     *  - we have at least one of: a near-term call, a LEAP, or a 30-50Δ CSP.
     *  Each available leg is surfaced individually with full strike + expiry
     *  + premium so the user can place the trade without guessing. */
    fun isActionable(item: ScanResultItem): Boolean {
        val rec = (item.stockRecommendation ?: item.overall ?: "").uppercase()
        if (rec.contains("SELL") || rec.contains("AVOID")) return false
        if (!rec.contains("BUY") && !rec.contains("STRONG")) return false
        val rsi = item.rsi ?: 50.0
        if (rsi >= 78 || rsi <= 28) return false
        return pickNearTermCall(item) != null ||
            pickLeapsCall(item) != null ||
            pickPremiumHarvestCsp(item) != null ||
            pickComboCsp(item) != null
    }

    /** Composite score: prefers strong recs + bullish signals + analyst upside + healthy RSI. */
    private fun score(item: ScanResultItem): Double {
        val rec = (item.stockRecommendation ?: item.overall ?: "").uppercase()
        val recScore = when {
            rec.contains("STRONG BUY") -> 30.0
            rec.contains("BUY") -> 15.0
            else -> 0.0
        }
        val rsi = item.rsi ?: 50.0
        val rsiScore = when {
            rsi in 45.0..65.0 -> 15.0
            rsi in 35.0..70.0 -> 8.0
            else -> 0.0
        }
        val signals = ((item.bullishSignals?.size ?: 0) - (item.bearishSignals?.size ?: 0)).toDouble().coerceIn(-5.0, 15.0)
        val upside = (item.analystTarget?.upsidePct ?: 0.0).coerceIn(0.0, 30.0)
        val change = (item.changePercent ?: 0.0).coerceIn(-10.0, 10.0)
        return recScore + rsiScore + signals + upside + change
    }

    fun pickActionable(results: List<ScanResultItem>): List<ScanResultItem> =
        results.filter { isActionable(it) }
            .sortedByDescending { score(it) }
            .take(MAX_ACTIONABLE)

    /**
     * Tenor label for a contract's days-to-expiry.
     *  <  90d  -> "Nwk"   (short term)
     *  < 365d  -> "Nmo"   (medium term)
     *  >=365d  -> "LEAP"  (long term)
     */
    private fun tenorLabel(days: Int?): String {
        if (days == null) return ""
        return when {
            days < 90 -> "${(days / 7.0).let { "%.0f".format(it) }}wk"
            days < 365 -> "${(days / 30.0).let { "%.0f".format(it) }}mo"
            else -> "LEAP"
        }
    }

    /**
     * Headline lines for an actionable trending item. Every line ALWAYS
     * carries: expiry date, strike, and premium per share. We surface up to
     * three independent legs (near-term call, LEAPS, and 40Δ CSP funding)
     * — each is a self-contained trade idea, not a fabricated combo.
     *
     * Format (2026-07-02 redesign):
     *  - One strategy per line, prefixed by a strategy emoji for scan-ability.
     *  - Fields separated by " · " (mid-dot) rather than parens with embedded
     *    dashes — much easier to read at a glance in a notification.
     *  - CFC combo collapsed to a single line (previously spilled across two
     *    with a `└` continuation that rendered awkwardly).
     *  - Analyst upside no longer emitted here; the caller merges it into
     *    the reasoning line so we don't waste a whole line on one number.
     */
    private fun strategyLines(item: ScanResultItem): List<String> {
        val out = mutableListOf<String>()
        val today = java.time.LocalDate.now()
        val nearCall = pickNearTermCall(item)
        val leapCall = if (nearCall == null) pickLeapsCall(item) else null
        val csp = pickPremiumHarvestCsp(item)

        if (nearCall != null) {
            val days = parseExpiryDays(nearCall.expiry, today)
            val tenor = tenorLabel(days)
            val expiry = nearCall.expiry.formatDate()
            out += "\uD83D\uDCC8 Buy $tenor Call — \$${nearCall.strike} exp $expiry · \$${"%.2f".format(nearCall.premium)} · Δ%.2f".format(nearCall.delta)
        } else if (leapCall != null) {
            // No 4-8wk call available — surface the LEAP honestly labelled.
            val days = parseExpiryDays(leapCall.expiry, today)
            val tenor = tenorLabel(days)
            val expiry = leapCall.expiry.formatDate()
            val lev = leapCall.leverage?.let { " · lev $it" } ?: ""
            out += "\uD83D\uDCC8 Buy $tenor Call — \$${leapCall.strike} exp $expiry · \$${"%.2f".format(leapCall.premium)} · Δ%.2f$lev".format(leapCall.delta)
        }

        if (csp != null) {
            val deltaStr = "%.2f".format(kotlin.math.abs(csp.delta))
            val expiry = csp.expiry?.formatDate() ?: "near-term"
            val premium = "\$${"%.2f".format(csp.premium)}"
            // If we have a call leg, show how much of its debit the CSP covers.
            val anyCall = nearCall ?: leapCall
            val coverage = if (anyCall != null && anyCall.premium > 0) {
                val pct = (csp.premium / anyCall.premium * 100.0).coerceAtMost(999.0)
                " · covers ${"%.0f".format(pct)}% of call debit"
            } else ""
            // Monthly ROC (backend already normalises to a per-month figure)
            // — surfaced here so the trending CSP idea reports the same
            // income yield the user sees in the daily "📊 CSPs" section.
            val rocStr = csp.roc?.takeIf { it.isNotBlank() }?.let { " · ROC/mo $it" } ?: ""
            val tag = if (anyCall != null) " · funds the call" else " · premium harvest"
            out += "\uD83D\uDCB5 Sell ${deltaStr}Δ CSP — \$${csp.strike} exp $expiry · $premium$rocStr$coverage$tag"
        }

        // CSP-Funded Call combo (high-IV only) — sell a 6-8wk CSP and use
        // the premium to buy a same-expiry call. Near-zero-cost synthetic long.
        // Collapsed onto ONE line for readability; the two-line variant with
        // a `└` continuation rendered poorly in the notification bigText body.
        val comboCsp = pickComboCsp(item)
        if (comboCsp != null) {
            val ivRank = parseIvRank(item.ivRank)
            val ivStr = ivRank?.let { " · IV-rank ${"%.0f".format(it)}%" } ?: ""
            val deltaStr = "%.2f".format(kotlin.math.abs(comboCsp.delta))
            val expiry = comboCsp.expiry?.formatDate() ?: "near-term"
            // Suggest an ATM call strike near the current underlying price (rounded to nearest $5).
            val callStrikeHint = (kotlin.math.round(item.price / 5.0) * 5.0)
            out += "\uD83D\uDD00 CFC Combo — sell \$${comboCsp.strike}P @ \$${"%.2f".format(comboCsp.premium)} (${deltaStr}Δ) + buy ~\$${"%.0f".format(callStrikeHint)}C same expiry $expiry · net debit ≈ 0$ivStr"
        }
        return out
    }

    private fun reasoning(item: ScanResultItem): String {
        val parts = mutableListOf<String>()
        item.rsi?.let { parts += "RSI ${"%.0f".format(it)}" }
        val sma50 = item.sma50; val sma200 = item.sma200
        if (sma50 != null && sma200 != null) {
            val golden = sma50 >= sma200
            parts += if (golden && item.price >= sma50) "above SMA50/200" else "MA: mixed"
        }
        item.bullishSignals?.firstOrNull()?.let { parts += it }
        item.sector?.takeIf { it.isNotBlank() }?.let { parts += it }
        // Analyst upside merged into the reasoning line so it doesn't waste a
        // dedicated row in the notification.
        item.analystTarget?.upsidePct?.takeIf { it > 0 }?.let {
            parts += "analyst +%.0f%%".format(it)
        }
        return parts.take(5).joinToString(" \u2022 ")
    }

    /**
     * Filters [results], persists & shows a pop-up notification for the top
     * actionable picks, and returns the filtered list so the caller can also
     * render it inline. If no items pass the filter, no notification is sent.
     */
    fun postActionableAlert(context: Context, results: List<ScanResultItem>): List<ScanResultItem> {
        val picks = pickActionable(results)
        return postActionableAlertInternal(context, picks, vetoedTickers = emptyList(), gateApplied = false)
    }

    /**
     * Suspend variant that runs every actionable pick through the Gemini gate
     * BEFORE posting the notification. Vetoed tickers are dropped; if the
     * Gemini key is missing the gate fails open and behaviour matches the
     * non-suspend overload above. Use this from a coroutine when you want
     * Gemini to act as the final filter on what reaches the user.
     */
    suspend fun postActionableAlertGated(
        context: Context,
        results: List<ScanResultItem>
    ): List<ScanResultItem> {
        val picks = pickActionable(results)
        if (picks.isEmpty()) return emptyList()
        val gate = if (GeminiGate.isEnabled(context)) {
            GeminiGate.gateAll(context, picks)
        } else emptyMap()
        val approved = picks.filter { gate[it.ticker.uppercase()]?.vetoed != true }
        val vetoed = gate.values.filter { it.vetoed }.map { it.ticker }
        if (vetoed.isNotEmpty()) {
            android.util.Log.i("TrendingAlerts", "Gemini vetoed ${vetoed.size} trending picks: $vetoed")
        }
        return postActionableAlertInternal(
            context, approved,
            vetoedTickers = vetoed,
            gateApplied = gate.isNotEmpty()
        )
    }

    /** HTML-escape a single line so <, >, & from the source text (e.g. an
     *  analyst comment) don't corrupt the Html.fromHtml parse. */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun postActionableAlertInternal(
        context: Context,
        picks: List<ScanResultItem>,
        vetoedTickers: List<String>,
        gateApplied: Boolean
    ): List<ScanResultItem> {
        if (picks.isEmpty()) return emptyList()

        val title = "\ud83d\udd25 Trending Momentum \u2014 ${picks.size} call idea${if (picks.size > 1) "s" else ""}"

        // Build BOTH a plain-text body (for the first-line contentText) and
        // an HTML body (used by BigTextStyle so the ticker renders in bold
        // for at-a-glance readability). The HTML version is also what we
        // persist to NotificationCache — the in-app history view parses it
        // with HtmlCompat.fromHtml so bold styling carries through there too.
        val plain = StringBuilder()
        val html = StringBuilder()
        picks.forEachIndexed { idx, item ->
            if (idx > 0) {
                plain.appendLine()
                html.append("<br/>")
            }
            val change = item.changePercent?.let { " %+.1f%%".format(it) } ?: ""
            val priceStr = "\$${"%.2f".format(item.price)}"
            plain.appendLine("\u25b6 ${item.ticker}  $priceStr$change")
            html.append("\u25b6 <b>").append(escapeHtml(item.ticker)).append("</b>  ")
                .append(escapeHtml(priceStr)).append(escapeHtml(change)).append("<br/>")
            strategyLines(item).forEach { line ->
                plain.appendLine("    $line")
                html.append("&nbsp;&nbsp;&nbsp;&nbsp;").append(escapeHtml(line)).append("<br/>")
            }
            val r = reasoning(item)
            if (r.isNotBlank()) {
                plain.appendLine("    \uD83D\uDCCA $r")
                html.append("&nbsp;&nbsp;&nbsp;&nbsp;\uD83D\uDCCA ").append(escapeHtml(r)).append("<br/>")
            }
        }
        if (gateApplied) {
            plain.append("\n\ud83d\udd0d Gemini gate: ")
            html.append("<br/><b>\ud83d\udd0d Gemini gate:</b> ")
            if (vetoedTickers.isEmpty()) {
                plain.append("all picks approved.")
                html.append("all picks approved.")
            } else {
                plain.append("vetoed ${vetoedTickers.size} \u2014 ${vetoedTickers.joinToString(", ")}")
                val vetoedHtml = vetoedTickers.joinToString(", ") { "<b>${escapeHtml(it)}</b>" }
                html.append("vetoed ${vetoedTickers.size} \u2014 ").append(vetoedHtml)
            }
        }
        val plainBody = plain.toString().trim()
        val htmlBody = html.toString().removeSuffix("<br/>")

        // Persist the HTML variant so the in-app NotificationCard viewer
        // (which runs HtmlCompat.fromHtml on load) renders the ticker in bold.
        NotificationCache.save(context, title, htmlBody)

        // Permission / OS-level guards
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return picks
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return picks

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Momentum-aligned ideas (4-8wk calls + 40Δ CSP funding) from the trending scan"
            }
            nm.createNotificationChannel(ch)
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notifications")
        }
        val pi = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val styledBody = Html.fromHtml(htmlBody, Html.FROM_HTML_MODE_LEGACY)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(plainBody.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(styledBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        return picks
    }
}

// ==========================================
// 3. AI CROSS-VALIDATION UI COMPONENTS
// ==========================================

/** Dialog for entering AI API keys */
@Composable
fun AiApiKeysDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var claudeKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_CLAUDE) ?: "") }
    var geminiKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI) ?: "") }
    var chatgptKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_CHATGPT) ?: "") }
    var perplexityKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_PERPLEXITY) ?: "") }
    var grokKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_GROK) ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("AI Engine API Keys", fontWeight = FontWeight.Bold)
                Text(
                    "Keys are stored encrypted on your device only. Enter keys for any engines you want to use for cross-validation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = claudeKey, onValueChange = { claudeKey = it },
                    label = { Text("Claude (Anthropic)") },
                    placeholder = { Text("sk-ant-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = geminiKey, onValueChange = { geminiKey = it },
                    label = { Text("Gemini (Google)") },
                    placeholder = { Text("AIza...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = chatgptKey, onValueChange = { chatgptKey = it },
                    label = { Text("ChatGPT (OpenAI)") },
                    placeholder = { Text("sk-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = perplexityKey, onValueChange = { perplexityKey = it },
                    label = { Text("Perplexity") },
                    placeholder = { Text("pplx-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = grokKey, onValueChange = { grokKey = it },
                    label = { Text("Grok (xAI)") },
                    placeholder = { Text("xai-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (claudeKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_CLAUDE, claudeKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_CLAUDE)
                if (geminiKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_GEMINI, geminiKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_GEMINI)
                if (chatgptKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_CHATGPT, chatgptKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_CHATGPT)
                if (perplexityKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_PERPLEXITY, perplexityKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_PERPLEXITY)
                if (grokKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_GROK, grokKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_GROK)
                AiCrossValidator.clearCache()
                Toast.makeText(context, "API keys saved (${AiKeyManager.getConfiguredEngines(context).size} engines configured)", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Compact badge showing AI cross-validation consensus on a scan result card */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCrossValidationBadge(validation: AiCrossValidation?) {
    if (validation == null) return

    var expanded by remember { mutableStateOf(false) }

    val consensusColor = when {
        validation.consensus.contains("STRONG BUY", true) -> Color(0xFF1B5E20)
        validation.consensus.contains("BUY", true) -> Color(0xFF2E7D32)
        validation.consensus.contains("HOLD", true) -> Color(0xFFEF6C00)
        validation.consensus.contains("SELL", true) || validation.consensus.contains("AVOID", true) -> Color(0xFFC62828)
        validation.consensus == "MIXED" -> Color(0xFF7C3AED)
        else -> Color.Gray
    }

    // Whether the badge has anything worth expanding to. If every engine
    // either failed or returned empty reasoning, the only extra info is
    // the per-engine verdicts table — still useful, so we always allow
    // expand, but the empty-reasoning case shows a clear placeholder
    // instead of a blank panel.
    val anyReasoning = validation.engines.any { it.error == null && it.reasoning.isNotBlank() }

    Column {
        // Clickable consensus badge
        Card(
            colors = CardDefaults.cardColors(containerColor = consensusColor.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("🤖", style = MaterialTheme.typography.labelSmall)
                Text(
                    "AI: ${validation.consensus}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = consensusColor
                )
                Text(
                    "(${validation.agreementPct}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = consensusColor.copy(alpha = 0.7f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = consensusColor
                )
            }
        }

        // Expanded detail: top reasoning quote + per-engine verdicts.
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("AI Cross-Validation", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    if (validation.summary.isNotBlank()) {
                        Text(validation.summary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }

                    // Highlight the strongest available reasoning at the top
                    // so the user gets the "why" without scanning every
                    // engine row. Picks the engine matching consensus first,
                    // then any engine with non-blank reasoning.
                    val highlight = pickHighlightReasoning(validation)
                    if (highlight != null) {
                        Surface(
                            color = consensusColor.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "💡 Why ${validation.consensus}? — ${highlight.engine}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = consensusColor
                                )
                                Text(
                                    highlight.reasoning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else if (!anyReasoning) {
                        Text(
                            "No reasoning returned by any engine. Verdicts below are based on raw model output only.",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = Color.Gray
                        )
                    }

                    Text(
                        "Per-engine breakdown",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    validation.engines.forEach { engine ->
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(engine.engine, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            if (engine.error != null) {
                                Column {
                                    Text("⚠ Failed", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC62828))
                                }
                            } else {
                                val vColor = when {
                                    engine.verdict.contains("BUY", true) -> Color(0xFF2E7D32)
                                    engine.verdict.contains("HOLD", true) -> Color(0xFFEF6C00)
                                    else -> Color(0xFFC62828)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = vColor.copy(alpha = 0.12f)),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            engine.verdict,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = vColor
                                        )
                                    }
                                    Text(engine.confidence, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                        if (engine.error != null) {
                            Text(
                                "Error: ${engine.error.take(120)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC62828).copy(alpha = 0.8f)
                            )
                        } else if (engine.reasoning.isNotBlank()) {
                            Text(
                                "“${engine.reasoning}”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "(no reasoning provided)",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. MAIN ACTIVITY & UI
// ==========================================
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d("MainActivity", "POST_NOTIFICATIONS granted by user")
        } else {
            Log.w("MainActivity", "POST_NOTIFICATIONS denied — notifications will be silently dropped by the system")
            Toast.makeText(
                this,
                "Notifications are disabled. Enable them in system settings to receive daily picks.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        WorkSchedule.scheduleAll(this)
        // Pre-warm: wake up Render backend so it's ready when user scans
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { apiService.getHealth() } catch (_: Exception) { }
        }
        val startTab = if (intent?.getStringExtra("navigate_to") == "notifications") 3 else 0
        val isSignedIn = GoogleAuthManager.isSignedIn(this)
        // Set user session for API auth header
        if (isSignedIn) {
            UserSession.userId = GoogleAuthManager.getUserId(this)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var signedIn by remember { mutableStateOf(isSignedIn) }
                    if (signedIn) {
                        MainScreen(
                            startTab = startTab,
                            onSignOut = {
                                GoogleAuthManager.signOut(this@MainActivity)
                                signedIn = false
                            }
                        )
                    } else {
                        GoogleSignInScreen(onSignInSuccess = { signedIn = true })
                    }
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // Auto-granted pre-Android 13
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ==========================================
// WORK SCHEDULING + MANUAL-SCAN PRIORITY
// ==========================================
/**
 * All background WorkManager schedules the app registers, kept in one place
 * so [ScanCoordinator] can cancel + re-enqueue them as a unit when the user
 * fires a manual scan.
 *
 * Currently scheduled:
 *  - `DailyRecommendation`          — daily 7:00 AM Pacific (periodic 24h)
 *  - `DailyRecommendation_etf_noon` — daily 12:00 PM device-local (periodic 24h)
 *  - `PortfolioFlipScan`            — hourly (periodic 1h, self-gated to US market hours)
 *
 * NOT scheduled here (fired on-demand):
 *  - `DailyRecommendation_manual`   — Alerts tab "Send Today's Picks Now"
 *  - `PortfolioFlipScan_manual`     — Alerts tab "Test Hourly Scan Now"
 *  - Ad-hoc Retrofit scans          — Scan tab per-ticker / watchlist / trending
 *
 * All periodic workers hit the backend `/scan*` endpoints which are
 * serialised by `_engine_scan_lock` in main.py — if the user launches a
 * manual scan while a scheduled worker is holding the lock, the manual
 * request blocks for the remainder of that scan (up to ~5 min). See
 * [ScanCoordinator] for the cancel-and-re-enqueue mitigation.
 */
object WorkSchedule {
    private const val LOG_TAG = "WorkSchedule"

    fun scheduleAll(context: Context) {
        scheduleDailyRecommendations(context)
        scheduleEtfMidDayAlerts(context)
        // Hourly PortfolioFlipScan is currently DISABLED (2026-07-02).
        // It was firing every hour, holding the backend `_engine_scan_lock`
        // for several minutes per run, and blocking manual single-symbol
        // scans (which then timed out at the client's 120s readTimeout and
        // surfaced as bogus "network connection error" toasts). Cancel any
        // previously-scheduled instance from an older app version so users
        // upgrading get the fix automatically.
        WorkManager.getInstance(context).cancelUniqueWork(PortfolioFlipWorker.TAG)
        Log.d(LOG_TAG, "PortfolioFlipScan hourly work cancelled (feature disabled).")
    }

    fun scheduleDailyRecommendations(context: Context) {
        // Anchor the daily scan to 7:00 AM Pacific Time (10:00 AM Eastern,
        // ~30 minutes after the US equities cash open at 9:30 AM ET / 6:30 AM PT).
        // Using America/Los_Angeles explicitly (instead of the device-local
        // timezone) keeps the notification time stable across daylight-savings
        // transitions and if the user travels.
        val pacific = java.util.TimeZone.getTimeZone("America/Los_Angeles")
        val now = Calendar.getInstance(pacific)
        val target = Calendar.getInstance(pacific).apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val dailyWork = PeriodicWorkRequestBuilder<DailyRecommendationWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            // Exponential backoff: if the dyno is cold / network flakes, retry
            // every 15-30-60 min instead of waiting until tomorrow. Maximum
            // attempts is governed by WorkManager defaults.
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                15, TimeUnit.MINUTES
            )
            .addTag(DailyRecommendationWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyRecommendationWorker.TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyWork
        )

        Log.d(LOG_TAG, "Daily recommendations scheduled. Initial delay: ${initialDelayMs / 1000 / 60} min")
    }

    /**
     * Schedule a focused mid-day ETF scan at 10:00 AM Pacific Time on
     * trading days. Only scans the ETFs in
     * DailyRecommendationWorker.WATCHED_ETFS and posts to a dedicated
     * notification channel so it doesn't get conflated with the morning
     * daily picks.
     *
     * Anchored to America/Los_Angeles (not device-local) so the trigger
     * stays stable across DST transitions and if the user travels. The
     * worker itself skips non-market days via `isMarketDay()`.
     */
    fun scheduleEtfMidDayAlerts(context: Context) {
        val pacific = java.util.TimeZone.getTimeZone("America/Los_Angeles")
        val now = Calendar.getInstance(pacific)
        val target = Calendar.getInstance(pacific).apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If 10:00 AM PT already passed today, schedule for tomorrow
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val noonWork = PeriodicWorkRequestBuilder<DailyRecommendationWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(DailyRecommendationWorker.TAG_NOON_ETF)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyRecommendationWorker.TAG_NOON_ETF,
            ExistingPeriodicWorkPolicy.UPDATE,
            noonWork
        )

        Log.d(LOG_TAG, "ETF mid-day alerts scheduled for 10:00 AM PT. Initial delay: ${initialDelayMs / 1000 / 60} min")
    }

    /**
     * DISABLED (2026-07-02). Retained for easy re-enable but no longer
     * called by [scheduleAll]. See the comment in [scheduleAll] for the
     * lock-contention rationale.
     */
    @Suppress("unused")
    fun schedulePortfolioFlipScan(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val hourlyWork = PeriodicWorkRequestBuilder<PortfolioFlipWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            // Exponential backoff so a Render cold-start or transient
            // network failure doesn't drop the whole hour's run.
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                5, TimeUnit.MINUTES
            )
            .addTag(PortfolioFlipWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PortfolioFlipWorker.TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            hourlyWork
        )

        Log.d(LOG_TAG, "Portfolio flip scan scheduled (hourly during US/Eastern market hours, work id=${hourlyWork.id})")
    }
}

/**
 * Bridges user-initiated scans with the background WorkManager schedule so
 * a manual request always gets first crack at the backend.
 *
 * Background: the backend serialises every scan on `_engine_scan_lock`
 * (main.py ~2325). If a scheduled worker (`DailyRecommendation`,
 * `DailyRecommendation_etf_noon`, `PortfolioFlipScan`) is holding that lock
 * when the user taps "Scan Stocks", the user's Retrofit request stalls
 * behind it for the full remainder of the scheduled scan (up to ~5 min).
 * The client sees a `SocketTimeoutException`/`UnknownHostException` and —
 * before the 2026-07-02 friendly-error tweak — reported it as "No internet
 * connection", which was misleading.
 *
 * Mitigation (client-side):
 *   1. [beginManualScan] cancels every pending / running scheduled worker
 *      that hits the backend, and immediately re-registers the periodic
 *      chain so the next scheduled window still fires as normal. Cancelling
 *      a unique periodic work stops future runs of that chain; a run that
 *      is CURRENTLY executing receives a cancellation signal at its next
 *      cooperative suspension point (the coroutine + OkHttp both honour it).
 *   2. [endManualScan] is a no-op wrapper today — kept as an explicit
 *      lifecycle marker so callers can wrap their logic in a
 *      `try / finally { endManualScan(ctx) }` block and we retain a hook
 *      for future re-enqueue logic without churning every call site.
 *
 * NOT a full fix: even after the client cancels, the BACKEND's active scan
 * still holds `_engine_scan_lock` in its own worker thread until it either
 * completes or its own 5-min timeout expires. A proper fix requires either
 * (a) a `/scan/cancel` endpoint that aborts the current scan or
 * (b) making `_engine_scan_lock` fail-fast for manual requests (a small
 * queue with priority). Both are backend follow-ups.
 *
 * Manual worker tags (`DailyRecommendation_manual`, `PortfolioFlipScan_manual`)
 * are intentionally NOT cancelled here — they represent the user's own
 * explicit request and should always be allowed to finish.
 */
object ScanCoordinator {
    private const val LOG_TAG = "ScanCoordinator"

    // Tags of the SCHEDULED (periodic) workers that share the backend
    // `_engine_scan_lock`. Cancelling any of these frees up the backend
    // for the user's manual request. Manual runs use different tags
    // (`_manual` suffix) and are never cancelled here.
    private val SCHEDULED_TAGS = listOf(
        DailyRecommendationWorker.TAG,           // daily 7 AM Pacific
        DailyRecommendationWorker.TAG_NOON_ETF,  // noon ETF
        PortfolioFlipWorker.TAG                  // hourly flip
    )

    /**
     * Give the user's manual scan first-crack at the backend. Cancels any
     * pending/running scheduled workers, then re-registers the periodic
     * chain so the next scheduled window still fires normally.
     *
     * Call this at the START of every manual scan code path — the Alerts
     * tab manual buttons AND the Scan tab per-ticker/watchlist/trending
     * scans.
     */
    fun beginManualScan(context: Context) {
        val wm = WorkManager.getInstance(context)
        SCHEDULED_TAGS.forEach { tag ->
            try {
                wm.cancelUniqueWork(tag)
                Log.d(LOG_TAG, "Cancelled scheduled work \"$tag\" to prioritise manual scan.")
            } catch (e: Exception) {
                // Never let a cancellation failure derail the manual scan.
                Log.w(LOG_TAG, "cancelUniqueWork(\"$tag\") failed: ${e.message}")
            }
        }
        // Immediately re-register the periodic chain. Because each schedule
        // helper computes its own next-window initial delay, the re-enqueued
        // job won't collide with the manual run — it will simply fire on the
        // next scheduled window (7 AM, noon, or +1 hour) as if it had never
        // been cancelled.
        try {
            WorkSchedule.scheduleAll(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to re-enqueue scheduled workers: ${e.message}")
        }
    }

    /**
     * Marker for the end of a manual scan. Currently a no-op — [beginManualScan]
     * already re-enqueues the periodic chain up-front. Kept as an explicit
     * lifecycle hook so callers can wrap their logic in
     * `try { … } finally { ScanCoordinator.endManualScan(ctx) }` without
     * having to change every call site if we later add cleanup logic (e.g.
     * clearing a "manual in-progress" flag surfaced to a UI badge).
     */
    fun endManualScan(@Suppress("UNUSED_PARAMETER") context: Context) { /* no-op */ }
}

@Composable
fun GoogleSignInScreen(onSignInSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun performSignIn() {
        isLoading = true
        errorMessage = null
        if (!GoogleAuthManager.isWebClientIdConfigured()) {
            errorMessage = "Google Sign-In is not configured. " +
                "Set GoogleAuthManager.WEB_CLIENT_ID to your OAuth 2.0 Web client ID " +
                "from Google Cloud Console."
            isLoading = false
            return
        }
        scope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GoogleAuthManager.buildGoogleIdOption(filterAuthorizedAccounts = false)
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = withContext(Dispatchers.IO) {
                    credentialManager.getCredential(context as android.app.Activity, request)
                }
                if (GoogleAuthManager.handleSignInResult(context, result)) {
                    onSignInSuccess()
                } else {
                    errorMessage = "Sign-in failed: token could not be parsed."
                }
            } catch (e: GetCredentialException) {
                Log.e("GoogleSignIn", "Sign-in failed [${e.type}]: ${e.message}", e)
                errorMessage = friendlyCredentialError(e)
            } catch (e: Exception) {
                Log.e("GoogleSignIn", "Unexpected error: ${e.message}", e)
                errorMessage = e.message ?: "An unexpected error occurred."
            } finally {
                isLoading = false
            }
        }
    }

    // Auto-attempt sign-in with previously authorized accounts on launch
    LaunchedEffect(Unit) {
        if (!GoogleAuthManager.isWebClientIdConfigured()) return@LaunchedEffect
        try {
            val credentialManager = CredentialManager.create(context)
            val googleIdOption = GoogleAuthManager.buildGoogleIdOption(filterAuthorizedAccounts = true)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = withContext(Dispatchers.IO) {
                credentialManager.getCredential(context as android.app.Activity, request)
            }
            if (GoogleAuthManager.handleSignInResult(context, result)) {
                onSignInSuccess()
            }
        } catch (_: Exception) {
            // No previously authorized account — show sign-in button
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                Icons.Default.TipsAndUpdates,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "StockWiz AI",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Smart stock scanning & options analysis powered by AI",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { performSignIn() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Signing in...")
                } else {
                    Text("Sign in with Google", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }

            OutlinedButton(
                onClick = {
                    GoogleAuthManager.signInAsGuest(context)
                    onSignInSuccess()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Skip for now (continue as guest)",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                "You can sign in later from Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        errorMessage!!,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(startTab: Int = 0, onSignOut: () -> Unit = {}) {
    var selectedTab by remember { mutableIntStateOf(startTab) }
    var subScreen by remember { mutableStateOf<String?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // First-launch prompt for AI API keys
    var showAiKeysPrompt by remember { mutableStateOf(false) }
    var showAiKeysDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!AiKeyManager.wasPromptShown(context) && !AiKeyManager.hasAnyKeys(context)) {
            showAiKeysPrompt = true
        }
    }

    // First-launch AI key prompt dialog
    if (showAiKeysPrompt) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = {
                showAiKeysPrompt = false
                AiKeyManager.markPromptShown(context)
            },
            icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color(0xFF7C3AED)) },
            title = { Text("AI Cross-Validation", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "StockWiz can cross-validate Strong Buy recommendations with top AI engines " +
                        "(Claude, Gemini, ChatGPT, Perplexity, Grok) for extra confidence.\n\n" +
                        "You'll need your own API keys — they're stored encrypted on your device only.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF7C3AED).copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("How to get API keys:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                            val links = listOf(
                                "Gemini" to "https://aistudio.google.com/apikey" to "FREE — no credit card needed",
                                "Claude" to "https://console.anthropic.com" to "Settings → API Keys (pay-as-you-go)",
                                "ChatGPT" to "https://platform.openai.com/api-keys" to "Settings → API Keys (pay-as-you-go)",
                                "Perplexity" to "https://www.perplexity.ai/settings/api" to "Settings → API (pay-as-you-go)",
                                "Grok" to "https://console.x.ai" to "FREE credits for new users"
                            )
                            links.forEach { (nameUrl, note) ->
                                val (name, url) = nameUrl
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("• $name: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Get key",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFF1565C0),
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        modifier = Modifier.clickable { uriHandler.openUri(url) }
                                    )
                                }
                                Text("  $note", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            Text(
                                "\uD83D\uDCA1 Tip: Start with Gemini or Grok (both have free tiers). You don't need all 5.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        "Would you like to set up AI validation now?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAiKeysPrompt = false
                    AiKeyManager.markPromptShown(context)
                    showAiKeysDialog = true
                }) { Text("Set Up Keys") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAiKeysPrompt = false
                    AiKeyManager.markPromptShown(context)
                }) { Text("Maybe Later") }
            }
        )
    }

    if (showAiKeysDialog) {
        AiApiKeysDialog(onDismiss = { showAiKeysDialog = false })
    }

    // Keep-alive: ping backend every 5 minutes to prevent Render from sleeping
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            try { withContext(Dispatchers.IO) { apiService.getHealth() } } catch (_: Exception) { }
        }
    }

    // Sub-screen navigation
    if (subScreen != null) {
        when (subScreen) {
            "sector_rotation" -> SectorRotationScreen(onBack = { subScreen = null })
            "ai_learnings" -> AiLearningsScreen(onBack = { subScreen = null })
            "account" -> AccountScreen(
                onBack = { subScreen = null },
                onSignOut = { subScreen = null; onSignOut() }
            )
            "gemini_chat" -> GeminiChatScreen(onBack = { subScreen = null })
        }
        return
    }

    Scaffold(
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) {
            keyboardController?.hide()
            focusManager.clearFocus()
        },
        bottomBar = {
            var showMoreMenu by remember { mutableStateOf(false) }
            NavigationBar(tonalElevation = 4.dp) {
                val scanColor = Color(0xFF4338CA) // Indigo
                val portfolioColor = Color(0xFF059669) // Emerald
                val guruColor = Color(0xFF7C3AED) // Purple
                val alertColor = Color(0xFFD97706) // Amber
                val moreColor = Color(0xFF64748B) // Slate

                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null, tint = scanColor) },
                    label = { Text("Scan", color = scanColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = scanColor,
                        selectedTextColor = scanColor,
                        indicatorColor = Color(0xFFE0E7FF)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AccountBalance, contentDescription = null, tint = portfolioColor) },
                    label = { Text("Portfolio", color = portfolioColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = portfolioColor,
                        selectedTextColor = portfolioColor,
                        indicatorColor = Color(0xFFD1FAE5)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.TipsAndUpdates, contentDescription = null, tint = guruColor) },
                    label = { Text("AI Guru", color = guruColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = guruColor,
                        selectedTextColor = guruColor,
                        indicatorColor = Color(0xFFEDE9FE)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = alertColor) },
                    label = { Text("Alerts", color = alertColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = alertColor,
                        selectedTextColor = alertColor,
                        indicatorColor = Color(0xFFFEF3C7)
                    )
                )
                // ── More ▾ menu (Sectors + Learn) ─────────────────────────
                NavigationBarItem(
                    selected = showMoreMenu,
                    onClick = { showMoreMenu = true },
                    icon = {
                        Box {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = moreColor)
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Default.PieChart, contentDescription = null, tint = Color(0xFF0891B2), modifier = Modifier.size(20.dp))
                                            Text("Sectors")
                                        }
                                    },
                                    onClick = { showMoreMenu = false; subScreen = "sector_rotation" }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFFDB2777), modifier = Modifier.size(20.dp))
                                            Text("Learn")
                                        }
                                    },
                                    onClick = { showMoreMenu = false; subScreen = "ai_learnings" }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Default.Chat, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                                            Text("Ask Gemini")
                                        }
                                    },
                                    onClick = { showMoreMenu = false; subScreen = "gemini_chat" }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color(0xFF4338CA), modifier = Modifier.size(20.dp))
                                            Text("Account")
                                        }
                                    },
                                    onClick = { showMoreMenu = false; subScreen = "account" }
                                )
                            }
                        }
                    },
                    label = { Text("More", color = moreColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = moreColor,
                        selectedTextColor = moreColor,
                        indicatorColor = Color(0xFFF1F5F9)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> ScanScreen()
                1 -> PortfolioScreen()
                2 -> AiGuruScreen()
                3 -> NotificationsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val sharedPrefs = remember { context.getSharedPreferences("FinanceStreamPrefs", Context.MODE_PRIVATE) }

    var isLoading by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResultItem>>(emptyList()) }
    var manualTicker by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf("") }
    // Discriminator: which scan button initiated the in-flight request.
    // Used so the spinner/progress text only renders on the originating button.
    //   null = idle, "single" / "watchlist" / "trending" otherwise.
    var activeScan by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    // ── KEEP SCREEN ON DURING SCAN ────────────────────────────────────
    // A full watchlist scan can take 60-300s on the Render free tier
    // (bulk yfinance download + per-ticker fundamentals). If the screen
    // locks mid-scan Android's Doze mode throttles/kills the socket
    // holding the /scan/status poll, the coroutine sees an IOException,
    // and AppNetwork.hasInternet() can transiently return false during
    // the wake transition — surfacing a bogus "You appear to be offline"
    // message even though the network is fine.
    //
    // Simplest correct fix: hold FLAG_KEEP_SCREEN_ON on the window while
    // the scan is in flight. Costs the user a bit of battery but is
    // scoped only to the ~1-5 minutes the scan is running, and clears
    // as soon as isLoading flips false (success, error, or cancel).
    DisposableEffect(isLoading) {
        val activity = context as? android.app.Activity
        if (isLoading && activity != null) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val strategies = listOf("All", "CSPs", "Put Credit Spreads", "Diagonals", "Verticals", "Long LEAPS")
    var selectedStrategy by remember { mutableStateOf(strategies[0]) }
    var expandedDropdown by remember { mutableStateOf(false) }

    var showTunerDialog by remember { mutableStateOf(false) }
    var showWatchlistDialog by remember { mutableStateOf(false) }
    var showAiKeysDialog by remember { mutableStateOf(false) }

    // Collapsible top-controls panel: gives the results list the full screen
    // when the user is parsing through scan output. Auto-collapses after a
    // scan completes; user re-expands by tapping the compact "Scan" pill.
    var controlsExpanded by remember { mutableStateOf(true) }

    // AI cross-validation state
    val aiValidations = remember { mutableStateMapOf<String, AiCrossValidation>() }
    var aiValidatingTickers by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Auto-trigger AI validation for Strong Buy results
    LaunchedEffect(scanResults) {
        if (scanResults.isEmpty()) return@LaunchedEffect
        // Publish to the shared holder so the Gemini chat screen can use the
        // latest scan as conversation context without re-fetching.
        LastScanContext.results = scanResults
        // hasAnyKeys touches EncryptedSharedPreferences which is expensive
        // on first read (AES-GCM keystore unwrap). Move off the main thread
        // so the scan-results UI renders immediately.
        val keysAvailable = withContext(Dispatchers.IO) { AiKeyManager.hasAnyKeys(context) }
        if (!keysAvailable) return@LaunchedEffect

        // Auto-validate every BUY-rated ticker (STRONG BUY + BUY), capped to
        // [MAX_AUTO_AI_VALIDATIONS] so a 100-symbol watchlist doesn't fire
        // hundreds of LLM calls (5 engines x N tickers). Users can manually
        // trigger AI for any other row via the per-card "Run AI" button.
        val toValidate = scanResults
            .filter { isBuyRated(it.stockRecommendation, it.overall) }
            .take(MAX_AUTO_AI_VALIDATIONS)

        for (item in toValidate) {
            triggerAiValidation(item, scope, context, aiValidations, aiValidatingTickers) { newSet ->
                aiValidatingTickers = newSet
            }
        }
    }

    // Manual per-card AI trigger (reuses the same launch path as auto-validate
    // so behaviour & dedup are identical).
    val onRunAi: (ScanResultItem) -> Unit = { item ->
        triggerAiValidation(item, scope, context, aiValidations, aiValidatingTickers) { newSet ->
            aiValidatingTickers = newSet
        }
    }

    // Mirror the live AI validation map into LastScanContext so the Gemini
    // ask-overlay can include current AI verdicts in its prompt context.
    LaunchedEffect(aiValidations.size) {
        LastScanContext.aiValidations = aiValidations.toMap()
    }

    // Floating Gemini ask-overlay state. When true the bottom half of the
    // results screen is occupied by an embedded GeminiChatPanel; the top
    // half keeps the scan results scrollable so the user can cross-reference.
    var askOverlayOpen by remember { mutableStateOf(false) }
    val chatPanelState = rememberGeminiChatPanelState()

    // Persisted Watchlist State
    var watchlist by remember {
        val saved = sharedPrefs.getString("watchlist", null)
        val list = saved?.split(",")?.filter { it.isNotBlank() } ?: MASTER_WATCHLIST_DEFAULT
        mutableStateOf(list)
    }

    // Watchlist sync: push-then-pull on first load.
    //  - If a previous save failed, watchlist_dirty=true is persisted; on next
    //    launch we replay the PUT before any pull so local edits aren't lost.
    //  - The pull from server only happens once the local copy is clean, so
    //    a stale server list never silently overwrites pending edits.
    LaunchedEffect(Unit) {
        val dirty = sharedPrefs.getBoolean("watchlist_dirty", false)
        if (dirty) {
            try {
                withContext(Dispatchers.IO) {
                    apiService.setWatchlist(WatchlistSetRequest(watchlist))
                }
                sharedPrefs.edit().putBoolean("watchlist_dirty", false).apply()
                Log.d("Watchlist", "Replayed pending watchlist save (${watchlist.size} symbols)")
            } catch (e: Exception) {
                Log.w("Watchlist", "Pending watchlist save still failing: ${e.message}")
                // Skip pull — local copy is the source of truth until we manage to push.
                return@LaunchedEffect
            }
        }
        try {
            val serverWatchlist = withContext(Dispatchers.IO) { apiService.getWatchlist() }
            watchlist = serverWatchlist.tickers
            sharedPrefs.edit().putString("watchlist", serverWatchlist.tickers.joinToString(",")).apply()
        } catch (e: Exception) {
            Log.w("Watchlist", "getWatchlist failed, using local cache: ${e.message}")
        }
    }

    // Tuner Settings State
    var targetDelta by remember { mutableStateOf("-0.25") }
    var minRoc by remember { mutableStateOf("4.0") }

    if (showTunerDialog) {
        AlertDialog(
            onDismissRequest = { showTunerDialog = false },
            title = { Text("Tune Strategy Engine") },
            text = {
                Column {
                    OutlinedTextField(value = targetDelta, onValueChange = { targetDelta = it }, label = { Text("CSP Target Delta") })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = minRoc, onValueChange = { minRoc = it }, label = { Text("Min. Monthly ROC (%)") })
                    Text("Note: Backend API tuner parameters will be passed with each scan request.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top=8.dp))
                }
            },
            confirmButton = {
                Button(onClick = { showTunerDialog = false }) { Text("Apply Locally") }
            }
        )
    }

    if (showWatchlistDialog) {
        var tempWatchlistText by remember { mutableStateOf(watchlist.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { showWatchlistDialog = false },
            title = { Text("Edit Market Watchlist") },
            text = {
                Column {
                    Text("Enter ticker symbols separated by commas or spaces.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempWatchlistText,
                        onValueChange = { tempWatchlistText = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("AAPL, MSFT, TSLA...") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newList = tempWatchlistText.split(Regex("[,\\s]+"))
                        .filter { it.isNotBlank() }
                        .map { it.trim() }
                    if (newList.isNotEmpty()) {
                        watchlist = newList
                        // Mark dirty BEFORE the network call so a process death
                        // mid-flight is recoverable on the next app launch.
                        sharedPrefs.edit()
                            .putString("watchlist", newList.joinToString(","))
                            .putBoolean("watchlist_dirty", true)
                            .apply()
                        showWatchlistDialog = false
                        Toast.makeText(context, "Watchlist updated (${newList.size} symbols) — syncing…", Toast.LENGTH_SHORT).show()
                        // Sync to server with up to 3 attempts + exponential
                        // backoff. The first attempt covers the common case;
                        // the retries cover Render cold-start (~10-30s) and
                        // transient Wi-Fi flakiness. If all three fail the
                        // dirty flag stays true and MainActivity's startup
                        // LaunchedEffect replays the PUT on next foreground.
                        scope.launch {
                            var pushed = false
                            var lastError: Exception? = null
                            for (attempt in 1..3) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        apiService.setWatchlist(WatchlistSetRequest(newList))
                                    }
                                    pushed = true
                                    break
                                } catch (e: Exception) {
                                    lastError = e
                                    Log.w("Watchlist", "Server sync attempt $attempt/3 failed: ${e.message}")
                                    if (attempt < 3) kotlinx.coroutines.delay(1500L * attempt)
                                }
                            }
                            if (pushed) {
                                sharedPrefs.edit().putBoolean("watchlist_dirty", false).apply()
                                Log.d("Watchlist", "Synced ${newList.size} symbols to server")
                            } else {
                                Log.e("Watchlist", "Server sync failed after 3 attempts: ${lastError?.message}")
                                Toast.makeText(
                                    context,
                                    "Saved locally; server sync failed (\"${friendlyErrorMessage(lastError ?: Exception("unknown"), context)}\"). Will retry on next launch.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }) { Text("Save Watchlist") }
            },
            dismissButton = {
                TextButton(onClick = { showWatchlistDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (!controlsExpanded) {
            // Compact bar: shown after a scan completes so the results take the
            // full screen. Tap "Scan" to bring the full controls back.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { controlsExpanded = true },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = "Expand controls", modifier = Modifier.size(16.dp))
                }
                if (scanResults.isNotEmpty()) {
                    Text(
                        "${scanResults.size} results",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
        }

        if (controlsExpanded) {
        // Strategy Filter & Tuner
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = { expandedDropdown = !expandedDropdown },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = selectedStrategy,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Strategy") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                ExposedDropdownMenu(expanded = expandedDropdown, onDismissRequest = { expandedDropdown = false }) {
                    strategies.forEach { selectionOption ->
                        DropdownMenuItem(text = { Text(selectionOption) }, onClick = {
                            selectedStrategy = selectionOption
                            expandedDropdown = false
                        })
                    }
                }
            }
            IconButton(onClick = { showWatchlistDialog = true }) {
                Icon(Icons.Default.EditNote, contentDescription = "Edit Watchlist")
            }
            IconButton(onClick = { showAiKeysDialog = true }) {
                val hasKeys = AiKeyManager.hasAnyKeys(context)
                Icon(Icons.Default.Psychology, contentDescription = "AI Keys", tint = if (hasKeys) Color(0xFF7C3AED) else Color.Gray)
            }
            IconButton(onClick = { showTunerDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Tune Strategy")
            }
        }

        // AI Keys Dialog
        if (showAiKeysDialog) {
            AiApiKeysDialog(onDismiss = { showAiKeysDialog = false })
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Manual Search Bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = manualTicker,
                onValueChange = { manualTicker = it.uppercase() },
                label = { Text("Ticker") },
                placeholder = { Text("e.g. TSLA, AMD") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scan Action Button
        Button(
            onClick = {
                keyboardController?.hide()
                if (manualTicker.isBlank()) return@Button
                scope.launch {
                    // Manual scan priority: cancel any currently-running
                    // scheduled scan workers (daily / noon ETF / hourly flip)
                    // so this request gets an unobstructed path to the
                    // backend `_engine_scan_lock`. Periodic chain is
                    // re-registered immediately so the next scheduled
                    // window still fires normally.
                    ScanCoordinator.beginManualScan(context)
                    try {
                        isLoading = true
                        activeScan = "single"
                        scanResults = emptyList()
                        scanError = null
                        scanProgress = "Scanning ${manualTicker}..."

                        val strategyParam = when (selectedStrategy) {
                            "CSPs" -> "csp"
                            "Diagonals" -> "diagonal"
                            "Verticals" -> "vertical"
                            "Long LEAPS" -> "long_leaps"
                            "Put Credit Spreads" -> "pcs"
                            else -> null
                        }
                        val deltaParam = targetDelta.toDoubleOrNull()
                        val rocParam = minRoc.toDoubleOrNull()

                        // 2026-07-04: switched single-ticker path from sync
                        // `/scan` to async `/scan/async` + poll for the same
                        // reason the watchlist path did: sync has a hard
                        // 120s client-side readTimeout, and when the
                        // backend's `_engine_scan_lock` is currently held
                        // by a scheduled worker (daily 7am / ETF 10am /
                        // portfolio-flip) the sync request sits in the
                        // lock queue and hits SocketTimeout WITHOUT ever
                        // reaching process_ticker. Async + poll gives the
                        // user "Scanning 0/1…" progress and, if the lock
                        // never frees, a specific
                        // "The scan stalled at 0/1 symbols — backend may
                        // be running a scheduled scan" message instead of
                        // the misleading "scan service didn't respond".
                        // Sync fallback preserved (via runAsyncWatchlistScan's
                        // caller pattern below) in case /scan/async itself
                        // is 404 on an older backend.
                        var asyncMadeProgress = false
                        val results = try {
                            runAsyncWatchlistScan(
                                apiService = apiService,
                                tickers = manualTicker,
                                strategy = strategyParam,
                                scanListType = scanListType,
                                gson = gson,
                                // User-initiated single-ticker scan.
                                // Priority=high preempts any in-flight
                                // scheduled (normal-priority) scan on the
                                // backend so the user isn't blocked behind
                                // a 46-ticker refresh holding the engine
                                // lock.
                                priority = "high",
                                onProgress = { done, jobTotal, phase ->
                                    if (done > 0) asyncMadeProgress = true
                                    scanProgress = when {
                                        done < 0 -> phase
                                        phase == "Done" -> "Scanning ${manualTicker}..."
                                        else -> "Scanning ${manualTicker}..."
                                    }
                                },
                            )
                        } catch (asyncErr: Exception) {
                            Log.w(
                                "API_ERROR",
                                "Single async scan failed, evaluating fallback: " +
                                    "${asyncErr.javaClass.simpleName}: ${asyncErr.message}",
                            )
                            // If async made progress OR stalled with a
                            // specific ScanStalledException OR the backend
                            // restarted mid-scan (ScanJobLostException),
                            // don't retry via sync — same backend, same
                            // lock, same wait.
                            if (asyncMadeProgress || asyncErr is ScanStalledException || asyncErr is ScanJobLostException) {
                                throw asyncErr
                            }
                            // Only fall through to sync when /scan/async
                            // never produced any progress (older backend
                            // that returns 404 for /scan/async).
                            apiService.getScanResults(
                                tickers = manualTicker,
                                strategy = strategyParam,
                                targetDelta = deltaParam,
                                minRoc = rocParam,
                            )
                        }
                        scanResults = results
                        if (results.isEmpty()) {
                            scanError = "No opportunities found for this ticker."
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "API_ERROR",
                            "Scan failed: ${e.javaClass.simpleName}: ${e.message}",
                            e,
                        )
                        scanError = friendlyErrorMessage(e, context)
                    } finally {
                        isLoading = false
                        scanProgress = ""
                        activeScan = null
                        if (scanResults.isNotEmpty()) controlsExpanded = false
                        ScanCoordinator.endManualScan(context)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (activeScan == "single") {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(scanProgress, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Stocks", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Scan Watchlist Button — sends the full watchlist as a single
        // async scan job. Previously we split into ~6 parallel chunks
        // hoping for Nx throughput, but the backend serializes every scan
        // on `_engine_scan_lock` (main.py) so N parallel jobs = N wall-clock
        // times, PLUS each chunk pays the full prefetch_market_data cost
        // (bulk yf.download + fundamentals). Consolidating into one job
        // lets the backend do a single bulk prefetch for the whole
        // watchlist, and cuts wall-clock by ~4-6x on a 30-ticker list.
        Button(
            onClick = {
                keyboardController?.hide()
                scope.launch {
                    // Manual scan priority (see beginManualScan doc).
                    ScanCoordinator.beginManualScan(context)
                    try {
                        isLoading = true
                        activeScan = "watchlist"
                        scanResults = emptyList()
                        scanError = null
                        scanProgress = "Starting watchlist scan..."

                        val strategyParam = when (selectedStrategy) {
                            "CSPs" -> "csp"
                            "Diagonals" -> "diagonal"
                            "Verticals" -> "vertical"
                            "Long LEAPS" -> "long_leaps"
                            "Put Credit Spreads" -> "pcs"
                            else -> null
                        }

                        val total = watchlist.size
                        val tickersCsv = watchlist.joinToString(",")
                        scanProgress = "Scanning 0/$total symbols..."

                        // Kick off a single background scan job. If /scan/async
                        // is unavailable (older backend) we fall back to a
                        // blocking /scan call in the catch block below.
                        //
                        // BUT: only fall back when the async job never made
                        // progress. If we already saw N/M tickers scanned and
                        // then the backend stalled or dropped, the sync
                        // fallback will land on the same jammed backend and
                        // burn another 2+ minutes before its own SocketTimeout.
                        // In that case we surface the async-side error directly
                        // so the user can retry (once `_engine_scan_lock` frees).
                        var scanFinished = false
                        var asyncMadeProgress = false
                        try {
                            // Extracted for testability — see runAsyncWatchlistScan
                            // in AsyncScanPoller.kt (unit-tested by
                            // AsyncScanPollerTest with MockWebServer,
                            // including mid-scan transient-IOException recovery).
                            val results = runAsyncWatchlistScan(
                                apiService = apiService,
                                tickers = tickersCsv,
                                strategy = strategyParam,
                                scanListType = scanListType,
                                gson = gson,
                                // User-initiated watchlist scan.
                                // Priority=high preempts any in-flight
                                // scheduled (normal-priority) scan on the
                                // backend so the user isn't blocked behind
                                // a 46-ticker refresh holding the engine
                                // lock.
                                priority = "high",
                                onProgress = { done, jobTotal, phase ->
                                    if (done > 0) asyncMadeProgress = true
                                    scanProgress = when {
                                        done < 0 -> phase
                                        phase == "Done" -> "Scanning $jobTotal/$total symbols..."
                                        else -> "Scanning $done/$total symbols..."
                                    }
                                }
                            )
                            scanResults = results
                            scanFinished = true
                        } catch (asyncErr: Exception) {
                            Log.w("API_ERROR", "Async scan failed, falling back to sync: ${asyncErr.message}")
                            // If async progressed and then stalled/failed, don't
                            // retry with a sync call — same backend, same jam.
                            // Rethrow so the outer catch surfaces the specific
                            // "stalled at N/M" / "backend restarted" / transient-
                            // error message.
                            if (asyncMadeProgress || asyncErr is ScanStalledException || asyncErr is ScanJobLostException) {
                                throw asyncErr
                            }
                            // Sync fallback preserves behaviour on older backends
                            // or when the poll endpoint is temporarily unreachable
                            // BEFORE the job ever started producing progress.
                            val results = withContext(Dispatchers.IO) {
                                apiService.getScanResults(
                                    tickers = tickersCsv,
                                    strategy = strategyParam
                                )
                            }
                            scanResults = results
                            scanFinished = true
                        }

                        if (scanFinished && scanResults.isEmpty()) {
                            scanError = "No opportunities found. Try adjusting tuner parameters or your watchlist."
                        }
                    } catch (e: Exception) {
                        // Log full stack + exception class so logcat filtered by
                        // API_ERROR contains everything needed to root-cause a
                        // failed watchlist scan without asking the user for
                        // additional info. Passing `e` as the third arg to
                        // Log.e includes the stacktrace.
                        Log.e(
                            "API_ERROR",
                            "Watchlist scan failed: ${e.javaClass.simpleName}: ${e.message}",
                            e,
                        )
                        scanError = friendlyErrorMessage(e, context)
                    } finally {
                        isLoading = false
                        scanProgress = ""
                        activeScan = null
                        if (scanResults.isNotEmpty()) controlsExpanded = false
                        ScanCoordinator.endManualScan(context)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
        ) {
            if (activeScan == "watchlist") {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(scanProgress, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Watchlist — ${watchlist.size}", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Scan Trending Button
        Button(
            onClick = {
                keyboardController?.hide()
                scope.launch {
                    // Manual scan priority (see beginManualScan doc).
                    ScanCoordinator.beginManualScan(context)
                    try {
                        isLoading = true
                        activeScan = "trending"
                        scanResults = emptyList()
                        scanError = null
                        scanProgress = "Fetching trending stocks..."
                        val results = apiService.scanTrending()
                        // Filter to actionable picks, then run them through Gemini
                        // (if a key is configured) so vetoed names are dropped
                        // before reaching the user's notification shade.
                        val actionable = TrendingAlerts.postActionableAlertGated(context, results)
                        // Show actionable picks first; user can still see the rest below.
                        scanResults = if (actionable.isEmpty()) results
                                      else actionable + results.filter { r -> actionable.none { it.ticker == r.ticker } }
                        when {
                            results.isEmpty() -> scanError = "No trending stocks found."
                            actionable.isEmpty() -> scanError = "No actionable picks in today's trending list — showing all ${results.size} for reference."
                            else -> Toast.makeText(
                                context,
                                "${actionable.size} actionable trending pick${if (actionable.size > 1) "s" else ""} \u2014 see notification",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e("API_ERROR", "Trending scan failed: ${e.message}")
                        scanError = friendlyErrorMessage(e, context)
                    } finally {
                        isLoading = false
                        scanProgress = ""
                        activeScan = null
                        if (scanResults.isNotEmpty()) controlsExpanded = false
                        ScanCoordinator.endManualScan(context)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
        ) {
            if (activeScan == "trending") {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(scanProgress, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Trending Stocks", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Hint for editing watchlist
        if (manualTicker.isBlank()) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tap ",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                Text(
                    " to edit watchlist symbols",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
        } // end if (controlsExpanded)

        Spacer(modifier = Modifier.height(6.dp))

        // Error / Status message
        if (scanError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (scanResults.isNotEmpty()) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (scanResults.isNotEmpty()) Icons.Default.Warning else Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = scanError!!,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { scanError = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Results List
        if (scanResults.isNotEmpty()) {
            // Recommendation filter chips. Buckets are derived from the
            // raw stockRecommendation/overall strings via [recommendationBucket]
            // so the chips work even when the backend returns variants like
            // "STRONG BUY (Confirmed)" or "BUY — Trending".
            var recFilter by remember(scanResults) { mutableStateOf("All") }
            val bucketCounts = remember(scanResults) {
                scanResults.groupingBy {
                    recommendationBucket(it.stockRecommendation, it.overall)
                }.eachCount()
            }
            val filterOrder = listOf("All", "STRONG BUY", "BUY", "HOLD", "SELL", "AVOID")
            val visibleFilters = filterOrder.filter { f -> f == "All" || (bucketCounts[f] ?: 0) > 0 }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                visibleFilters.forEach { f ->
                    val count = if (f == "All") scanResults.size else (bucketCounts[f] ?: 0)
                    val selected = recFilter == f
                    val chipColor = recommendationChipColor(f)
                    FilterChip(
                        selected = selected,
                        onClick = { recFilter = f },
                        label = { Text("$f ($count)", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipColor.copy(alpha = 0.18f),
                            selectedLabelColor = chipColor
                        )
                    )
                }
            }

            // Sort the overall list of tickers based on the selected strategy's best metric
            val sortedResults = remember(scanResults, selectedStrategy) {
                when (selectedStrategy) {
                    "CSPs" -> scanResults.sortedByDescending { item ->
                        item.csps?.maxOfOrNull { it.roc.parseToDouble() } ?: -1.0
                    }
                    "Put Credit Spreads" -> scanResults.sortedByDescending { item ->
                        item.putCreditSpreads?.maxOfOrNull { it.roc.parseToDouble() } ?: -1.0
                    }
                    "Diagonals" -> scanResults.sortedByDescending { item ->
                        item.diagonals?.maxOfOrNull { it.yieldRatio.parseToDouble() } ?: -1.0
                    }
                    else -> scanResults
                }
            }
            val displayedResults = remember(sortedResults, recFilter) {
                if (recFilter == "All") sortedResults
                else sortedResults.filter {
                    recommendationBucket(it.stockRecommendation, it.overall) == recFilter
                }
            }
            // Mirror the active filter to the shared context so the Gemini
            // ask-overlay can mention "you're looking at the BUY filter".
            LaunchedEffect(recFilter) { LastScanContext.activeFilter = recFilter }

            if (displayedResults.isEmpty()) {
                Text(
                    "No ${recFilter} results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(8.dp)
                )
            }

            // Wrap results + chat overlay in a Box so the floating Gemini
            // FAB can sit on top of the results list. When the overlay is
            // open the chat takes the larger share of the screen (60%) so
            // multi-line Gemini answers are readable; the results list keeps
            // ~40% and stays scrollable.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.weight(if (askOverlayOpen) 0.4f else 1f)) {
                        items(displayedResults) { item ->
                            ScanResultCard(
                                item, selectedStrategy, scope, context,
                                aiValidation = aiValidations[item.ticker],
                                isAiValidating = aiValidatingTickers.contains(item.ticker),
                                onRunAi = { onRunAi(item) }
                            )
                        }
                    }
                    if (askOverlayOpen) {
                        // Drag handle / header for the chat panel. Doubles
                        // as the context-share toggle so the embedded panel
                        // doesn't waste a second row on a banner.
                        Surface(
                            color = Color(0xFF2563EB),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🤖", style = MaterialTheme.typography.labelLarge)
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Ask Gemini",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    if (LastScanContext.results.isNotEmpty()) {
                                        Text(
                                            if (chatPanelState.includeScanContext)
                                                "Sharing ${LastScanContext.results.size.coerceAtMost(20)} results"
                                            else "Context off",
                                            color = Color.White.copy(alpha = 0.85f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (LastScanContext.results.isNotEmpty()) {
                                    Switch(
                                        checked = chatPanelState.includeScanContext,
                                        onCheckedChange = { chatPanelState.includeScanContext = it },
                                        modifier = Modifier.scale(0.75f)
                                    )
                                }
                                if (chatPanelState.messages.isNotEmpty()) {
                                    TextButton(
                                        onClick = { chatPanelState.clear() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                    ) {
                                        Text("Clear", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(
                                    onClick = { askOverlayOpen = false },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        GeminiChatPanel(
                            state = chatPanelState,
                            modifier = Modifier.weight(0.6f).fillMaxWidth(),
                            compact = true
                        )
                    }
                }
                // Floating Gemini button (only when chat is closed).
                if (!askOverlayOpen) {
                    FloatingActionButton(
                        onClick = { askOverlayOpen = true },
                        containerColor = Color(0xFF2563EB),
                        contentColor = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = "Ask Gemini")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanResultCard(
    item: ScanResultItem,
    strategyFilter: String,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    aiValidation: AiCrossValidation? = null,
    isAiValidating: Boolean = false,
    onRunAi: (() -> Unit)? = null
) {
    val hasStrategies = !item.csps.isNullOrEmpty() || !item.diagonals.isNullOrEmpty() ||
            !item.verticals.isNullOrEmpty() || !item.longLeaps.isNullOrEmpty() ||
            !item.putCreditSpreads.isNullOrEmpty()

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Ticker + Name + Price + % Change
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    if (item.name != null) {
                        Text(text = item.name, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.changePercent != null) {
                        val pctColor = if (item.changePercent >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                        Card(colors = CardDefaults.cardColors(containerColor = pctColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = "${if (item.changePercent >= 0) "+" else ""}${"%.2f".format(item.changePercent)}%",
                                fontWeight = FontWeight.Bold,
                                color = pctColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = "$${item.price}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Key metrics (always visible): RSI, Beta, IV — colored per
            // [metricColor]/[metricLabel] using options-seller semantics:
            // green = favourable, red = unfavourable, blue = caution. The
            // help icon opens a legend dialog explaining each band.
            var showLegend by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (item.rsi != null) {
                    val (rsiColor, rsiHint) = metricColor(MetricKind.RSI, item.rsi)
                    Card(colors = CardDefaults.cardColors(containerColor = rsiColor.copy(alpha = 0.14f)), shape = RoundedCornerShape(6.dp)) {
                        Text("RSI ${"%.0f".format(item.rsi)} · $rsiHint", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = rsiColor)
                    }
                }
                if (item.beta != null) {
                    val (betaColor, betaHint) = metricColor(MetricKind.BETA, item.beta)
                    Card(colors = CardDefaults.cardColors(containerColor = betaColor.copy(alpha = 0.14f)), shape = RoundedCornerShape(6.dp)) {
                        Text("β ${"%.2f".format(item.beta)} · $betaHint", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = betaColor)
                    }
                }
                if (item.ivRank != null) {
                    val ivNum = item.ivRank.replace("%", "").trim().toDoubleOrNull()
                    if (ivNum != null) {
                        val (ivColor, ivHint) = metricColor(MetricKind.IV, ivNum)
                        Card(colors = CardDefaults.cardColors(containerColor = ivColor.copy(alpha = 0.14f)), shape = RoundedCornerShape(6.dp)) {
                            Text("IV ${item.ivRank} · $ivHint", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ivColor)
                        }
                    } else {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                            Text("IV ${item.ivRank}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
                // Help icon — explains the colored chips
                IconButton(onClick = { showLegend = true }, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Color legend", modifier = Modifier.size(16.dp), tint = Color(0xFF64748B))
                }
                // Earnings date chip
                if (item.nextEarningsDate != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD97706).copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                        Text("📅 Earnings ${item.nextEarningsDate}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD97706))
                    }
                }
                // Analyst target price chip (consensus shown separately in recommendation badge below)
                if (item.analystTarget?.mean != null) {
                    val at = item.analystTarget
                    val targetColor = when {
                        (at.upsidePct ?: 0.0) >= 15.0 -> Color(0xFF2E7D32)
                        (at.upsidePct ?: 0.0) >= 0.0  -> Color(0xFF1565C0)
                        else                           -> Color(0xFFC62828)
                    }
                    val parts = buildList {
                        add("Analyst Target: $${"%.0f".format(at.mean)}")
                        if (at.upsidePct != null) add("${"%+.0f".format(at.upsidePct)}% from today")
                        if (at.numAnalysts != null) add("${at.numAnalysts} analysts")
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = targetColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            parts.joinToString(" · "),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = targetColor
                        )
                    }
                }
            }

            // Stock Recommendation Badge + Summary
            if (item.stockRecommendation != null || item.overall != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.stockRecommendation != null) {
                        val recColor = when {
                            item.stockRecommendation.contains("STRONG BUY", true) -> Color(0xFF1B5E20)
                            item.stockRecommendation.contains("BUY", true) -> Color(0xFF2E7D32)
                            item.stockRecommendation.contains("SELL", true) -> Color(0xFFC62828)
                            item.stockRecommendation.contains("HOLD", true) -> Color(0xFFEF6C00)
                            else -> Color.Gray
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = recColor.copy(alpha = 0.15f))) {
                            Text(
                                item.stockRecommendation,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = recColor
                            )
                        }
                    }
                    if (item.overall != null) {
                        val overallColor = when {
                            item.overall.contains("STRONG", true) -> Color(0xFF1565C0)
                            item.overall.contains("OPPORTUNITY", true) -> Color(0xFF2E7D32)
                            item.overall.contains("CAUTION", true) -> Color(0xFFEF6C00)
                            else -> Color.Gray
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = overallColor.copy(alpha = 0.12f))) {
                            Text(
                                item.overall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = overallColor
                            )
                        }
                    }
                }
            }
            // Summary (always visible)
            if (item.stockSummary != null) {
                Text(
                    item.stockSummary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // AI Cross-Validation Badge (for Strong Buy results)
            if (aiValidation != null) {
                Spacer(modifier = Modifier.height(6.dp))
                AiCrossValidationBadge(validation = aiValidation)
            } else if (isAiValidating) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF7C3AED))
                    Text("AI cross-validating...", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
                }
            } else if (onRunAi != null) {
                // Manual "Run AI" trigger for rows that weren't auto-validated
                // (e.g. HOLD/SELL rows or anything past the auto-validate cap).
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = onRunAi,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("🤖", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run AI cross-validation", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
                }
            }

            // Legend dialog explaining the colored RSI/β/IV chips above.
            if (showLegend) {
                MetricLegendDialog(onDismiss = { showLegend = false })
            }

            // Expandable details section
            val hasDetails = item.sma200 != null || item.discountFromHigh != null ||
                    !item.bullishSignals.isNullOrEmpty() || !item.bearishSignals.isNullOrEmpty() ||
                    item.levels != null
            if (hasDetails) {
                var detailsExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded }.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (detailsExpanded) "Hide details" else "More details",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (detailsExpanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    // SMA200 & Off High
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.sma200 != null) {
                            val aboveSma = item.price > item.sma200
                            val smaColor = if (aboveSma) Color(0xFF2E7D32) else Color(0xFFC62828)
                            Card(colors = CardDefaults.cardColors(containerColor = smaColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "SMA200 $${"%.2f".format(item.sma200)} ${if (aboveSma) "▲" else "▼"}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = smaColor
                                )
                            }
                        }
                        if (item.discountFromHigh != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "Off 52W High: ${item.discountFromHigh}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    // SMA & Momentum chips (single line)
                    val smaSignals = mutableListOf<Pair<String, Boolean>>() // label, isBullish
                    val momentumSignals = mutableListOf<Pair<String, Boolean>>()
                    val otherBullish = mutableListOf<String>()
                    val otherBearish = mutableListOf<String>()
                    item.bullishSignals?.forEach { signal ->
                        val s = signal.trim()
                        when {
                            s.contains("SMA200", true) || s.contains("200-day", true) || s.contains("200 SMA", true) -> smaSignals.add("↑SMA200" to true)
                            s.contains("SMA50", true) || s.contains("50-day", true) || s.contains("50 SMA", true) -> smaSignals.add("↑SMA50" to true)
                            s.contains("momentum", true) || s.contains("uptrend", true) -> momentumSignals.add("Momentum — Uptrend" to true)
                            else -> otherBullish.add(abbreviateSignal(signal))
                        }
                    }
                    item.bearishSignals?.forEach { signal ->
                        val s = signal.trim()
                        when {
                            s.contains("SMA200", true) || s.contains("200-day", true) || s.contains("200 SMA", true) -> smaSignals.add("↓SMA200" to false)
                            s.contains("SMA50", true) || s.contains("50-day", true) || s.contains("50 SMA", true) -> smaSignals.add("↓SMA50" to false)
                            s.contains("momentum", true) || s.contains("downtrend", true) -> momentumSignals.add("Momentum — Downtrend" to false)
                            else -> otherBearish.add(abbreviateSignal(signal))
                        }
                    }
                    if (smaSignals.isNotEmpty() || momentumSignals.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            smaSignals.distinctBy { it.first }.forEach { (label, bullish) ->
                                val c = if (bullish) Color(0xFF2E7D32) else Color(0xFFC62828)
                                Card(colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                    Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c)
                                }
                            }
                            momentumSignals.distinctBy { it.first }.forEach { (label, bullish) ->
                                val c = if (bullish) Color(0xFF2E7D32) else Color(0xFFC62828)
                                Card(colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                    Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c)
                                }
                            }
                        }
                    }
                    // Other bullish / bearish signals
                    if (otherBullish.isNotEmpty() || otherBearish.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            otherBullish.forEach { signal ->
                                Text("▲ $signal", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                            }
                            otherBearish.forEach { signal ->
                                Text("▼ $signal", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                            }
                        }
                    }
                    // Reassure the user that the bear/bull pane isn't broken
                    // when a stock is strongly one-sided. INTC on 2026-06-28
                    // legitimately produced zero bearish signals (RSI 57,
                    // price > SMA200 > SMA50, 99% backtest win rate); without
                    // this placeholder the section silently disappears and
                    // looks like a regression.
                    val hasAnyBullSignal = otherBullish.isNotEmpty() ||
                        smaSignals.any { it.second } ||
                        momentumSignals.any { it.second }
                    val hasAnyBearSignal = otherBearish.isNotEmpty() ||
                        smaSignals.any { !it.second } ||
                        momentumSignals.any { !it.second }
                    if (hasAnyBullSignal && !hasAnyBearSignal) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "▼ no bearish signals detected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC62828).copy(alpha = 0.7f)
                        )
                    } else if (hasAnyBearSignal && !hasAnyBullSignal) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "▲ no bullish signals detected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32).copy(alpha = 0.7f)
                        )
                    }

                    // Key Levels (deduplicated)
                    if (item.levels != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Key Levels", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(6.dp))

                                // Collect all levels into an ordered list (high → low)
                                val stopVal = item.levels.stopLoss
                                val supportVal = item.levels.support
                                val swingLowVal = item.levels.swingLow60d
                                val targetVal = item.levels.target
                                val resistVal = item.levels.resistance
                                val swingHighVal = item.levels.swingHigh60d
                                val high52w = item.levels.high52w
                                val currentPrice = item.price

                                data class LevelRow(val label: String, val value: Double, val color: Color, val bold: Boolean = false, val emoji: String = "")
                                val levels = mutableListOf<LevelRow>()
                                if (high52w != null) levels.add(LevelRow("52-Week High", high52w, Color.Gray))
                                if (targetVal != null) levels.add(LevelRow("Target", targetVal, Color(0xFF2E7D32), bold = true, emoji = "🎯"))
                                if (resistVal != null && "%.2f".format(resistVal) != "%.2f".format(targetVal ?: -1.0)) levels.add(LevelRow("Resistance", resistVal, Color(0xFF1565C0)))
                                if (swingHighVal != null && "%.2f".format(swingHighVal) != "%.2f".format(targetVal ?: -1.0) && "%.2f".format(swingHighVal) != "%.2f".format(resistVal ?: -1.0)) levels.add(LevelRow("60d Swing High", swingHighVal, Color.Gray))
                                levels.add(LevelRow("Current Price", currentPrice, MaterialTheme.colorScheme.primary, bold = true, emoji = "📍"))
                                if (supportVal != null && "%.2f".format(supportVal) != "%.2f".format(stopVal ?: -1.0)) levels.add(LevelRow("Support", supportVal, Color(0xFFEF6C00)))
                                if (swingLowVal != null && "%.2f".format(swingLowVal) != "%.2f".format(stopVal ?: -1.0) && "%.2f".format(swingLowVal) != "%.2f".format(supportVal ?: -1.0)) levels.add(LevelRow("60d Swing Low", swingLowVal, Color.Gray))
                                if (stopVal != null) {
                                    // For AVOID / SELL recommendations the
                                    // backend returns a SHORT-COVER stop
                                    // that legitimately sits ABOVE current
                                    // price (exit the short if stock
                                    // rallies through the stop). Labeling
                                    // it "Stop Loss" implies a long-position
                                    // stop and is confusing when it appears
                                    // above price — relabel for clarity.
                                    val isShort = isStockAvoidOrSell(item.stockRecommendation, item.overall)
                                    val stopLabel = if (isShort) "Short-cover Stop" else "Stop Loss"
                                    levels.add(LevelRow(stopLabel, stopVal, Color(0xFFC62828), bold = true, emoji = "🛑"))
                                }

                                // Sort descending by value
                                val sortedLevels = levels.sortedByDescending { it.value }
                                sortedLevels.forEach { level ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${level.emoji}${if (level.emoji.isNotEmpty()) " " else ""}${level.label}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (level.bold) FontWeight.Bold else FontWeight.Normal,
                                            color = level.color
                                        )
                                        Text(
                                            "$${"%.2f".format(level.value)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (level.bold) FontWeight.Bold else FontWeight.Normal,
                                            color = level.color
                                        )
                                    }
                                }
                                // Risk/Reward, Daily Move, 52W High chips
                                val hasChips = item.levels.riskReward != null || item.levels.atr != null || item.levels.high52w != null
                                if (hasChips) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (item.levels.riskReward != null) {
                                            val rr = item.levels.riskReward
                                            val rrColor = if (rr >= 2.0) Color(0xFF2E7D32) else if (rr >= 1.0) Color(0xFFEF6C00) else Color(0xFFC62828)
                                            // rr is reward÷risk: e.g. 2.0 = $2 reward per $1 risk
                                            val rating = when {
                                                rr >= 3.0 -> "Excellent"
                                                rr >= 2.0 -> "Good"
                                                rr >= 1.0 -> "Fair"
                                                else -> "Poor — reward < risk"
                                            }
                                            val rrText = "Reward:Risk ${"%.1f".format(rr)}:1 ($rating)"
                                            Card(colors = CardDefaults.cardColors(containerColor = rrColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                                Text(rrText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = rrColor)
                                            }
                                        }
                                        if (item.levels.atr != null) {
                                            val atrPct = if (item.price > 0) (item.levels.atr / item.price) * 100 else 0.0
                                            Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                                                Text("Avg Daily Move: $${"%.2f".format(item.levels.atr)} (${"%.1f".format(atrPct)}%)", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        if (item.levels.high52w != null) {
                                            Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                                                Text("52W Hi $${"%.2f".format(item.levels.high52w)}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                                // Risk note
                                if (item.levels.riskNote != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(item.levels.riskNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (!hasStrategies) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "No strategy matches found — showing basic info only",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // CSP Results (Best by ROC — 1 per stock)
            if (strategyFilter == "All" || strategyFilter == "CSPs") {
                val sortedCsps = item.csps?.sortedByDescending {
                    it.roc.parseToDouble()
                }?.take(1)

                sortedCsps?.forEach { csp ->
                    val expiryInfo = if (csp.expiry != null) " | Exp: ${csp.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "CSP Strike ${csp.strike}",
                        subtitle = "Prem: $${csp.premium} | Delta: ${csp.delta} | ROC: ${csp.roc ?: "N/A"}$expiryInfo",
                        bt = csp.bt ?: "N/A",
                        riskNote = csp.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker, strike = csp.strike, expiry = csp.expiry ?: "45DTE",
                                        trigger_price = item.price, entry_premium = csp.premium,
                                        contracts = 1, strategy = "CSP", is_call = 0, is_buy = 0
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = "CSP", contracts = 1, strike = csp.strike, expiry = csp.expiry ?: "45DTE", entryPremium = csp.premium))
                                    Toast.makeText(context, "Added ${item.ticker} CSP to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e, context)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            // Put Credit Spread Results (Best by monthly ROC — 1 per stock).
            // Defined-risk bullish spread. Capital = max_loss (much less than a
            // naked CSP), so we surface it as its own actionable row.
            if (strategyFilter == "All" || strategyFilter == "Put Credit Spreads") {
                item.putCreditSpreads?.take(1)?.forEach { pcs ->
                    val expiryInfo = if (pcs.expiry != null) " | Exp: ${pcs.expiry.formatDate()}" else ""
                    val widthTxt = pcs.width?.let { " | Width: $${"%.2f".format(it)}" } ?: ""
                    val deltaTxt = pcs.delta?.let { " | Δ: ${it}" } ?: ""
                    OpportunityRow(
                        title = "Put Credit Spread ${pcs.shortStrike}/${pcs.longStrike}",
                        subtitle = "Credit: $${"%.2f".format(pcs.credit)} | Max Loss: $${"%.2f".format(pcs.maxLoss)} | ROC/mo: ${pcs.roc ?: "N/A"}$widthTxt$deltaTxt$expiryInfo",
                        bt = pcs.bt ?: "N/A",
                        riskNote = pcs.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    // PCS is a spread; backend TradeEntry currently expects a
                                    // single strike. Record the SHORT leg as the primary strike
                                    // and encode the long leg in the strategy label so the
                                    // portfolio still reflects the actual position.
                                    val trade = TradeEntry(
                                        ticker = item.ticker,
                                        strike = pcs.shortStrike,
                                        expiry = pcs.expiry ?: "45DTE",
                                        trigger_price = item.price,
                                        entry_premium = pcs.credit,
                                        contracts = 1,
                                        strategy = "PCS SELL ${pcs.shortStrike}P / BUY ${pcs.longStrike}P",
                                        is_call = 0, is_buy = 0
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = trade.strategy, contracts = 1, strike = pcs.shortStrike, expiry = pcs.expiry ?: "45DTE", entryPremium = pcs.credit))
                                    Toast.makeText(context, "Added ${item.ticker} PCS to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e, context)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            // Diagonal Results (Best by Yield — 1 per stock)
            if (strategyFilter == "All" || strategyFilter == "Diagonals") {
                val sortedDiagonals = item.diagonals?.sortedByDescending {
                    it.yieldRatio.parseToDouble()
                }?.take(1)

                sortedDiagonals?.forEach { diag ->
                    val expiryInfo = if (diag.expiry != null) " | Exp: ${diag.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "Diagonal: BUY ${diag.longLeg.formatDate()} / SELL ${diag.shortLeg.formatDate()}",
                        subtitle = "Net Debit: $${diag.netDebt} | Yield: ${diag.yieldRatio ?: "N/A"}$expiryInfo",
                        bt = diag.bt ?: "N/A",
                        riskNote = diag.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker,
                                        strike = diag.netDebt,
                                        expiry = diag.expiry ?: "N/A",
                                        trigger_price = item.price,
                                        entry_premium = diag.netDebt,
                                        contracts = 1,
                                        strategy = "Diagonal BUY ${diag.longLeg ?: "?"} / SELL ${diag.shortLeg ?: "?"}",
                                        is_call = 1, is_buy = 1
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = trade.strategy, contracts = 1, strike = diag.netDebt, expiry = diag.expiry ?: "N/A", entryPremium = diag.netDebt))
                                    Toast.makeText(context, "Added ${item.ticker} Diagonal to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e, context)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            // Vertical Results (Limit 10, sorted by yield desc)
            if (strategyFilter == "All" || strategyFilter == "Verticals") {
                val sortedVerticals = item.verticals?.mapNotNull { vert ->
                    // Parse strikes "L110.0/S180.0" to compute yield
                    val yieldPct = try {
                        val parts = vert.strikes?.replace("L", "")?.replace("S", "")?.split("/")
                        if (parts?.size == 2) {
                            val low = parts[0].toDouble()
                            val high = parts[1].toDouble()
                            val width = high - low
                            if (vert.netDebit > 0) ((width - vert.netDebit) / vert.netDebit) * 100.0 else null
                        } else null
                    } catch (_: Exception) { null }
                    Pair(vert, yieldPct)
                }?.sortedByDescending { it.second ?: -1.0 }?.take(1)

                sortedVerticals?.forEach { (vert, yieldPct) ->
                    val yieldStr = if (yieldPct != null) "%.1f%%".format(yieldPct) else "N/A"
                    val expiryInfo = if (vert.expiry != null) " | Exp: ${vert.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "Vertical: ${vert.strikes ?: "N/A"}",
                        subtitle = "Net Debit: $${vert.netDebit} | Yield: $yieldStr$expiryInfo",
                        bt = vert.bt ?: "N/A",
                        riskNote = vert.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val buyStrike = try {
                                        vert.strikes?.replace("L", "")?.split("/")?.get(0)?.toDouble() ?: 0.0
                                    } catch (_: Exception) { 0.0 }
                                    val trade = TradeEntry(
                                        ticker = item.ticker,
                                        strike = buyStrike,
                                        expiry = vert.expiry ?: "N/A",
                                        trigger_price = item.price,
                                        entry_premium = vert.netDebit,
                                        contracts = 1,
                                        strategy = "Vertical ${vert.strikes ?: ""}",
                                        is_call = 1, is_buy = 1
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = trade.strategy, contracts = 1, strike = buyStrike, expiry = vert.expiry ?: "N/A", entryPremium = vert.netDebit))
                                    Toast.makeText(context, "Added ${item.ticker} Vertical to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e, context)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            // Long LEAPS Results UI Block (Limit 10)
            if (strategyFilter == "All" || strategyFilter == "Long LEAPS") {
                item.longLeaps?.take(1)?.forEach { leaps ->
                    val profileInfo = if (leaps.profile != null) " [${leaps.profile}]" else ""
                    val otmInfo = if (leaps.otmPct != null) " OTM: ${leaps.otmPct}" else ""
                    OpportunityRow(
                        title = "Long LEAPS: ${leaps.expiry.formatDate()} $${leaps.strike}C$profileInfo",
                        subtitle = "Prem: $${leaps.premium} | Lev: ${leaps.leverage ?: "N/A"} | Buffer: ${leaps.intrinsicBuffer ?: "N/A"}$otmInfo",
                        bt = leaps.bt ?: "N/A",
                        riskNote = leaps.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker, strike = leaps.strike, expiry = leaps.expiry,
                                        trigger_price = item.price, entry_premium = leaps.premium,
                                        contracts = 1, strategy = "Long LEAPS", is_call = 1, is_buy = 1
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = "Long LEAPS", contracts = 1, strike = leaps.strike, expiry = leaps.expiry, entryPremium = leaps.premium))
                                    Toast.makeText(context, "Added ${item.ticker} LEAPS to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e, context)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun OpportunityRow(title: String, subtitle: String, bt: String, riskNote: String? = null, onAdd: () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val btValue = bt.replace("%", "").toDoubleOrNull()
            val btColor = when {
                btValue != null && btValue >= 80 -> Color(0xFF2E7D32)
                btValue != null && btValue >= 60 -> Color(0xFFEF6C00)
                else -> Color(0xFFC62828)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = btColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        "BT: $bt",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = btColor
                    )
                }
            }
            if (riskNote != null) {
                Text("⚠ $riskNote", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00), modifier = Modifier.padding(top = 2.dp))
            }
        }
        FilledIconButton(
            onClick = onAdd,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Position", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// ==========================================
// AI GURU SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiGuruScreen() {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    var ticker by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("CSP") }
    var expandedType by remember { mutableStateOf(false) }
    val typeOptions = listOf("CSP", "Sell Call", "Vertical", "Diagonal", "Long LEAPS", "CSP-Funded Call")

    var strike by remember { mutableStateOf("") }
    var strikeSell by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var expirySell by remember { mutableStateOf("") }
    var premium by remember { mutableStateOf("") }
    // Second premium field used only by the CSP-Funded Call combo, which
    // takes a call premium AND a put premium on a single shared expiry.
    var premiumSell by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<BacktestResponse?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // AI cross-validation for backtest results
    var aiValidation by remember { mutableStateOf<AiCrossValidation?>(null) }
    var isAiValidating by remember { mutableStateOf(false) }

    // Auto-validate when backtest returns Strong Buy / BUY with High confidence
    LaunchedEffect(response) {
        val res = response ?: return@LaunchedEffect
        aiValidation = null
        if (!AiKeyManager.hasAnyKeys(context)) return@LaunchedEffect
        val isSell = selectedType in listOf("CSP", "Sell Call")
        // For sell strategies (CSP/Sell Call) the backend returns SELL/STRONG SELL for good trades
        val isStrongResult = if (isSell)
            res.verdict.contains("SELL", true) && (res.confidence != "None")
        else
            res.verdict.contains("BUY", true) && (res.confidence.equals("High", true) || res.verdict.contains("STRONG", true))
        if (!isStrongResult) return@LaunchedEffect

        isAiValidating = true
        try {
            aiValidation = AiCrossValidator.validate(
                context = context,
                ticker = ticker,
                price = res.price ?: 0.0,
                recommendation = res.verdict,
                signals = res.signals ?: emptyList(),
                warnings = res.warnings ?: emptyList(),
                levels = res.levels,
                sector = null,
                strategies = selectedType
            )
        } catch (e: Exception) {
            Log.e("AiGuru", "AI validation failed: ${e.message}")
        } finally {
            isAiValidating = false
        }
    }

    val isSpread = selectedType == "Vertical" || selectedType == "Diagonal"
    val isDiagonal = selectedType == "Diagonal"
    val isCfCombo = selectedType == "CSP-Funded Call"
    val isTwoStrikes = isSpread || isCfCombo

    // Focus requesters for field navigation
    val strikeFocus = remember { FocusRequester() }
    val strikeSellFocus = remember { FocusRequester() }
    val expiryFocus = remember { FocusRequester() }
    val expirySellFocus = remember { FocusRequester() }
    val premiumFocus = remember { FocusRequester() }
    val premiumSellFocus = remember { FocusRequester() }

    // Submit function
    fun submitForm() {
        if (ticker.isBlank() || isLoading) return
        keyboardController?.hide()

        // Normalise and validate expiry dates before sending
        val normExpiry = if (expiry.isNotBlank()) {
            normaliseExpiry(expiry) ?: run {
                errorMessage = "Unrecognised expiry format: \"$expiry\". Use YYYY-MM-DD (e.g. 2026-06-18) or DDMonYYYY (e.g. 18Jun2026)."
                return
            }
        } else null
        val normExpirySell = if (isDiagonal && expirySell.isNotBlank()) {
            normaliseExpiry(expirySell) ?: run {
                errorMessage = "Unrecognised sell-leg expiry format: \"$expirySell\". Use YYYY-MM-DD or DDMonYYYY."
                return
            }
        } else null

        // CSP-Funded Call combo: validate both legs are present, then run two
        // parallel backtests (long-call BUY + CSP SELL on a shared expiry) and
        // merge into a single synthetic verdict.
        if (isCfCombo) {
            val callStrike = strike.toDoubleOrNull()
            val putStrike = strikeSell.toDoubleOrNull()
            val callPrem = premium.toDoubleOrNull()
            val putPrem = premiumSell.toDoubleOrNull()
            if (normExpiry == null) {
                errorMessage = "CSP-Funded Call requires a shared expiry date."
                return
            }
            if (callStrike == null || putStrike == null || callPrem == null || putPrem == null) {
                errorMessage = "CSP-Funded Call requires call strike, put strike, call premium, and put premium."
                return
            }
            if (putStrike >= callStrike) {
                errorMessage = "Put strike (${putStrike}) should be below call strike (${callStrike}) for this combo."
                return
            }
            isLoading = true
            errorMessage = null
            response = null
            scope.launch {
                try {
                    val callReq = BacktestRequest(
                        ticker = ticker, strategy = "long_leaps", action = "buy",
                        strike = callStrike, expiry = normExpiry, premium = callPrem
                    )
                    val putReq = BacktestRequest(
                        ticker = ticker, strategy = "csp", action = "sell",
                        strike = putStrike, expiry = normExpiry, premium = putPrem
                    )
                    val (callRes, putRes) = withContext(Dispatchers.IO) {
                        val c = async { runCatching { apiService.getBacktest(callReq) } }
                        val p = async { runCatching { apiService.getBacktest(putReq) } }
                        c.await() to p.await()
                    }
                    val callOk = callRes.getOrNull()
                    val putOk = putRes.getOrNull()
                    if (callOk == null && putOk == null) {
                        errorMessage = friendlyErrorMessage(
                            (callRes.exceptionOrNull() ?: putRes.exceptionOrNull()) as? Exception
                                ?: Exception("Both legs failed")
                        )
                    } else {
                        response = mergeCfComboResponses(
                            callRes = callOk, putRes = putOk,
                            callStrike = callStrike, putStrike = putStrike,
                            callPrem = callPrem, putPrem = putPrem, expiry = normExpiry
                        )
                    }
                } catch (e: Exception) {
                    errorMessage = friendlyErrorMessage(e, context)
                } finally {
                    isLoading = false
                }
            }
            return
        }

        isLoading = true
        errorMessage = null
        response = null
        val strategyKey = when (selectedType) {
            "CSP" -> "csp"; "Sell Call" -> "sell_call"; "Vertical" -> "vertical"
            "Diagonal" -> "diagonal"; "Long LEAPS" -> "long_leaps"; else -> "csp"
        }
        val action = when (selectedType) { "CSP", "Sell Call" -> "sell"; else -> "buy" }
        scope.launch {
            try {
                val request = BacktestRequest(
                    ticker = ticker, strategy = strategyKey, action = action,
                    strike = strike.toDoubleOrNull(), strike_sell = strikeSell.toDoubleOrNull(),
                    expiry = normExpiry,
                    expiry_sell = normExpirySell,
                    premium = premium.toDoubleOrNull()
                )
                response = withContext(Dispatchers.IO) { apiService.getBacktest(request) }
            } catch (e: Exception) { errorMessage = friendlyErrorMessage(e, context) }
            finally { isLoading = false }
        }
    }

    Scaffold { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TipsAndUpdates,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Guru", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Select a strategy, enter your trade parameters, and get a backtesting-powered verdict.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            // Ticker Input
            item {
                OutlinedTextField(
                    value = ticker,
                    onValueChange = { ticker = it.uppercase().trim() },
                    label = { Text("Stock Symbol") },
                    placeholder = { Text("e.g. TSLA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }

            // Type Selection
            item {
                ExposedDropdownMenuBox(expanded = expandedType, onExpandedChange = { expandedType = it }) {
                    OutlinedTextField(
                        value = selectedType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Strategy Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expandedType, onDismissRequest = { expandedType = false }) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { selectedType = option; expandedType = false }
                            )
                        }
                    }
                }
            }

            // Strategy-specific fields
            item {
                OutlinedTextField(
                    value = strike,
                    onValueChange = { strike = it },
                    label = {
                        Text(
                            when {
                                isCfCombo -> "Call Strike (buy leg)"
                                isSpread -> "Buy Leg Strike"
                                else -> "Strike Price"
                            }
                        )
                    },
                    placeholder = { Text("e.g. 200") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (isTwoStrikes) strikeSellFocus.requestFocus() else expiryFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(strikeFocus)
                )
            }
            if (isTwoStrikes) {
                item {
                    OutlinedTextField(
                        value = strikeSell,
                        onValueChange = { strikeSell = it },
                        label = { Text(if (isCfCombo) "Put Strike (sell leg)" else "Sell Leg Strike") },
                        placeholder = { Text("e.g. 250") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { expiryFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(strikeSellFocus)
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = {
                        Text(
                            when {
                                isDiagonal -> "Buy Leg Expiry"
                                isCfCombo -> "Shared Expiry (call + put)"
                                else -> "Expiry"
                            }
                        )
                    },
                    placeholder = { Text("e.g. 2026-06-18 or 18Jun2026") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (isDiagonal) expirySellFocus.requestFocus() else premiumFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(expiryFocus)
                )
            }
            if (isDiagonal) {
                item {
                    OutlinedTextField(
                        value = expirySell,
                        onValueChange = { expirySell = it },
                        label = { Text("Sell Leg Expiry") },
                        placeholder = { Text("e.g. 2026-05-16 or 16May2026") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { premiumFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(expirySellFocus)
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = premium,
                    onValueChange = { premium = it },
                    label = {
                        Text(
                            when {
                                isCfCombo -> "Call Premium (paid)"
                                isSpread -> "Net Debit"
                                else -> "Premium"
                            }
                        )
                    },
                    placeholder = { Text("e.g. 5.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = if (isCfCombo) ImeAction.Next else ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { if (isCfCombo) premiumSellFocus.requestFocus() },
                        onDone = { submitForm() }
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(premiumFocus)
                )
            }
            if (isCfCombo) {
                item {
                    OutlinedTextField(
                        value = premiumSell,
                        onValueChange = { premiumSell = it },
                        label = { Text("Put Premium (received)") },
                        placeholder = { Text("e.g. 3.10") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitForm() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(premiumSellFocus)
                    )
                }
                item {
                    Text(
                        "Tip: best on high-IV trending names. Net debit ≈ call premium − put premium.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            // Ask AI Guru Button
            item {
                Button(
                    onClick = { submitForm() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = ticker.isNotBlank() && !isLoading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyzing...")
                    } else {
                        Icon(Icons.Default.TipsAndUpdates, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ask AI Guru", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Error
            if (errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                errorMessage!!,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Recommendation Result
            if (response != null) {
                item { BacktestResultCard(response!!, isSellStrategy = selectedType in listOf("CSP", "Sell Call")) }

                // AI Cross-Validation for backtest
                if (aiValidation != null) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        AiCrossValidationBadge(validation = aiValidation)
                    }
                } else if (isAiValidating) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF7C3AED))
                            Text("Cross-validating with AI engines...", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BacktestResultCard(res: BacktestResponse, isSellStrategy: Boolean = false) {
    // For sell-action strategies (CSP, Sell Call), the backend verdicts are inverted:
    //   STRONG SELL = strongly recommend entering the trade (sell the put/call)
    //   SELL        = recommend entering the trade
    //   HOLD        = marginal, consider waiting
    //   AVOID       = skip this trade
    // For stock/buy strategies, verdicts are stock-direction signals (BUY/SELL/HOLD/AVOID).
    val rawVerdict = res.verdict.uppercase()
    val displayVerdict: String
    val verdictColor: Color
    val verdictSubtitle: String   // brief explainer shown under the verdict
    if (isSellStrategy) {
        when (rawVerdict) {
            "STRONG SELL" -> {
                displayVerdict = "STRONG ENTRY"
                verdictColor = Color(0xFF1B5E20)
                verdictSubtitle = "Backtest strongly supports selling this option"
            }
            "SELL" -> {
                displayVerdict = "ENTER TRADE"
                verdictColor = Color(0xFF2E7D32)
                verdictSubtitle = "Conditions support entering this position"
            }
            "HOLD" -> {
                displayVerdict = "WAIT"
                verdictColor = Color(0xFFEF6C00)
                verdictSubtitle = "Setup is marginal — consider waiting for better conditions"
            }
            "AVOID" -> {
                displayVerdict = "SKIP"
                verdictColor = Color(0xFFC62828)
                verdictSubtitle = "Current conditions do not favour this trade"
            }
            else -> {
                displayVerdict = rawVerdict
                verdictColor = Color(0xFF757575)
                verdictSubtitle = ""
            }
        }
    } else {
        when (rawVerdict) {
            "STRONG BUY" -> { displayVerdict = "STRONG BUY"; verdictColor = Color(0xFF1B5E20); verdictSubtitle = "" }
            "BUY"        -> { displayVerdict = "BUY";        verdictColor = Color(0xFF2E7D32); verdictSubtitle = "" }
            "SELL"       -> { displayVerdict = "SELL";       verdictColor = Color(0xFFC62828); verdictSubtitle = "" }
            "STRONG SELL"-> { displayVerdict = "STRONG SELL";verdictColor = Color(0xFFB71C1C); verdictSubtitle = "" }
            "HOLD"       -> { displayVerdict = "HOLD";       verdictColor = Color(0xFFEF6C00); verdictSubtitle = "" }
            else         -> { displayVerdict = rawVerdict;   verdictColor = Color(0xFF757575); verdictSubtitle = "" }
        }
    }
    // Confidence label — for sell strategies, clarify that confidence reflects signal quality,
    // not the backtest success rate (which is shown separately in the metrics row).
    val confidenceLabel = when (res.confidence) {
        "High"   -> if (isSellStrategy) "High Signal Confidence" else "High Confidence"
        "Medium" -> if (isSellStrategy) "Mixed Signals" else "Medium Confidence"
        "Low"    -> if (isSellStrategy) "Signals Mixed — see backtest score" else "Low Confidence"
        "None"   -> if (isSellStrategy) "No Signals — use caution" else "No Confidence"
        else     -> "${res.confidence} Confidence"
    }
    val confidenceColor = when (res.confidence) {
        "High" -> Color(0xFF2E7D32)
        "Medium" -> Color(0xFFEF6C00)
        else -> Color(0xFF757575)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = verdictColor.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Verdict header + confidence
            Column {
                Text(
                    displayVerdict,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = verdictColor
                )
                if (verdictSubtitle.isNotEmpty()) {
                    Text(
                        verdictSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = verdictColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = confidenceColor.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        confidenceLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = confidenceColor
                    )
                }
            }

            // Summary
            Text(res.summary, style = MaterialTheme.typography.bodyLarge)

            HorizontalDivider()

            // Key metrics row
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (res.price != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Price", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text("$${"%,.2f".format(res.price)}", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (res.rsi != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("RSI", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(
                                "${"%.1f".format(res.rsi)}",
                                fontWeight = FontWeight.Bold,
                                color = if (res.rsi < 30) Color(0xFF2E7D32) else if (res.rsi > 70) Color(0xFFC62828) else Color.Unspecified
                            )
                        }
                    }
                    if (res.backtestScore != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Backtest", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(res.backtestScore, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        }
                    }
                }
            }

            // Signals
            val signals = res.signals ?: emptyList()
            if (signals.isNotEmpty()) {
                HorizontalDivider()
                Text("Signals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                signals.forEach { signal ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text("✦ ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Text(signal, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Warnings
            val warnings = res.warnings ?: emptyList()
            if (warnings.isNotEmpty()) {
                HorizontalDivider()
                Text("Warnings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                warnings.forEach { warning ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text("⚠ ", style = MaterialTheme.typography.bodyMedium)
                        Text(warning, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEF6C00))
                    }
                }
            }

            // Key Levels from backtest
            if (res.levels != null) {
                HorizontalDivider()
                Text("Key Levels", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                val lvl = res.levels
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (lvl.target != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🎯 Target", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            Text("$${"%.2f".format(lvl.target)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (lvl.resistance != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Resistance", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                            Text("$${"%.2f".format(lvl.resistance)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                        }
                    }
                    if (lvl.support != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Support", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
                            Text("$${"%.2f".format(lvl.support)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
                        }
                    }
                    if (lvl.stopLoss != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            // For sell-side backtests (sell_call, short stock,
                            // bear vertical/diagonal) the backend returns a
                            // stop ABOVE current price. Rename to
                            // "Short-cover Stop" so it doesn't read as a
                            // broken long-position stop.
                            val stopLabel = if (isSellStrategy) "🛑 Short-cover Stop" else "🛑 Stop Loss"
                            Text(stopLabel, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                            Text("$${"%.2f".format(lvl.stopLoss)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (lvl.riskReward != null) {
                        val rr = lvl.riskReward
                        val rrColor = if (rr >= 2.0) Color(0xFF2E7D32) else if (rr >= 1.0) Color(0xFFEF6C00) else Color(0xFFC62828)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Reward : Risk", style = MaterialTheme.typography.bodySmall)
                            Text("${"%.1f".format(rr)} : 1", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = rrColor)
                        }
                        Text(
                            "Potential reward of \$${"%.1f".format(rr)} per \$1 risked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (lvl.riskNote != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("💡 ${lvl.riskNote}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // AI Learning info
            if (res.learning != null && res.learning.enabled) {
                HorizontalDivider()
                Text("AI Learning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED))
                if (res.learning.adjustmentReason != null) {
                    Text(res.learning.adjustmentReason, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                if (res.learning.applied == true && res.learning.originalVerdict != res.learning.adjustedVerdict) {
                    Text(
                        "Adjusted: ${res.learning.originalVerdict} → ${res.learning.adjustedVerdict}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF6C00)
                    )
                }
            }
        }
    }
}

@Composable
fun PortfolioScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var healthData by remember { mutableStateOf<HealthResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var closingPosition by remember { mutableStateOf<ActivePosition?>(null) }
    var closingIndex by remember { mutableIntStateOf(-1) }
    var editingPosition by remember { mutableStateOf<ActivePosition?>(null) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var deletingPosition by remember { mutableStateOf<ActivePosition?>(null) }
    var deletingIndex by remember { mutableIntStateOf(-1) }

    fun refreshData() {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null
                val response = try {
                    apiService.getPositions()
                } catch (_: Exception) {
                    apiService.getHealth()
                }
                val cachedActive = PortfolioCache.loadActivePositions(context)
                val backendActive = response.activePositions
                val backendClosed = response.closedPositions ?: emptyList()
                if (backendActive.isNotEmpty() || cachedActive.isEmpty()) {
                    val backendIds = backendActive.mapNotNull { it.id }.toSet()
                    val localOnly = cachedActive.filter { it.id == null || it.id !in backendIds }
                    val mergedActive = backendActive + localOnly
                    val cachedClosed = PortfolioCache.loadClosedPositions(context)
                    val backendClosedIds = backendClosed.mapNotNull { it.id }.toSet()
                    val localOnlyClosed = cachedClosed.filter { it.id == null || it.id !in backendClosedIds }
                    val mergedClosed = backendClosed + localOnlyClosed
                    healthData = response.copy(activePositions = mergedActive, closedPositions = mergedClosed)
                    PortfolioCache.savePositions(context, mergedActive, mergedClosed)
                } else {
                    val cachedClosed = PortfolioCache.loadClosedPositions(context)
                    healthData = HealthResponse(
                        status = response.status,
                        capitalHealth = response.capitalHealth,
                        performance = response.performance,
                        activePositions = cachedActive,
                        closedPositions = cachedClosed
                    )
                }
            } catch (e: Exception) {
                Log.e("PORTFOLIO", "Health load failed: ${e.message}")
                errorMessage = friendlyErrorMessage(e, context)
                // If backend fails and we have no data yet, load from local cache
                if (healthData == null) {
                    val cachedActive = PortfolioCache.loadActivePositions(context)
                    val cachedClosed = PortfolioCache.loadClosedPositions(context)
                    if (cachedActive.isNotEmpty() || cachedClosed.isNotEmpty()) {
                        healthData = HealthResponse(
                            status = "cached",
                            capitalHealth = CapitalHealth(0.0),
                            performance = PerformanceMetrics(0.0, "N/A"),
                            activePositions = cachedActive,
                            closedPositions = cachedClosed
                        )
                        errorMessage = null
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Load cached data first for instant display
        val cachedActive = PortfolioCache.loadActivePositions(context)
        val cachedClosed = PortfolioCache.loadClosedPositions(context)
        if (cachedActive.isNotEmpty() || cachedClosed.isNotEmpty()) {
            healthData = HealthResponse(
                status = "cached",
                capitalHealth = CapitalHealth(0.0),
                performance = PerformanceMetrics(0.0, "N/A"),
                activePositions = cachedActive,
                closedPositions = cachedClosed
            )
        }
        // Then refresh from backend (will overwrite with live data)
        refreshData()
    }

    // --- Dialogs ---

    if (showAddManualDialog) {
        AddManualPositionDialog(
            onDismiss = { showAddManualDialog = false },
            onSave = { trade ->
                scope.launch {
                    try {
                        val backendId = try {
                            val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                            (resp["id"] as? Number)?.toInt()
                        } catch (_: Exception) { null }
                        if (trade.exit_price != null) {
                            val current = PortfolioCache.loadClosedPositions(context).toMutableList()
                            current.add(ClosedPosition(
                                id = backendId, ticker = trade.ticker, strategy = trade.strategy,
                                contracts = trade.contracts, strike = trade.strike, expiry = trade.expiry,
                                entryPremium = trade.entry_premium, exitPrice = trade.exit_price,
                                exitDate = trade.exit_date ?: ""
                            ))
                            val active = PortfolioCache.loadActivePositions(context)
                            PortfolioCache.savePositions(context, active, current)
                        } else {
                            PortfolioCache.addPosition(context, ActivePosition(
                                id = backendId, ticker = trade.ticker, strategy = trade.strategy,
                                contracts = trade.contracts, strike = trade.strike, expiry = trade.expiry,
                                entryPremium = trade.entry_premium
                            ))
                        }
                        showAddManualDialog = false
                        val cachedActive = PortfolioCache.loadActivePositions(context)
                        val cachedClosed = PortfolioCache.loadClosedPositions(context)
                        healthData = HealthResponse(
                            status = healthData?.status ?: "cached",
                            capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                            performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                            activePositions = cachedActive,
                            closedPositions = cachedClosed
                        )
                        snackbarHostState.showSnackbar("Position added successfully")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to add: ${friendlyErrorMessage(e, context)}")
                    }
                }
            }
        )
    }

    if (closingPosition != null) {
        ClosePositionDialog(
            position = closingPosition!!,
            onDismiss = { closingPosition = null; closingIndex = -1 },
            onConfirm = { exitPrice, exitDate ->
                scope.launch {
                    try {
                        val pos = closingPosition!!
                        val idx = closingIndex
                        try {
                            pos.id?.let { apiService.closePosition(it, mapOf("exit_price" to exitPrice, "exit_date" to exitDate)) }
                        } catch (_: Exception) { }
                        if (idx >= 0) {
                            PortfolioCache.removePosition(context, idx)
                        }
                        val closedList = PortfolioCache.loadClosedPositions(context).toMutableList()
                        closedList.add(ClosedPosition(
                            id = pos.id, ticker = pos.ticker, strategy = pos.strategy,
                            contracts = pos.contracts, strike = pos.strike, expiry = pos.expiry,
                            entryPremium = pos.entryPremium,
                            exitPrice = exitPrice.toDoubleOrNull() ?: 0.0,
                            exitDate = exitDate
                        ))
                        val activeList = PortfolioCache.loadActivePositions(context)
                        PortfolioCache.savePositions(context, activeList, closedList)
                        closingPosition = null
                        closingIndex = -1
                        healthData = HealthResponse(
                            status = healthData?.status ?: "cached",
                            capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                            performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                            activePositions = activeList,
                            closedPositions = closedList
                        )
                        snackbarHostState.showSnackbar("Position closed")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to close: ${friendlyErrorMessage(e, context)}")
                    }
                }
            }
        )
    }

    if (editingPosition != null) {
        EditPositionDialog(
            position = editingPosition!!,
            onDismiss = { editingPosition = null; editingIndex = -1 },
            onSave = { trade ->
                scope.launch {
                    try {
                        // Update local cache directly
                        val updatedPos = ActivePosition(
                            id = editingPosition?.id,
                            ticker = trade.ticker,
                            strategy = trade.strategy,
                            contracts = trade.contracts,
                            strike = trade.strike,
                            expiry = trade.expiry,
                            entryPremium = trade.entry_premium
                        )
                        if (editingIndex >= 0) {
                            PortfolioCache.updatePosition(context, editingIndex, updatedPos)
                        }
                        // Best-effort backend sync (only update, never create duplicate)
                        try {
                            val posId = editingPosition?.id
                            if (posId != null) apiService.updatePosition(posId, trade)
                        } catch (_: Exception) { }
                        editingPosition = null
                        editingIndex = -1
                        // Reload from cache to reflect the edit
                        val cachedActive = PortfolioCache.loadActivePositions(context)
                        val cachedClosed = PortfolioCache.loadClosedPositions(context)
                        healthData = HealthResponse(
                            status = healthData?.status ?: "cached",
                            capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                            performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                            activePositions = cachedActive,
                            closedPositions = cachedClosed
                        )
                        snackbarHostState.showSnackbar("Position updated")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to update: ${friendlyErrorMessage(e, context)}")
                    }
                }
            }
        )
    }

    if (deletingPosition != null) {
        AlertDialog(
            onDismissRequest = { deletingPosition = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Position") },
            text = {
                Text("Remove ${deletingPosition!!.ticker} ${deletingPosition!!.strategy} (${deletingPosition!!.contracts}x ${deletingPosition!!.strike})?\n\nThis action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pos = deletingPosition!!
                        val idx = deletingIndex
                        deletingPosition = null
                        deletingIndex = -1
                        scope.launch {
                            try {
                                // Remove from local cache
                                if (idx >= 0) {
                                    PortfolioCache.removePosition(context, idx)
                                }
                                // Best-effort backend sync
                                try { pos.id?.let { apiService.removePosition(it) } } catch (_: Exception) { }
                                // Reload from cache
                                val cachedActive = PortfolioCache.loadActivePositions(context)
                                val cachedClosed = PortfolioCache.loadClosedPositions(context)
                                healthData = HealthResponse(
                                    status = healthData?.status ?: "cached",
                                    capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                                    performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                                    activePositions = cachedActive,
                                    closedPositions = cachedClosed
                                )
                                snackbarHostState.showSnackbar("${pos.ticker} position removed")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Failed to delete: ${friendlyErrorMessage(e, context)}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingPosition = null }) { Text("Cancel") }
            }
        )
    }

    // --- Main Layout ---

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddManualDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Manual Position")
            }
        }
    ) { padding ->
        // Initial state or error state with retry
        if (!isLoading && healthData == null && errorMessage == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (!isLoading && healthData == null && errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Could not load portfolio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { refreshData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
        }
        // Loading state
        else if (isLoading && healthData == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Loading portfolio...", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
            }
        }
        // Data loaded
        else if (healthData != null) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Portfolio Health", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { refreshData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Capital Committed", style = MaterialTheme.typography.labelLarge)
                            Text("$${"%,.2f".format(healthData?.capitalHealth?.committed)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Monthly Realized Profit", style = MaterialTheme.typography.labelLarge)
                            Text("$${"%,.2f".format(healthData?.performance?.monthlyRealized)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Goal Progress: ${healthData?.performance?.progress}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active Positions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${healthData?.activePositions?.size ?: 0}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                val activePositions = healthData?.activePositions ?: emptyList()
                if (activePositions.isEmpty()) {
                    item {
                        Text(
                            "No active positions. Tap + to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    itemsIndexed(activePositions) { index, pos ->
                        PositionCard(
                            pos = pos,
                            onEdit = { editingPosition = pos; editingIndex = index },
                            onRemove = { deletingPosition = pos; deletingIndex = index },
                            onClose = { closingPosition = pos; closingIndex = index }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Closed Positions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${healthData?.closedPositions?.size ?: 0}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                val closedPositions = healthData?.closedPositions ?: emptyList()
                if (closedPositions.isEmpty()) {
                    item {
                        Text(
                            "No closed positions yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(closedPositions) { pos ->
                        ClosedPositionCard(pos)
                    }
                }

                // Bottom spacer for FAB
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
fun PositionCard(pos: ActivePosition, onEdit: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pos.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                pos.strategy,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${pos.contracts}x $${pos.strike} | Exp: ${pos.expiry}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "$${pos.entryPremium}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onClose) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF388E3C))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Close", style = MaterialTheme.typography.labelMedium, color = Color(0xFF388E3C))
                }
                TextButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ClosedPositionCard(pos: ClosedPosition) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${pos.ticker} ${pos.strategy}", fontWeight = FontWeight.Bold)
                Text("${pos.contracts}x $${pos.strike} | Exp: ${pos.expiry}", style = MaterialTheme.typography.bodySmall)
                Text("Closed on ${pos.exitDate}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End) {
                val profit = (pos.exitPrice - pos.entryPremium) * pos.contracts * 100
                Text("Exit: $${pos.exitPrice}", fontWeight = FontWeight.Bold)
                Text(
                    text = "${if (profit >= 0) "+" else ""}$${"%.2f".format(profit)}",
                    color = if (profit >= 0) Color(0xFF388E3C) else Color.Red,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosePositionDialog(position: ActivePosition, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var exitPrice by remember { mutableStateOf("") }
    var exitDate by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close Position: ${position.ticker}") },
        text = {
            Column {
                Text("${position.strategy} | ${position.contracts}x $${position.strike}")
                Text("Entry Premium: $${position.entryPremium}", color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = exitPrice,
                    onValueChange = { exitPrice = it },
                    label = { Text("Exit Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = exitDate,
                    onValueChange = { exitDate = it },
                    label = { Text("Exit Date (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(exitPrice, exitDate) },
                enabled = exitPrice.toDoubleOrNull() != null && exitDate.isNotBlank()
            ) { Text("Confirm Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPositionDialog(position: ActivePosition, onDismiss: () -> Unit, onSave: (TradeEntry) -> Unit) {
    var contracts by remember { mutableStateOf(position.contracts.toString()) }
    var strike by remember { mutableStateOf(position.strike.toString()) }
    var expiry by remember { mutableStateOf(position.expiry) }
    var entryPremium by remember { mutableStateOf(position.entryPremium.toString()) }
    var strategy by remember { mutableStateOf(position.strategy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit: ${position.ticker}") },
        text = {
            Column {
                OutlinedTextField(value = strategy, onValueChange = { strategy = it }, label = { Text("Strategy") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = strike, onValueChange = { strike = it }, label = { Text("Strike Price") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = contracts, onValueChange = { contracts = it }, label = { Text("Contracts") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text("Expiry Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = entryPremium, onValueChange = { entryPremium = it }, label = { Text("Entry Premium") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trade = TradeEntry(
                        ticker = position.ticker,
                        strike = strike.toDoubleOrNull() ?: position.strike,
                        expiry = expiry,
                        trigger_price = 0.0,
                        entry_premium = entryPremium.toDoubleOrNull() ?: position.entryPremium,
                        contracts = contracts.toIntOrNull() ?: position.contracts,
                        strategy = strategy,
                        is_call = if (strategy.contains("Call", true) || strategy.contains("LEAPS", true)) 1 else 0,
                        is_buy = 0
                    )
                    onSave(trade)
                },
                enabled = strike.toDoubleOrNull() != null && contracts.toIntOrNull() != null && entryPremium.toDoubleOrNull() != null
            ) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddManualPositionDialog(onDismiss: () -> Unit, onSave: (TradeEntry) -> Unit) {
    var ticker by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("CSP") }
    var expandedStrategy by remember { mutableStateOf(false) }
    val strategyOptions = listOf("CSP", "Vertical", "Diagonal", "Long LEAPS")
    var contracts by remember { mutableStateOf("1") }
    var strike by remember { mutableStateOf("") }
    var strikeSell by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }
    var expiryShort by remember { mutableStateOf("") }
    var entryPremium by remember { mutableStateOf("") }
    var isClosed by remember { mutableStateOf(false) }
    var exitPrice by remember { mutableStateOf("") }
    var exitDate by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }

    val isSpread = strategy == "Vertical" || strategy == "Diagonal"
    val isValid = ticker.isNotBlank() && strike.toDoubleOrNull() != null && entryPremium.toDoubleOrNull() != null &&
            (!isSpread || strikeSell.toDoubleOrNull() != null) &&
            (!isClosed || exitPrice.toDoubleOrNull() != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Manual Position") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedTextField(value = ticker, onValueChange = { ticker = it.uppercase() }, label = { Text("Ticker *") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))

                    // Strategy dropdown
                    ExposedDropdownMenuBox(
                        expanded = expandedStrategy,
                        onExpandedChange = { expandedStrategy = !expandedStrategy }
                    ) {
                        OutlinedTextField(
                            value = strategy,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Strategy") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedStrategy) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedStrategy, onDismissRequest = { expandedStrategy = false }) {
                            strategyOptions.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    strategy = option
                                    expandedStrategy = false
                                })
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Buy leg strike
                    OutlinedTextField(
                        value = strike, onValueChange = { strike = it },
                        label = { Text(if (isSpread) "Buy Leg Strike *" else "Strike Price *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Sell leg strike (only for spreads)
                    if (isSpread) {
                        OutlinedTextField(
                            value = strikeSell, onValueChange = { strikeSell = it },
                            label = { Text("Sell Leg Strike *") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(value = contracts, onValueChange = { contracts = it }, label = { Text("Contracts") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text(if (isSpread && strategy == "Diagonal") "Buy Leg Expiry (YYYY-MM-DD)" else "Expiry Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))

                    // Short leg expiry for Diagonal
                    if (strategy == "Diagonal") {
                        OutlinedTextField(value = expiryShort, onValueChange = { expiryShort = it }, label = { Text("Sell Leg Expiry (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(value = entryPremium, onValueChange = { entryPremium = it }, label = { Text(if (isSpread) "Net Debit *" else "Entry Premium *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isClosed, onCheckedChange = { isClosed = it })
                        Text("Add as Closed Position")
                    }

                    if (isClosed) {
                        OutlinedTextField(value = exitPrice, onValueChange = { exitPrice = it }, label = { Text("Exit Price *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = exitDate, onValueChange = { exitDate = it }, label = { Text("Exit Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val strategyStr = if (isSpread) {
                        val sellStrikeVal = strikeSell.toDoubleOrNull() ?: 0.0
                        val buyStrikeVal = strike.toDoubleOrNull() ?: 0.0
                        if (strategy == "Diagonal") {
                            "Diagonal BUY $expiry $$buyStrikeVal / SELL ${expiryShort.ifBlank { "N/A" }} $$sellStrikeVal"
                        } else {
                            "Vertical L$buyStrikeVal/S$sellStrikeVal"
                        }
                    } else strategy

                    val trade = TradeEntry(
                        ticker = ticker,
                        strike = strike.toDoubleOrNull() ?: 0.0,
                        expiry = expiry,
                        trigger_price = 0.0,
                        entry_premium = entryPremium.toDoubleOrNull() ?: 0.0,
                        contracts = contracts.toIntOrNull() ?: 1,
                        strategy = strategyStr,
                        is_call = if (strategy.contains("Call", true) || strategy.contains("LEAPS", true) || strategy == "Vertical" || strategy == "Diagonal") 1 else 0,
                        is_buy = if (strategy == "CSP") 0 else 1,
                        exit_price = if (isClosed) exitPrice.toDoubleOrNull() else null,
                        exit_date = if (isClosed) exitDate else null
                    )
                    onSave(trade)
                },
                enabled = isValid
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Cancel") }
        }
    )
}

// ==========================================
// NOTIFICATIONS SCREEN
// ==========================================
@Composable
fun NotificationsScreen() {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val notifications = remember(refreshTick) { NotificationCache.load(context) }

    // Observe the manual-trigger work so the UI reflects real WorkManager state
    // (scanning ~50 tickers in batches over the Render backend can take 1–3 minutes).
    val workInfos by produceState<List<WorkInfo>>(initialValue = emptyList()) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow("DailyRecommendation_manual")
            .collect { value = it }
    }
    val activeInfo = workInfos.firstOrNull { !it.state.isFinished }
    val isRunning = activeInfo != null
    var elapsedSec by remember { mutableStateOf(0) }

    // Pull live progress (N of M scanned) from the worker's setProgress data
    // so the user can see how many symbols have been processed and estimate
    // remaining time. Falls back to 0/0 until the first batch publishes.
    val progressDone = activeInfo?.progress?.getInt(DailyRecommendationWorker.PROGRESS_DONE, 0) ?: 0
    val progressTotal = activeInfo?.progress?.getInt(DailyRecommendationWorker.PROGRESS_TOTAL, 0) ?: 0
    val progressPhase = activeInfo?.progress?.getString(DailyRecommendationWorker.PROGRESS_PHASE)

    // Tick a 1-second clock while the worker is running so the user sees
    // continuous feedback (otherwise the button just looked frozen).
    LaunchedEffect(isRunning) {
        if (isRunning) {
            elapsedSec = 0
            while (true) {
                delay(1000)
                elapsedSec += 1
            }
        } else {
            elapsedSec = 0
        }
    }

    // When the worker finishes, auto-refresh the notification list so the new
    // entry appears without the user having to navigate away and back.
    val lastFinishedId = workInfos.firstOrNull { it.state.isFinished }?.id
    LaunchedEffect(lastFinishedId, isRunning) {
        if (!isRunning && lastFinishedId != null) {
            refreshTick++
        }
    }

    // Re-load when user returns to this tab
    LaunchedEffect(Unit) { refreshTick++ }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Notification History", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Manual trigger button — runs the same pipeline as the 6:50 AM daily scan.
        Button(
            onClick = {
                // Manual scan priority: cancel any currently-running
                // scheduled scan workers so the manual pipeline (which
                // also hits `/scan`) doesn't have to wait behind them.
                ScanCoordinator.beginManualScan(context)
                val req = OneTimeWorkRequestBuilder<DailyRecommendationWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag("DailyRecommendation_manual")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "DailyRecommendation_manual",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
                Toast.makeText(
                    context,
                    "Scan started. Runs in the background — you'll get a notification when it finishes.",
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isRunning,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Compose a label that always tells the user *something*
                // meaningful — never just the wall-clock elapsed time.
                //   - Before the worker emits its first setProgress (the
                //     few hundred ms between ENQUEUED → RUNNING):
                //         "Starting scan…"
                //   - During pre-scan phases (watchlist sync, pre-warm):
                //         phase string from worker, e.g. "Syncing watchlist…"
                //   - Mid-scan (partial coverage):
                //         "12 of 42 symbols"
                //   - Tail phases after symbols are all in (retry, trending,
                //     analysis): phase string from worker, e.g.
                //         "Fetching trending + analysis…"
                val mm = elapsedSec / 60
                val ss = elapsedSec % 60
                val elapsed = "%d:%02d".format(mm, ss)
                val label = when {
                    progressTotal <= 0 ->
                        "Starting scan… $elapsed"
                    progressDone in 1 until progressTotal ->
                        "$progressDone of $progressTotal symbols · $elapsed"
                    !progressPhase.isNullOrBlank() ->
                        "$progressPhase $elapsed"
                    progressDone >= progressTotal ->
                        "Finalizing… $elapsed"
                    else ->
                        "Scanning… $elapsed"
                }
                Text(label, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Send Today's Picks Now", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Stop button — only visible while the manual daily scan is running.
        // Cancels the unique WorkManager job (which propagates a
        // CancellationException into the worker's `withTimeout` block; the
        // worker translates that into Result.success() with no notification).
        // Gives the user an escape hatch when the backend is wedged and the
        // 6-min hard timeout hasn't fired yet.
        if (isRunning) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    WorkManager.getInstance(context)
                        .cancelUniqueWork("DailyRecommendation_manual")
                    Toast.makeText(
                        context,
                        "Stopping scan\u2026 (may take a few seconds to release the network connection).",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB91C1C))
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop scan", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Manual trigger for the hourly portfolio/trending flip scan.
        // Bypasses the market-hours gate so the user can confirm the
        // pipeline (network, X-User-Id, notification channel) end-to-end.
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                // Manual scan priority: same rationale as the daily manual
                // button above — don't queue behind an in-flight scheduled
                // worker that's already holding the backend scan lock.
                ScanCoordinator.beginManualScan(context)
                val req = OneTimeWorkRequestBuilder<PortfolioFlipWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag(PortfolioFlipWorker.TAG_MANUAL)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    PortfolioFlipWorker.TAG_MANUAL,
                    ExistingWorkPolicy.REPLACE,
                    req
                )
                Toast.makeText(
                    context,
                    "Hourly scan started. A confirmation notification will appear within ~30s.",
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Test Hourly Scan Now", style = MaterialTheme.typography.labelMedium)
        }

        // Live status banner while the worker is running so the user knows
        // the app is actually doing something during the 1–3 min scan.
        if (isRunning) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFEDE9FE),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        progressPhase ?: "Scanning watchlist + portfolio + ETFs + trending…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF5B21B6)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val mm = elapsedSec / 60
                    val ss = elapsedSec % 60
                    val countLine = if (progressTotal > 0) {
                        "Scanned $progressDone of $progressTotal symbols  •  elapsed %d:%02d".format(mm, ss)
                    } else {
                        "Preparing scan…  elapsed %d:%02d".format(mm, ss)
                    }
                    Text(
                        countLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6D28D9)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "3-ticker batches over Render. Push notification will appear when complete; you can leave this screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6D28D9)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (progressTotal > 0) {
                        // Determinate bar = real progress; falls back to
                        // indeterminate sweep while we wait for the first update.
                        LinearProgressIndicator(
                            progress = { progressDone.toFloat() / progressTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF7C3AED)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF7C3AED)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No notifications yet", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    Text(
                        "Daily recommendations will appear here at 6:45 AM on market days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(notifications) { notification ->
                    NotificationCard(notification)
                }
            }
        }
    }
}

@Composable
fun NotificationCard(notification: NotificationRecord) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", java.util.Locale.getDefault()) }
    // Pre-parse the body into structured sections / blocks. Done with remember
    // so we don't re-parse on every recomposition (toggling a single section
    // expand triggers many recompositions of the parent).
    val parsed = remember(notification.body) { parseAlertBody(notification.body) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .animateContentSize()
        ) {
            Text(notification.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                dateFormat.format(java.util.Date(notification.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Preamble (text BEFORE the first emoji-header line). Renders verbatim
            // so legacy plain-text alerts and one-liner banners still look right.
            if (parsed.preamble.isNotBlank()) {
                Text(
                    htmlToAnnotated(parsed.preamble),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (parsed.sections.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Each top-level section becomes a tap-to-expand row. Default state:
            // FIRST section expanded so the user immediately sees value; the
            // rest collapse to a clickable header line, dramatically reducing
            // wall-of-text feel.
            parsed.sections.forEachIndexed { idx, section ->
                if (idx > 0) Spacer(modifier = Modifier.height(6.dp))
                AlertSectionGroup(section = section, initiallyExpanded = (idx == 0))
            }

            // Defensive fallback: a body that produced NO sections AND NO
            // preamble (extremely rare — would only happen on an empty string).
            if (parsed.preamble.isBlank() && parsed.sections.isEmpty()) {
                Text(htmlToAnnotated(notification.body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Alert parsing — turns the existing emoji-header notification body into a
// structured ParsedAlert (preamble + sections + per-recommendation blocks).
// Backwards-compatible: legacy plain-text bodies (no emoji headers) fall
// through into `preamble` and render verbatim via htmlToAnnotated.
// ---------------------------------------------------------------------------

/** A single self-contained chunk within a section — typically one ticker's
 *  recommendation, or a sub-header (✅ Best to BUY) + its detail rows. */
internal data class AlertBlock(
    val lines: List<String>
) {
    /** First non-blank line, used as the block's accent / quick-glance line. */
    val headline: String get() = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/** A top-level section starting with an emoji-header line ending in `:`. */
internal data class AlertSection(
    val header: String,
    val blocks: List<AlertBlock>
)

internal data class ParsedAlert(
    val preamble: String,
    val sections: List<AlertSection>
)

// Same emoji set used server-side and in DailyRecommendationWorker.toRichHtml.
//
// A top-level header is an emoji at column 0, followed by a space, followed
// by a short label (1-80 non-colon characters), followed by a colon. The
// remainder of the line is optional metadata (e.g. "⚠️ Skipped: AAA, BBB").
// Requiring the colon to be present — anywhere — prevents formatter lines
// like "🚀 LEAPS NVDA $200C (exp 2026-09-19, prem $12.50)" (no colon)
// from being misclassified as section headers.
private val TOP_LEVEL_HEADER_REGEX = Regex(
    """^(?:🛡️|⚖️|🛑|🎯|📢|🚀|📊|📐|📈|🔭|✅|❌|⚠️|🔍|🤖|📅|🟢|🔴|🟡|🔻|🚨|💡)\s[^:\n]{1,80}:.*$"""
)

// Sub-headers (indented bands inside a section) use the STRICT colon-at-end
// rule on purpose. Detail rows can carry colons mid-line (e.g.
// "  📈 RSI: 58 • MACD: bullish") and we must NOT classify those as block
// boundaries. The trailing-colon convention applies only to actual labels
// like "✅ Best to BUY (high R:R):" / "❌ Worst to AVOID/SELL:".
private val SUB_HEADER_REGEX = Regex(
    """^(?:🛡️|⚖️|🛑|🎯|📢|🚀|📊|📐|📈|🔭|✅|❌|⚠️|🔍|🤖|📅|🟢|🔴|🟡|🔻|🚨|💡)\s.+:\s*$"""
)

/**
 * Parse a notification body into a ParsedAlert. The body may be:
 *  - HTML with <b> and <br/> (current format from DailyRecommendationWorker.toRichHtml)
 *  - Plain text with emoji headers (legacy server-side format)
 *  - Plain text with no headers at all (oldest legacy format)
 */
internal fun parseAlertBody(rawBody: String): ParsedAlert {
    // Strip HTML once. HtmlCompat handles <br/>, <b>, &amp;, etc. The bold
    // styling is re-applied per-line via htmlToAnnotated on the original HTML
    // (we keep the source so styled tickers/headers still render bold).
    val spanned = androidx.core.text.HtmlCompat.fromHtml(
        rawBody,
        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
    )
    return parseAlertBodyFromPlain(spanned.toString())
}

/**
 * Pure-text variant of [parseAlertBody] — accepts the already-HTML-stripped
 * plain text. Exists so JVM unit tests can exercise the parser without the
 * Android-only HtmlCompat dependency.
 */
internal fun parseAlertBodyFromPlain(rawPlain: String): ParsedAlert {
    val plain = rawPlain.trimEnd()
    if (plain.isBlank()) return ParsedAlert(preamble = "", sections = emptyList())

    val lines = plain.split('\n')
    val preambleBuf = StringBuilder()
    val sections = mutableListOf<AlertSection>()
    var currentHeader: String? = null
    var currentBlocks = mutableListOf<MutableList<String>>()
    var currentBlock: MutableList<String>? = null
    // When true, the active block was opened by a sub-header (e.g. "✅ Best to BUY")
    // and subsequent bullet lines should *attach* to it rather than start their
    // own blocks. The flag is cleared when another sub-header, a blank line, or
    // a new top-level section ends the grouping.
    var subHeaderBlockActive = false

    fun startNewBlock() {
        currentBlock = mutableListOf()
        currentBlocks.add(currentBlock!!)
        subHeaderBlockActive = false
    }
    fun closeSection() {
        currentHeader?.let { hdr ->
            val cleaned = currentBlocks
                .map { AlertBlock(it.toList()) }
                .filter { it.lines.any { l -> l.isNotBlank() } }
            sections.add(AlertSection(header = hdr, blocks = cleaned))
        }
        currentHeader = null
        currentBlocks = mutableListOf()
        currentBlock = null
        subHeaderBlockActive = false
    }

    for (raw in lines) {
        val line = raw.trimEnd()
        val isTopLevelHeader = TOP_LEVEL_HEADER_REGEX.matches(line.trimStart()) &&
                line == line.trimStart()  // header must NOT be indented
        if (isTopLevelHeader) {
            closeSection()
            currentHeader = line.trim().removeSuffix(":")
            startNewBlock()
            continue
        }
        if (currentHeader == null) {
            // Pre-section preamble.
            if (preambleBuf.isNotEmpty()) preambleBuf.append('\n')
            preambleBuf.append(line)
            continue
        }
        // We're inside a section. Decide whether this line starts a new block.
        val trimmed = line.trim()
        val looksLikeNewBullet = trimmed.startsWith("• ") ||
                trimmed.startsWith("- ") ||
                Regex("""^[A-Z]{1,6}\b.*""").containsMatchIn(trimmed) && line.startsWith("  ") && !line.startsWith("    ")
        val isBlank = trimmed.isEmpty()
        val isSubHeader = SUB_HEADER_REGEX.matches(trimmed) && line != line.trimStart()

        when {
            isBlank -> {
                if (currentBlock?.isNotEmpty() == true) startNewBlock()
            }
            isSubHeader -> {
                // Indented sub-headers (e.g. "  ✅ Best to BUY ...:") start a
                // new visual block so the ✅/❌ pair renders as two cards.
                // Mark the block as sub-header-led so the bullets that
                // follow attach to it instead of fragmenting.
                if (currentBlock?.isNotEmpty() == true) startNewBlock()
                currentBlock!!.add(line)
                subHeaderBlockActive = true
            }
            looksLikeNewBullet -> {
                // When the current block is led by a sub-header, append the
                // bullet to it (it's a child row of that sub-group). Only
                // start a fresh block when there is no active sub-header.
                if (currentBlock?.isNotEmpty() == true && !subHeaderBlockActive) startNewBlock()
                currentBlock!!.add(line)
            }
            else -> {
                currentBlock!!.add(line)
            }
        }
    }
    closeSection()

    return ParsedAlert(
        preamble = preambleBuf.toString().trim(),
        sections = sections
    )
}

@Composable
private fun AlertSectionGroup(section: AlertSection, initiallyExpanded: Boolean) {
    var expanded by remember(section.header) { mutableStateOf(initiallyExpanded) }
    val nBlocks = section.blocks.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                section.header,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            if (nBlocks > 0) {
                Text(
                    "$nBlocks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 6.dp, top = 2.dp)) {
                section.blocks.forEach { block ->
                    AlertBlockCard(block = block)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun AlertBlockCard(block: AlertBlock) {
    if (block.lines.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            block.lines.forEachIndexed { idx, l ->
                if (idx > 0) Spacer(modifier = Modifier.height(2.dp))
                Text(
                    htmlToAnnotated(l),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/**
 * Convert an HTML body (with <b> and <br/> tags emitted by
 * DailyRecommendationWorker.toRichHtml) into an AnnotatedString that
 * preserves bold styling. Plain-text strings pass through unchanged.
 */
private fun htmlToAnnotated(body: String): androidx.compose.ui.text.AnnotatedString {
    val spanned = androidx.core.text.HtmlCompat.fromHtml(
        body,
        androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
    )
    val text = spanned.toString().trimEnd()
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val spans = spanned.getSpans(0, spanned.length, android.text.style.StyleSpan::class.java)
        for (span in spans) {
            if (span.style == android.graphics.Typeface.BOLD ||
                span.style == android.graphics.Typeface.BOLD_ITALIC) {
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span).coerceAtMost(text.length)
                if (start in 0 until end) {
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
                        start,
                        end
                    )
                }
            }
        }
    }
}
