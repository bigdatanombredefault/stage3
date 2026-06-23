# load_test.ps1 — HTTP load test for Stage 3 benchmark (Windows replacement for wrk)
#
# Sends concurrent HTTP GET requests using .NET HttpClient + PowerShell Runspaces.
# Outputs avg, p50, p90, p95, p99, max latency and requests/sec — same metrics as wrk.
#
# Usage (from PowerShell):
#   powershell -ExecutionPolicy Bypass -File scripts\load_test.ps1 `
#     -Url "http://192.168.1.10/search?q=the&limit=10" `
#     -Connections 50 -Duration 30
#
# Called automatically by benchmark.sh when wrk is not available.

param(
    [Parameter(Mandatory=$true)]
    [string]$Url,

    [int]$Connections = 50,
    [int]$Duration    = 30
)

Write-Host "=== Stage 3 Load Test ==="
Write-Host "URL        : $Url"
Write-Host "Connections: $Connections"
Write-Host "Duration   : ${Duration}s"
Write-Host ""

# Shared concurrent collections
$latencies = [System.Collections.Concurrent.ConcurrentBag[double]]::new()
$errCount  = [System.Collections.Concurrent.ConcurrentBag[int]]::new()

$stopAt = [DateTime]::UtcNow.AddSeconds($Duration)

# Worker script — each runspace runs this in a tight loop until $stopAt
$worker = {
    param($url, $stopAt, $latencies, $errCount)

    Add-Type -AssemblyName System.Net.Http
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.MaxConnectionsPerServer = 1
    $client  = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [System.TimeSpan]::FromSeconds(10)

    while ([DateTime]::UtcNow -lt $stopAt) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $resp = $client.GetAsync($url).GetAwaiter().GetResult()
            $sw.Stop()
            if ($resp.IsSuccessStatusCode) {
                $latencies.Add($sw.Elapsed.TotalMilliseconds)
            } else {
                $errCount.Add(1)
            }
            $resp.Dispose()
        } catch {
            $sw.Stop()
            $errCount.Add(1)
        }
    }
    $client.Dispose()
}

# Create a runspace pool with $Connections max threads
$pool = [System.Management.Automation.Runspaces.RunspacePool]::CreateRunspacePool(1, $Connections)
$pool.Open()

$handles = @()
$startTime = [DateTime]::UtcNow

for ($i = 0; $i -lt $Connections; $i++) {
    $ps = [PowerShell]::Create()
    $ps.RunspacePool = $pool
    [void]$ps.AddScript($worker)
    [void]$ps.AddArgument($Url)
    [void]$ps.AddArgument($stopAt)
    [void]$ps.AddArgument($latencies)
    [void]$ps.AddArgument($errCount)
    $handles += [PSCustomObject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
}

# Show a progress ticker while the test runs
$deadline = $stopAt.AddSeconds(3)
while ([DateTime]::UtcNow -lt $stopAt) {
    $remaining = [math]::Max(0, ($stopAt - [DateTime]::UtcNow).TotalSeconds)
    $done      = $latencies.Count
    Write-Host -NoNewline "`r  Running... ${remaining}s left | requests so far: $done   "
    Start-Sleep -Milliseconds 500
}
Write-Host -NoNewline "`r  Waiting for last requests to finish...                      "
Start-Sleep -Seconds 3

# Collect all runspaces
foreach ($h in $handles) {
    try { [void]$h.PS.EndInvoke($h.Handle) } catch {}
    $h.PS.Dispose()
}
$pool.Close()
$pool.Dispose()

Write-Host ""

$elapsed = ([DateTime]::UtcNow - $startTime).TotalSeconds

# ── Statistics ─────────────────────────────────────────────────────────────
$sorted = $latencies | Sort-Object
$total  = $sorted.Count
$errors = $errCount.Count

if ($total -lt 1) {
    Write-Host "No successful requests. Check that $Url is reachable."
    exit 1
}

function Percentile($arr, $p) {
    $idx = [int][math]::Ceiling($arr.Count * $p / 100) - 1
    $idx = [math]::Max(0, [math]::Min($idx, $arr.Count - 1))
    return $arr[$idx]
}

$avg  = ($sorted | Measure-Object -Average).Average
$p50  = Percentile $sorted 50
$p75  = Percentile $sorted 75
$p90  = Percentile $sorted 90
$p95  = Percentile $sorted 95
$p99  = Percentile $sorted 99
$max  = $sorted[-1]
$rps  = [math]::Round($total / $elapsed, 2)

Write-Host ""
Write-Host "Latency Distribution"
Write-Host ("  {0,-6} {1,10:F2} ms" -f "50%",  $p50)
Write-Host ("  {0,-6} {1,10:F2} ms" -f "75%",  $p75)
Write-Host ("  {0,-6} {1,10:F2} ms" -f "90%",  $p90)
Write-Host ("  {0,-6} {1,10:F2} ms" -f "95%",  $p95)
Write-Host ("  {0,-6} {1,10:F2} ms" -f "99%",  $p99)
Write-Host ""
Write-Host ("  {0,-25} {1,10:F2} ms" -f "Avg latency:",  $avg)
Write-Host ("  {0,-25} {1,10:F2} ms" -f "p95 latency:",  $p95)
Write-Host ("  {0,-25} {1,10:F2} ms" -f "p99 latency:",  $p99)
Write-Host ("  {0,-25} {1,10:F2} ms" -f "Max latency:",  $max)
Write-Host ("  {0,-25} {1,10}"       -f "Total requests:", $total)
Write-Host ("  {0,-25} {1,10:F2}"    -f "Requests/sec:",  $rps)
Write-Host ("  {0,-25} {1,10}"       -f "Errors:",        $errors)
Write-Host ""
Write-Host "Duration: $([math]::Round($elapsed,1))s | Connections: $Connections"
