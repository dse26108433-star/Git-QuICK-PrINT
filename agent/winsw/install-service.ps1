<#
  Installs the XeoGo agent as a Windows service on the Xerox center PC.
  It then starts by itself whenever the PC starts, even if nobody logs in.

  HOW TO RUN
    1. Put these files together in C:\CampusPrintAgent :
         print-agent.jar  (from agent\target after "mvn package")
         install-service.ps1, uninstall-service.ps1, service-template.xml,
         run-agent-console.bat, list-printers.bat
    2. Start menu -> type PowerShell -> right-click -> "Run as administrator"
    3. Type (with YOUR values):
         cd C:\CampusPrintAgent
         powershell -ExecutionPolicy Bypass -File .\install-service.ps1 `
           -BackendUrl "https://your-backend-address" `
           -AgentId "paste-agent_id-here" `
           -AgentSecret "paste-agent_secret-here"
#>
param(
    [Parameter(Mandatory = $true)][string]$BackendUrl,
    [Parameter(Mandatory = $true)][string]$AgentId,
    [Parameter(Mandatory = $true)][string]$AgentSecret
)
$ErrorActionPreference = 'Stop'

function Say($text) { Write-Host "==> $text" -ForegroundColor Cyan }

# Runs a normal program (java, WinSW). Windows PowerShell would otherwise treat
# anything the program prints on its error stream as a fatal error.
function Run-Program([string]$file, [string[]]$arguments) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $file @arguments 2>&1 | ForEach-Object { "$_" } }
    finally { $ErrorActionPreference = $old }
}

# --- 1. Administrator? ------------------------------------------------------
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Please open PowerShell with right-click -> 'Run as administrator', then run this again."
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

# --- 2. The program file ----------------------------------------------------
$jar = Join-Path $here 'print-agent.jar'
if (-not (Test-Path $jar)) {
    $built = Join-Path $here '..\target\print-agent.jar'
    if (Test-Path $built) { Copy-Item $built $jar }
    else { throw "print-agent.jar is missing. Build it with 'mvn package' in the agent folder and copy it here." }
}

# --- 3. Java ----------------------------------------------------------------
$javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    throw "Java was not found. Install Java 17 or newer (for example Eclipse Temurin 21) and tick 'Add to PATH'. Then open a NEW PowerShell window and run this again."
}
$java = $javaCmd.Source
$versionLine = (Run-Program $java @('-version') | Select-Object -First 1)
Say "Java: $versionLine"
if ($versionLine -match '"(1\.\d|9|10|11|12|13|14|15|16)[\."]') {
    throw "Java 17 or newer is needed. Found: $versionLine"
}

# --- 4. WinSW (the small tool that turns a program into a service) ----------
$exe = Join-Path $here 'print-agent.exe'
if (-not (Test-Path $exe)) {
    Say "Downloading WinSW..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -OutFile $exe `
        -Uri 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe'
}

# --- 5. Settings file (agent.yml), readable only by SYSTEM and admins -------
$yml = Join-Path $here 'agent.yml'
$content = @"
backendUrl: "$($BackendUrl.TrimEnd('/'))"
agentId: "$($AgentId.Trim())"
agentSecret: "$($AgentSecret.Trim())"
pollIntervalSeconds: 3
heartbeatSeconds: 20
workDir: "C:/ProgramData/CampusPrintAgent"
maxFileSizeBytes: 52428800
tempRetentionMinutes: 60
pickupCodeOnPage: true
coverSheetMinSheets: 0
printStrategy: "pdfbox"
externalToolPath: "C:/Program Files/SumatraPDF/SumatraPDF.exe"
externalToolTimeoutSeconds: 180
verifyViaSpooler: true
spoolerPollSeconds: 3
"@
[IO.File]::WriteAllText($yml, $content, (New-Object System.Text.UTF8Encoding $false))
icacls $yml /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
Say "Wrote $yml"

# --- 6. Service definition -------------------------------------------------
$xml = (Get-Content (Join-Path $here 'service-template.xml') -Raw).Replace('JAVA_EXE', $java)
[IO.File]::WriteAllText((Join-Path $here 'print-agent.xml'), $xml, (New-Object System.Text.UTF8Encoding $false))
New-Item -ItemType Directory -Force 'C:\ProgramData\CampusPrintAgent' | Out-Null

# --- 7. Install and start ---------------------------------------------------
if (Get-Service -Name 'CampusPrintAgent' -ErrorAction SilentlyContinue) {
    Say "Old service found: removing it first"
    Run-Program $exe @('stop') | Out-Null
    Run-Program $exe @('uninstall') | Out-Null
    Start-Sleep -Seconds 2
}
Say "Installing the service"
Run-Program $exe @('install')
Say "Starting the service"
Run-Program $exe @('start')
Start-Sleep -Seconds 8
$svc = Get-Service -Name 'CampusPrintAgent'
Write-Host ""
Write-Host "Service status: $($svc.Status)" -ForegroundColor Green
Write-Host "Logs: C:\ProgramData\CampusPrintAgent\logs\agent.log"
Write-Host "Look there for 'Signed in to the backend' and one 'Printer ...' line per printer."
