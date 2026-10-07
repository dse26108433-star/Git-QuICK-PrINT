<#
  Builds the XeoGo Station installer: one .exe for the Xerox center PC.
  The app carries its own small Java inside, so the PC needs nothing else.

  Needs (on THIS build computer only): JDK 21, Maven, Inno Setup 6.

      powershell -ExecutionPolicy Bypass -File build-installer.ps1
      powershell -ExecutionPolicy Bypass -File build-installer.ps1 -BackendUrl "https://print.mycollege.in"

  -BackendUrl  builds a copy for one college: the server address is already
               filled in, so staff only type the counter password.
  Result:      packaging\out\XeoGoStation-Setup-<version>.exe
#>
param(
    [string]$BackendUrl = "",
    [string]$Version = "4.3.0",
    [string]$InnoSetup = ""
)
$ErrorActionPreference = 'Stop'
function Say($t) { Write-Host "==> $t" -ForegroundColor Cyan }

$here  = Split-Path -Parent $MyInvocation.MyCommand.Path
$agent = Split-Path -Parent $here
$work  = Join-Path $here 'build'

# --- tools ------------------------------------------------------------------
$jdk = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) { $env:JAVA_HOME }
       else { Split-Path -Parent (Split-Path -Parent (Get-Command jpackage.exe -ErrorAction Stop).Source) }
$iscc = @($InnoSetup,
          "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
          "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
          "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
          "C:\CampusPrintTest\tools\InnoSetup6\ISCC.exe") |
        Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $iscc) { throw "Inno Setup 6 not found. Install it from https://jrsoftware.org/isdl.php or pass -InnoSetup <path to ISCC.exe>." }
Say "JDK: $jdk"
Say "Inno Setup: $iscc"

# --- 1. the program ---------------------------------------------------------
Say "Building the program (Maven)"
Push-Location $agent
try { & mvn -q -B package -DskipTests; if ($LASTEXITCODE -ne 0) { throw "Maven build failed" } }
finally { Pop-Location }
$jar = Join-Path $agent 'target\print-agent.jar'

if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force "$work\input" | Out-Null
Copy-Item $jar "$work\input\"

# --- 2. a small private Java with only what the app uses ---------------------
Say "Finding the Java parts the app needs"
$mods = (& "$jdk\bin\jdeps.exe" --ignore-missing-deps --print-module-deps --multi-release 21 $jar).Trim()
# + local web server, modern HTTPS certificates, Indian date formats
$mods = (($mods -split ',') + @('jdk.httpserver', 'jdk.crypto.ec', 'jdk.localedata') | Sort-Object -Unique) -join ','
Say "Modules: $mods"
& "$jdk\bin\jlink.exe" --add-modules $mods --include-locales=en,en-IN --strip-debug --no-header-files `
    --no-man-pages --compress=zip-6 --output "$work\runtime"
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

# --- 3. the app folder (XeoGo Station.exe + Java + program) ------------
Say "Making the app folder"
$javaOptions = @('-Xmx768m', '-Dfile.encoding=UTF-8')
if ($BackendUrl) { $javaOptions += "-Dcampusprint.backendUrl=$($BackendUrl.TrimEnd('/'))"; Say "Server address built in: $BackendUrl" }
$jpArgs = @('--type', 'app-image', '--name', 'XeoGo Station', '--app-version', $Version,
            '--vendor', 'Vedant Pravin Surve', '--description', 'XeoGo Station',
            '--copyright', '(c) 2026 Vedant Pravin Surve',
            '--icon', "$here\xeogo.ico", '--input', "$work\input", '--main-jar', 'print-agent.jar',
            '--main-class', 'edu.campus.agent.station.StationMain', '--runtime-image', "$work\runtime",
            '--dest', "$work\app-image")
foreach ($o in $javaOptions) { $jpArgs += @('--java-options', $o) }
& "$jdk\bin\jpackage.exe" @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

# --- 4. the installer ---------------------------------------------------------
Say "Making the installer (Inno Setup)"
& $iscc /Q "/DAppVersion=$Version" "$here\station.iss"
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed" }

$out = Join-Path $here "out\XeoGoStation-Setup-$Version.exe"
$mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
Write-Host ""
Write-Host "Installer ready: $out ($mb MB)" -ForegroundColor Green
