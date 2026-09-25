# Removes the Campus Print agent service. Run PowerShell as administrator.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$exe = Join-Path $here 'print-agent.exe'
if (-not (Get-Service -Name 'CampusPrintAgent' -ErrorAction SilentlyContinue)) {
    Write-Host "The service is not installed."
    exit 0
}
$ErrorActionPreference = 'Continue'
& $exe stop 2>&1 | ForEach-Object { "$_" }
& $exe uninstall 2>&1 | ForEach-Object { "$_" }
Write-Host "Removed. (Logs are still in C:\ProgramData\CampusPrintAgent\logs)"
