<#
  Builds the XeoGo Station installer: one .exe for the Xerox center PC.
  The app carries its own small Java inside, so the PC needs nothing else.

  It also carries the XeoGo SERVER, so the Xerox center's PC can be the server itself
  ("This PC is the XeoGo server" in the setup): the print service (backend), its database
  (PostgreSQL) and the tunnel that gives the PC its address on the internet (cloudflared).
  PostgreSQL and cloudflared are downloaded once (about 390 MB) into packaging\cache and
  checked against the checksums below; after that the build works without internet.

  Needs (on THIS build computer only): JDK 21, Maven, Inno Setup 6.

      powershell -ExecutionPolicy Bypass -File build-installer.ps1
      powershell -ExecutionPolicy Bypass -File build-installer.ps1 -BackendUrl "https://print.mycollege.in"
      powershell -ExecutionPolicy Bypass -File build-installer.ps1 -NoServer

  -BackendUrl  for a Xerox center whose server is somewhere else (the cloud): that address is
               already filled in, so staff only type the counter password.
  -NoServer    the small installer without the server programs (printing only, as before 5.0).
  Result:      packaging\out\XeoGoStation-Setup-<version>.exe
#>
param(
    [string]$BackendUrl = "",
    [string]$Version = "5.0.0",
    [string]$InnoSetup = "",
    [switch]$NoServer
)
$ErrorActionPreference = 'Stop'
function Say($t) { Write-Host "==> $t" -ForegroundColor Cyan }

$here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$agent   = Split-Path -Parent $here
$backend = Join-Path (Split-Path -Parent $agent) 'backend'
$work    = Join-Path $here 'build'
$cache   = Join-Path $here 'cache'

# What the server on the Xerox center's PC is made of. A new version: change the address AND the checksum
# (certutil -hashfile <file> SHA256), and try the installer on a PC before handing it out.
# PostgreSQL: only newer 17.x releases. Another major version (18...) cannot open a database made by 17.
$PostgresUrl    = 'https://get.enterprisedb.com/postgresql/postgresql-17.7-1-windows-x64-binaries.zip'
$PostgresSha256 = '2aacc055d9bac49763ac0164759c13866bc0235123d7ffdbb6a6aaa20dc25d9a'
$TunnelUrl      = 'https://github.com/cloudflare/cloudflared/releases/download/2026.10.7/cloudflared-windows-amd64.exe'
$TunnelSha256   = '2493dbc168923bb3e59e5e7716f71deced92992eea9acd6e9d74f84ac7751343'

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

# Maven prints warnings on its error stream; that must not stop this script.
function Maven($folder) {
    Push-Location $folder
    try {
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        & mvn -q -B package -DskipTests 2>&1 | ForEach-Object { "$_" } | Out-Host
        $code = $LASTEXITCODE
        $ErrorActionPreference = $old
        if ($code -ne 0) { throw "Maven build failed in $folder" }
    } finally { Pop-Location }
}

# A file from the internet: fetched once, kept in packaging\cache, and never used unless its checksum is right.
function Fetch($url, $name, $sha256) {
    New-Item -ItemType Directory -Force $cache | Out-Null
    $file = Join-Path $cache $name
    if (-not (Test-Path $file)) {
        Say "Downloading $name (once)"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $tmp = "$file.part"
        & curl.exe -L --fail --silent --show-error -o $tmp $url
        if ($LASTEXITCODE -ne 0) { throw "Could not download $url" }
        Move-Item -Force $tmp $file
    }
    $got = (Get-FileHash -Algorithm SHA256 $file).Hash.ToLower()
    if ($got -ne $sha256.ToLower()) {
        Remove-Item -Force $file
        throw "$name is not the file that was expected (checksum $got). It was deleted; run the build again. If it happens again, the download address or the checksum in this script is out of date."
    }
    return $file
}

# --- 1. the program ---------------------------------------------------------
Say "Building the Station (Maven)"
Maven $agent
$jar = Join-Path $agent 'target\print-agent.jar'

if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force "$work\input" | Out-Null
Copy-Item $jar "$work\input\"

# --- 2. the server programs (unless -NoServer) --------------------------------
$server = Join-Path $work 'server'
if (-not $NoServer) {
    Say "Building the print service (Maven)"
    Maven $backend
    New-Item -ItemType Directory -Force $server | Out-Null
    Copy-Item (Join-Path $backend 'target\print-server-1.0.0.jar') (Join-Path $server 'print-server.jar')

    Say "PostgreSQL: the parts the Station needs"
    $zip = Fetch $PostgresUrl 'postgresql-windows-x64-binaries.zip' $PostgresSha256
    # Kept: the database itself, the tools to make, start, stop, back up and restore it. Left out: pgAdmin,
    # StackBuilder, documentation, translations, developer files (about 900 MB of the 960).
    $keepExe = @('postgres.exe','initdb.exe','pg_ctl.exe','pg_dump.exe','pg_restore.exe','psql.exe','pg_isready.exe','createdb.exe','dropdb.exe')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $n = 0
        foreach ($e in $archive.Entries) {
            if (-not $e.FullName.StartsWith('pgsql/') -or $e.FullName.EndsWith('/')) { continue }
            $rel = $e.FullName.Substring(6)
            $parts = $rel.Split('/')
            $base = $parts[$parts.Length - 1].ToLower()
            # (no "switch" here: inside a switch, "continue" would not skip to the next file)
            $keep = $false
            if ($parts[0] -eq 'bin') {
                $keep = -not (($base.EndsWith('.exe') -and ($keepExe -notcontains $base)) -or $base.StartsWith('wx') -or $base.EndsWith('.pdb'))
            } elseif ($parts[0] -eq 'lib') {
                $keep = $base.EndsWith('.dll')
            } elseif ($parts[0] -eq 'share') {
                $keep = -not ($parts.Length -gt 1 -and ($parts[1] -eq 'doc' -or $parts[1] -eq 'locale'))
            }
            if (-not $keep) { continue }
            $out = Join-Path (Join-Path $server 'pgsql') ($rel -replace '/', '\')
            New-Item -ItemType Directory -Force (Split-Path -Parent $out) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
            $n++
        }
    } finally { $archive.Dispose() }
    if ($n -lt 500 -or $n -gt 3000 -or -not (Test-Path "$server\pgsql\bin\postgres.exe") -or -not (Test-Path "$server\pgsql\share\postgres.bki") `
            -or (Test-Path "$server\pgsql\pgAdmin 4")) {
        throw "PostgreSQL was not unpacked as expected ($n files)."
    }

    Say "cloudflared: the tunnel to the internet"
    Copy-Item (Fetch $TunnelUrl 'cloudflared-windows-amd64.exe' $TunnelSha256) (Join-Path $server 'cloudflared.exe')
}

# --- 3. a small private Java with only what the programs use ------------------
Say "Finding the Java parts the app needs"
$mods = (& "$jdk\bin\jdeps.exe" --ignore-missing-deps --print-module-deps --multi-release 21 $jar).Trim()
# + local web server, modern HTTPS certificates, Indian date formats, and the part the PDF library uses to let
#   go of files (without it every start wrote "Unmapping is not supported" as an error into the log)
$extra = @('jdk.httpserver', 'jdk.crypto.ec', 'jdk.localedata', 'jdk.unsupported', 'jdk.management')
if (-not $NoServer) {
    # ...and what the print service (Spring Boot, Hibernate, the PostgreSQL driver) uses. It runs on this same
    # Java (runtime\bin\java.exe), started by the Station.
    $extra += @('java.compiler', 'java.instrument', 'java.logging', 'java.management', 'java.management.rmi', 'java.naming',
                'java.net.http', 'java.prefs', 'java.rmi', 'java.scripting', 'java.security.jgss', 'java.security.sasl',
                'java.sql', 'java.sql.rowset', 'java.transaction.xa', 'java.xml', 'java.xml.crypto', 'jdk.crypto.cryptoki',
                'jdk.jfr', 'jdk.net', 'jdk.zipfs', 'jdk.charsets')
}
$mods = (($mods -split ',') + $extra | Sort-Object -Unique) -join ','
Say "Modules: $mods"
& "$jdk\bin\jlink.exe" --add-modules $mods --include-locales=en,en-IN --strip-debug --no-header-files `
    --no-man-pages --compress=zip-6 --output "$work\runtime"
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
if (-not (Test-Path "$work\runtime\bin\java.exe")) { throw "The private Java has no java.exe: the print service could not be started with it." }

# --- 4. the app folder (XeoGo Station.exe + Java + program) ------------
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
if (-not $NoServer) {
    # next to the Station's own program: ...\XeoGo Station\server (where LocalStack.home() looks)
    Copy-Item -Recurse $server "$work\app-image\XeoGo Station\server"
}

# --- 5. the installer ---------------------------------------------------------
Say "Making the installer (Inno Setup)"
& $iscc /Q "/DAppVersion=$Version" "$here\station.iss"
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed" }

$out = Join-Path $here "out\XeoGoStation-Setup-$Version.exe"
$mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
Write-Host ""
Write-Host "Installer ready: $out ($mb MB)$(if ($NoServer) { ' - printing only, without the server programs' })" -ForegroundColor Green
