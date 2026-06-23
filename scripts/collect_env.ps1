# collect_env.ps1 - Collect hardware and software environment for section 4.1
# (Experimental Setup) of the Stage 3 project guide.
#
# Run on EACH Windows lab PC before benchmarking:
#   powershell -ExecutionPolicy Bypass -File scripts\collect_env.ps1
#
# Output: benchmarks\results\env_<COMPUTERNAME>.txt
#
# If you get an execution policy error, run once in PowerShell as Administrator:
#   Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned

$hostname  = $env:COMPUTERNAME
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$outDir    = "benchmarks\results"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile   = "$outDir\env_$hostname.txt"

function Sep   { return "-" * 50 }
function Hdr($title) { return "`n### $title`n$(Sep)" }

$lines = @()
$lines += "# Lab Environment - Node: $hostname"
$lines += "Collected: $timestamp"

# ---------- HARDWARE ---------------------------------------------------------
$lines += Hdr "HARDWARE"

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$lines += "Hostname        : $hostname"
$lines += "CPU model       : $($cpu.Name.Trim())"
$lines += "Physical cores  : $($cpu.NumberOfCores)"
$lines += "Logical threads : $($cpu.NumberOfLogicalProcessors)"

$ramBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
$ramGB    = [math]::Round($ramBytes / 1GB, 1)
$lines += "RAM total       : $ramGB GB"

$disk = Get-PSDrive -Name C -ErrorAction SilentlyContinue
if ($disk) {
    $totalGB = [math]::Round(($disk.Used + $disk.Free) / 1GB, 1)
    $freeGB  = [math]::Round($disk.Free / 1GB, 1)
    $lines += "Disk (C:)       : $totalGB GB total, $freeGB GB free"
}

# Network - active physical adapter and link speed
$adapters = Get-NetAdapter -ErrorAction SilentlyContinue |
    Where-Object { $_.Status -eq "Up" -and $_.Virtual -eq $false } |
    Select-Object Name, InterfaceDescription, LinkSpeed, MacAddress

if ($adapters) {
    foreach ($a in $adapters) {
        $lines += "Network adapter : $($a.InterfaceDescription)"
        $lines += "Link speed      : $($a.LinkSpeed)"
        $lines += "MAC             : $($a.MacAddress)"
    }
} else {
    $nicInfo = (Get-WmiObject Win32_NetworkAdapterConfiguration |
        Where-Object { $_.IPEnabled }) | Select-Object -First 1
    if ($nicInfo) {
        $lines += "Network IP      : $($nicInfo.IPAddress -join ', ')"
        $lines += "Link speed      : run this in PowerShell: wmic nic where NetEnabled=TRUE get Name,Speed"
    } else {
        $lines += "Network         : could not detect - run: Get-NetAdapter"
    }
}

# ---------- OPERATING SYSTEM -------------------------------------------------
$lines += Hdr "OPERATING SYSTEM"

$os = Get-CimInstance Win32_OperatingSystem
$lines += "OS              : $($os.Caption)"
$lines += "OS version      : $($os.Version)"
$lines += "OS build        : $($os.BuildNumber)"
$lines += "Architecture    : $($os.OSArchitecture)"

# ---------- RUNTIME VERSIONS -------------------------------------------------
$lines += Hdr "RUNTIME VERSIONS"

try {
    $javaVer = (java -version 2>&1) | Select-Object -First 1
    $lines += "Java            : $javaVer"
} catch {
    $lines += "Java            : not found on PATH"
}

try {
    $dockerVer = (docker --version 2>&1)
    $lines += "Docker          : $dockerVer"
} catch {
    $lines += "Docker          : not found"
}

try {
    $dcVer = (docker compose version 2>&1)
    $lines += "Docker Compose  : $dcVer"
} catch {
    $lines += "Docker Compose  : not found"
}

$lines += "PowerShell      : $($PSVersionTable.PSVersion)"

# ---------- RUNNING CONTAINERS -----------------------------------------------
$lines += Hdr "RUNNING CONTAINERS"

try {
    $containers = (docker ps --format "table {{.Names}}`t{{.Image}}`t{{.Status}}" 2>&1)
    $lines += $containers
} catch {
    $lines += "  docker ps failed - is Docker Desktop running?"
}

# ---------- MIDDLEWARE VERSIONS -----------------------------------------------
$lines += Hdr "MIDDLEWARE VERSIONS"

# Hazelcast - try pom.xml files first
$hzVer = "unknown"
foreach ($pom in @("indexing-service\pom.xml", "search-service\pom.xml", "pom.xml")) {
    if (Test-Path $pom) {
        $found = Select-String -Path $pom -Pattern "hazelcast" -Context 0,1 |
            ForEach-Object { $_.Context.PostContext } |
            Where-Object { $_ -match "<version>" } |
            Select-Object -First 1
        if ($found) {
            $hzVer = ($found.Trim() -replace "<[^>]+>", "") + "  (from $pom)"
            break
        }
    }
}
if ($hzVer -eq "unknown") {
    # Try container logs as fallback
    try {
        $idxCtr = (docker ps --format "{{.Names}}" 2>&1 |
            Where-Object { $_ -match "index" } | Select-Object -First 1)
        if ($idxCtr) {
            $hzLog = docker logs $idxCtr 2>&1 |
                Where-Object { $_ -match "hazelcast" -and ($_ -match "version|starting|\d+\.\d+") } |
                Select-Object -First 1
            if ($hzLog) { $hzVer = $hzLog.Trim() + "  (from container log)" }
        }
    } catch {}
}
$lines += "Hazelcast       : $hzVer"

# ActiveMQ
$amqVer = "not running on this node"
try {
    $amqCtr = (docker ps --format "{{.Names}}" 2>&1 |
        Where-Object { $_ -match "activemq|broker" } | Select-Object -First 1)
    if ($amqCtr) {
        $amqImg = (docker inspect $amqCtr --format "{{.Config.Image}}" 2>&1)
        $amqVer = "$amqImg  (container: $amqCtr)"
    }
} catch {}
$lines += "ActiveMQ        : $amqVer"

# Nginx
$nginxVer = "not running on this node"
try {
    $ngxCtr = (docker ps --format "{{.Names}}" 2>&1 |
        Where-Object { $_ -match "nginx|load.balancer|lb" } | Select-Object -First 1)
    if ($ngxCtr) {
        $nv = (docker exec $ngxCtr nginx -v 2>&1 | Select-Object -First 1)
        $nginxVer = "$nv  (container: $ngxCtr)"
    }
} catch {}
$lines += "Nginx           : $nginxVer"

# ---------- CLUSTER CONFIGURATION --------------------------------------------
$lines += Hdr "CLUSTER CONFIGURATION"

if (Test-Path ".env") {
    $lines += "Values from .env:"
    Get-Content ".env" |
        Where-Object { $_ -match "CURRENT_NODE_IP|MASTER_NODE_IP|CLUSTER_NODES|REPLICATION|BACKUP" } |
        ForEach-Object { $lines += "  $_" }
} else {
    $lines += ".env not found - run from the stage3 directory"
    $lines += "Replication factor : check REPLICATION_FACTOR in .env (expected: 2)"
    $lines += "Hazelcast backups  : backupCount=2, asyncBackupCount=1  (see hazelcast.xml)"
}

# ---------- DOCKER STATS SNAPSHOT --------------------------------------------
$lines += Hdr "DOCKER STATS SNAPSHOT (idle baseline)"
$lines += "Run this again in a separate window DURING the load test and save the output."
$lines += ""
try {
    $stats = (docker stats --no-stream `
        --format "table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}" 2>&1)
    $lines += $stats
} catch {
    $lines += "  docker stats unavailable"
}

# ---------- WRITE OUTPUT -----------------------------------------------------
$lines | Tee-Object -FilePath $outFile

Write-Host ""
Write-Host "Saved -> $outFile"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Copy $outFile to the master PC into benchmarks\results\"
Write-Host "  2. During the load test, open a second PowerShell window and run:"
Write-Host "       docker stats --no-stream --format 'table {{.Name}}`t{{.CPUPerc}}`t{{.MemUsage}}`t{{.NetIO}}'"
Write-Host "     Save that output to: benchmarks\results\docker_stats_$hostname.txt"
