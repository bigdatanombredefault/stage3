# collect_env.ps1 — Collect hardware and software environment for §4.1 (Experimental Setup)
# of the Stage 3 project guide.
#
# Run on EACH Windows lab PC before benchmarking:
#   powershell -ExecutionPolicy Bypass -File scripts\collect_env.ps1
#
# Output: benchmarks\results\env_<hostname>.txt
#
# If you get an execution policy error, run this once in PowerShell as Administrator:
#   Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned

$Host.UI.RawUI.WindowTitle = "collect_env — Stage 3"

$hostname = $env:COMPUTERNAME
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$outDir = "benchmarks\results"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = "$outDir\env_$hostname.txt"

function Sep { "-" * 50 }
function Hdr($title) { "`n### $title`n$(Sep)" }

$lines = @()
$lines += "# Lab Environment — Node: $hostname"
$lines += "Collected: $timestamp"

# ── HARDWARE ─────────────────────────────────────────────────────────────────
$lines += Hdr "HARDWARE"

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$lines += "Hostname       : $hostname"
$lines += "CPU model      : $($cpu.Name.Trim())"
$lines += "Physical cores : $($cpu.NumberOfCores)"
$lines += "Logical threads: $($cpu.NumberOfLogicalProcessors)"

$ramBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
$ramGB = [math]::Round($ramBytes / 1GB, 1)
$lines += "RAM total      : $ramGB GB"

$disk = Get-PSDrive -Name C -ErrorAction SilentlyContinue
if ($disk) {
    $totalGB = [math]::Round(($disk.Used + $disk.Free) / 1GB, 1)
    $freeGB  = [math]::Round($disk.Free / 1GB, 1)
    $lines += "Disk (C:)      : $totalGB GB total, $freeGB GB free"
}

# Network — find the active adapter and its link speed
$adapters = Get-NetAdapter -ErrorAction SilentlyContinue |
    Where-Object { $_.Status -eq "Up" -and $_.Virtual -eq $false } |
    Select-Object Name, InterfaceDescription, LinkSpeed, MacAddress
if ($adapters) {
    foreach ($a in $adapters) {
        $lines += "Network adapter: $($a.InterfaceDescription)"
        $lines += "Link speed     : $($a.LinkSpeed)"
        $lines += "MAC            : $($a.MacAddress)"
    }
} else {
    # Fallback: wmic (works without admin)
    $nicInfo = (Get-WmiObject Win32_NetworkAdapterConfiguration |
        Where-Object { $_.IPEnabled }) |
        Select-Object -First 1
    if ($nicInfo) {
        $lines += "Network IP     : $($nicInfo.IPAddress -join ', ')"
        $lines += "Link speed     : run 'wmic nic where NetEnabled=TRUE get Name,Speed' for speed"
    } else {
        $lines += "Network        : could not detect — run: Get-NetAdapter"
    }
}

# ── OS ────────────────────────────────────────────────────────────────────────
$lines += Hdr "OPERATING SYSTEM"

$os = Get-CimInstance Win32_OperatingSystem
$lines += "OS             : $($os.Caption)"
$lines += "OS version     : $($os.Version)"
$lines += "OS build       : $($os.BuildNumber)"
$lines += "Architecture   : $($os.OSArchitecture)"

# ── RUNTIME VERSIONS ─────────────────────────────────────────────────────────
$lines += Hdr "RUNTIME VERSIONS"

# Java
try {
    $javaVer = (java -version 2>&1) | Select-Object -First 1
    $lines += "Java           : $javaVer"
} catch {
    $lines += "Java           : not found on PATH"
}

# Docker
try {
    $dockerVer = docker --version 2>&1
    $lines += "Docker         : $dockerVer"
} catch {
    $lines += "Docker         : not found"
}

# Docker Compose
try {
    $dcVer = docker compose version 2>&1
    $lines += "Docker Compose : $dcVer"
} catch {
    $lines += "Docker Compose : not found"
}

# PowerShell version
$lines += "PowerShell     : $($PSVersionTable.PSVersion)"

# ── RUNNING CONTAINERS ────────────────────────────────────────────────────────
$lines += Hdr "RUNNING CONTAINERS"

try {
    $containers = docker ps --format "table {{.Names}}`t{{.Image}}`t{{.Status}}" 2>&1
    $lines += $containers
} catch {
    $lines += "  docker ps failed — is Docker Desktop running?"
}

# ── MIDDLEWARE VERSIONS ───────────────────────────────────────────────────────
$lines += Hdr "MIDDLEWARE VERSIONS"

# Hazelcast — try pom.xml
$hzVer = "unknown"
foreach ($pom in @("indexing-service\pom.xml", "search-service\pom.xml", "pom.xml")) {
    if (Test-Path $pom) {
        $match = Select-String -Path $pom -Pattern "hazelcast" -Context 0,1 |
            ForEach-Object { $_.Context.PostContext } |
            Where-Object { $_ -match "<version>" } |
            Select-Object -First 1
        if ($match) {
            $hzVer = $match.Trim() -replace "<[^>]+>", "" + "  (from $pom)"
            break
        }
    }
}
if ($hzVer -eq "unknown") {
    # Try container logs
    try {
        $indexerCtr = (docker ps --format "{{.Names}}" 2>&1 | Where-Object { $_ -match "index" } | Select-Object -First 1)
        if ($indexerCtr) {
            $hzLog = docker logs $indexerCtr 2>&1 |
                Where-Object { $_ -match "hazelcast" -and ($_ -match "version|starting|\d+\.\d+") } |
                Select-Object -First 1
            if ($hzLog) { $hzVer = $hzLog.Trim() + "  (from container log)" }
        }
    } catch {}
}
$lines += "Hazelcast      : $hzVer"

# ActiveMQ
$amqVer = "not running on this node"
try {
    $amqCtr = docker ps --format "{{.Names}}" 2>&1 | Where-Object { $_ -match "activemq|broker" } | Select-Object -First 1
    if ($amqCtr) {
        $amqImg = docker inspect $amqCtr --format "{{.Config.Image}}" 2>&1
        $amqVer = "$amqImg  (container: $amqCtr)"
    }
} catch {}
$lines += "ActiveMQ       : $amqVer"

# Nginx
$nginxVer = "not running on this node"
try {
    $nginxCtr = docker ps --format "{{.Names}}" 2>&1 | Where-Object { $_ -match "nginx|load.balancer|lb" } | Select-Object -First 1
    if ($nginxCtr) {
        $nv = docker exec $nginxCtr nginx -v 2>&1 | Select-Object -First 1
        $nginxVer = "$nv  (container: $nginxCtr)"
    }
} catch {}
$lines += "Nginx          : $nginxVer"

# ── CLUSTER CONFIG ────────────────────────────────────────────────────────────
$lines += Hdr "CLUSTER CONFIGURATION"

$envFile = ".env"
if (Test-Path $envFile) {
    $lines += "Values from .env:"
    Get-Content $envFile |
        Where-Object { $_ -match "CURRENT_NODE_IP|MASTER_NODE_IP|CLUSTER_NODES|REPLICATION|BACKUP" } |
        ForEach-Object { $lines += "  $_" }
} else {
    $lines += ".env not found — run from the stage3\ directory"
    $lines += "Replication factor  : check REPLICATION_FACTOR in .env (expected: 2)"
    $lines += "Hazelcast backups   : backupCount=2, asyncBackupCount=1  (see hazelcast.xml)"
}

# ── DOCKER STATS SNAPSHOT ─────────────────────────────────────────────────────
$lines += Hdr "DOCKER STATS SNAPSHOT (idle)"
$lines += "(Run this DURING the load test too — paste results into benchmarks\results\docker_stats_$hostname.txt)"
$lines += ""
try {
    $stats = docker stats --no-stream --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}" 2>&1
    $lines += $stats
} catch {
    $lines += "  docker stats unavailable"
}

# ── WRITE OUTPUT ──────────────────────────────────────────────────────────────
$lines | Tee-Object -FilePath $outFile

Write-Host ""
Write-Host "Saved -> $outFile"
Write-Host "Copy this file to the master node and place it in benchmarks\results\"
Write-Host ""
Write-Host "To capture docker stats DURING the load test, run in a separate PowerShell window:"
Write-Host "  docker stats --no-stream --format 'table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}'"
Write-Host "  (paste output into benchmarks\results\docker_stats_$hostname.txt)"
