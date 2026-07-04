$ErrorActionPreference = "Continue"
$base = "https://financestreamai-backend.onrender.com/api/v1"
$tickers = "AAPL,MSFT,NVDA,GOOGL,META,AMZN,TSLA,AVGO,CRM,ORCL"

Write-Host "=== Backend live scan test ===" -ForegroundColor Cyan
Write-Host "Base : $base"
Write-Host "Tickers: $tickers"
Write-Host ""

$t0 = Get-Date
try {
    $start = Invoke-RestMethod "$base/scan/async?tickers=$tickers" -TimeoutSec 60
} catch {
    Write-Host "START FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
$jobId = $start.job_id
Write-Host "STARTED job_id=$jobId total=$($start.total_tickers) startup=$(((Get-Date)-$t0).TotalSeconds.ToString('0.0'))s"

$done    = $false
$poll    = 0
$errCnt  = 0
$status  = $null

do {
    Start-Sleep -Milliseconds 2500
    $poll++
    try {
        $status = Invoke-RestMethod "$base/scan/status/$jobId" -TimeoutSec 30
        $elapsed = ((Get-Date) - $t0).TotalSeconds.ToString('0.0')
        if ($status -is [array]) {
            Write-Host "poll #$poll [${elapsed}s]: RESULTS_ARRAY len=$($status.Count)" -ForegroundColor Green
            $done = $true
        } else {
            Write-Host "poll #$poll [${elapsed}s]: status=$($status.status) progress=$($status.progress)"
            if ($status.status -in @("complete","failed")) { $done = $true }
        }
    } catch {
        $errCnt++
        Write-Host "poll #$poll ERROR $errCnt): $($_.Exception.Message)" -ForegroundColor Yellow
    }
} while (-not $done -and $poll -lt 120 -and $errCnt -lt 8)

Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
Write-Host "pollCount=$poll  errCount=$errCnt  totalTime=$(((Get-Date)-$t0).TotalSeconds.ToString('0.0'))s"

if ($status -is [array]) {
    Write-Host "returned $($status.Count) items:"
    foreach ($it in $status) {
        $csps = @($it.csps).Count
        $diag = @($it.diagonals).Count
        $vert = @($it.verticals).Count
        $leap = @($it.long_leaps).Count
        $pcs  = @($it.put_credit_spreads).Count
        "  {0,-6} price=`${1,-8:F2} csps={2} diag={3} vert={4} leaps={5} pcs={6}" -f $it.ticker, $it.price, $csps, $diag, $vert, $leap, $pcs
    }
    $withCsps = ($status | Where-Object { @($_.csps).Count -gt 0 }).Count
    $withPcs  = ($status | Where-Object { @($_.put_credit_spreads).Count -gt 0 }).Count
    Write-Host ""
    Write-Host "Symbols with CSPs: $withCsps / $($status.Count)"
    Write-Host "Symbols with PCS : $withPcs / $($status.Count)"
} else {
    Write-Host "Final status object:"
    $status | ConvertTo-Json -Depth 3
}
