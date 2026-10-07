# XeoGo Station - turns Word files into PDFs with the Microsoft Word that is installed on this PC.
#
# The Station (Java, WordEngine) starts this once and talks to it line by line:
#
#   -> CONVERT <id> <file to read, base64> <PDF to write, base64>
#   <- OK <id> <pages>          or          ERR <id> <CODE> <message, base64>
#   -> PING                     <- PONG     (twice a second while there is no work)
#   -> QUIT                     Word is closed and this ends
#
# First it says "READY <Word version> <Word's process id>", or "NOWORD <message, base64>" and ends.
# CODE: PASSWORD (the file is locked), FAILED (Word cannot open or save this file), ENGINE (Word
# itself stopped answering: this ends, and the Station starts a fresh one).
#
# Word runs hidden and only for this: no alerts, no macros (whatever the file says), nothing
# fetched from links, nothing printed, nothing saved back. Files are opened read-only and what is
# written is the document as it prints, without tracked changes or comments.
#
# The shop's own Word is never touched: this is a second Word, started for automation. If Windows
# ever hands a document somebody opened by hand to this hidden Word, it is shown to them at once
# and left to them (see Give-Away); the work goes on in a new one.
#
# When the Station goes away (its end of the pipe closes), Word is closed too.

$ErrorActionPreference = 'Stop'
try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [Console]::InputEncoding = $utf8
    [Console]::OutputEncoding = $utf8
} catch { }

function Say([string]$line) {
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
}
function To-B64([string]$s) {
    if ($s -eq $null) { $s = '' }
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s))
}
function From-B64([string]$s) {
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
}
function Note([string]$line) {
    try { [Console]::Error.WriteLine((Get-Date).ToString('HH:mm:ss') + ' ' + $line) } catch { }
}

$script:word = $null
$script:wordPid = 0

function Start-Word {
    $before = @(Get-Process WINWORD -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    $w = New-Object -ComObject Word.Application
    try { $w.Visible = $false } catch { }
    try { $w.DisplayAlerts = 0 } catch { }                    # wdAlertsNone
    try { $w.AutomationSecurity = 3 } catch { }               # msoAutomationSecurityForceDisable: no macros
    try { $w.FeatureInstall = 0 } catch { }                   # never offer to install a missing feature
    # Nothing under Word's Options is changed here: those are the shop's own settings, and Word keeps
    # them for good. Everything above belongs to this one hidden Word and is gone with it.
    $id = 0
    foreach ($p in @(Get-Process WINWORD -ErrorAction SilentlyContinue)) {
        if ($before -notcontains $p.Id) { $id = $p.Id }
    }
    $script:word = $w
    $script:wordPid = $id
}

function Stop-Word {
    Give-Away                                                 # never close a Word that holds somebody's own file
    if ($script:word -eq $null) { return }
    try { $script:word.NormalTemplate.Saved = $true } catch { }
    try { $script:word.Quit([ref]0) } catch { }
    try { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($script:word) } catch { }
    $script:word = $null
    $script:wordPid = 0
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}

# True while Word still answers.
function Word-Alive {
    if ($script:word -eq $null) { return $false }
    try { [void]$script:word.Version; return $true } catch { return $false }
}

# A document somebody opened by hand landed in this hidden Word (or its window was brought up):
# show it and leave this Word to them. The next file gets a Word of its own.
# (Windows does that when no other Word is open: a double-clicked file goes to the Word that is
# already running, even a hidden one. Looked for before every file, twice a second in between,
# and before this Word would be closed.)
function Give-Away {
    if ($script:word -eq $null) { return }
    $theirs = $false
    try { if ($script:word.Documents.Count -gt 0) { $theirs = $true } } catch { }
    try { if ($script:word.Visible) { $theirs = $true } } catch { }
    if (-not $theirs) { return }
    Note 'A document opened by hand is in this Word: it is left to the person at the PC.'
    try { $script:word.DisplayAlerts = -1 } catch { }         # wdAlertsAll: it is their Word now
    try { $script:word.AutomationSecurity = 2 } catch { }     # msoAutomationSecurityByUI: their own macro settings
    try { $script:word.Visible = $true } catch { }
    try { $script:word.UserControl = $true } catch { }        # it stays open when we let go of it
    try { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($script:word) } catch { }
    $script:word = $null
    $script:wordPid = 0
}

function Convert-One([string]$in, [string]$out) {
    Give-Away
    if (-not (Word-Alive)) { Start-Word }
    $doc = $null
    try {
        # Open(FileName, ConfirmConversions, ReadOnly, AddToRecentFiles, PasswordDocument, PasswordTemplate, Revert,
        #      WritePasswordDocument, WritePasswordTemplate, Format, Encoding, Visible, OpenAndRepair, DocumentDirection,
        #      NoEncodingDialog)   A wrong password on purpose: a locked file fails at once instead of asking.
        $doc = $script:word.Documents.Open($in, $false, $true, $false, 'x', 'x', $false, 'x', 'x', 0,
            [Type]::Missing, $false, $false, 0, $true)
        # ExportAsFixedFormat(OutputFileName, ExportFormat = PDF, OpenAfterExport, OptimizeFor = print, Range = all,
        #      From, To, Item = the document without markup, IncludeDocProps, KeepIRM, CreateBookmarks = none,
        #      DocStructureTags, BitmapMissingFonts, UseISO19005_1)
        $doc.ExportAsFixedFormat($out, 17, $false, 0, 0, 1, 1, 0, $false, $false, 0, $false, $true, $false)
        $pages = 0
        try { $pages = [int]$doc.ComputeStatistics(2) } catch { }
        return $pages
    } finally {
        if ($doc -ne $null) {
            try { $doc.Saved = $true } catch { }
            try { $doc.Close([ref]0) } catch { }
            try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($doc) } catch { }
        }
    }
}

try {
    Start-Word
    Say ('READY ' + $script:word.Version + ' ' + $script:wordPid)
} catch {
    Say ('NOWORD ' + (To-B64 $_.Exception.Message))
    exit 2
}

$stop = $false
try {
    while (-not $stop) {
        $line = [Console]::In.ReadLine()
        if ($line -eq $null) { break }                        # the Station is gone
        $parts = $line.Trim().Split(' ')
        if ($parts[0] -eq 'PING') {
            Give-Away
            Say 'PONG'
        } elseif ($parts[0] -eq 'QUIT') {
            $stop = $true
        } elseif ($parts[0] -eq 'CONVERT' -and $parts.Length -ge 4) {
            $id = $parts[1]
            try {
                $in = From-B64 $parts[2]
                $out = From-B64 $parts[3]
                $pages = Convert-One $in $out
                if (-not (Test-Path -LiteralPath $out)) { throw 'Word wrote no PDF.' }
                Say ('OK ' + $id + ' ' + $pages)
            } catch {
                $message = $_.Exception.Message
                $code = 'FAILED'
                if ($message -match 'password|Kennwort|mot de passe|contrase') { $code = 'PASSWORD' }
                if (-not (Word-Alive)) { $code = 'ENGINE' }
                Note ('Could not convert ' + $id + ': ' + $message)
                Say ('ERR ' + $id + ' ' + $code + ' ' + (To-B64 $message))
                if ($code -eq 'ENGINE') { $stop = $true }
            }
        }
    }
} finally {
    Stop-Word
}
