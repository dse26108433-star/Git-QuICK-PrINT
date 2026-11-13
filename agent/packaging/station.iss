; ---------------------------------------------------------------------------
; XeoGo Station - the Windows installer (Inno Setup 6).
; Do not build this by hand: run build-installer.ps1, which first makes
; build\app-image\XeoGo Station (the app with its own Java inside, and in
; "server" the programs that let this PC be the XeoGo server itself: the
; print service, PostgreSQL and the tunnel to the internet).
;
; The result installs without administrator rights, needs nothing else on
; the PC (no Java, no database to install), adds Start menu + desktop
; shortcuts and an uninstaller, and opens the app on the last page so the
; setup wizard can start.
;
; What the Station keeps (settings, the database, the students' files,
; backups, logs) is in the Windows user's own folder
; (%LOCALAPPDATA%\CampusPrint). An update or an uninstall never touches it.
; ---------------------------------------------------------------------------
#ifndef AppVersion
  #define AppVersion "5.0.0"
#endif
#define AppName "XeoGo Station"
#define AppExe  "XeoGo Station.exe"
; What the program was called before version 4.3 (an update removes that copy; its settings are kept).
#define OldName "Campus Print Station"
#define OldExe  "Campus Print Station.exe"

[Setup]
; Never change AppId: it is how an update finds the installed copy.
AppId={{6E1B7C3A-2F4D-4C8E-9A51-3D7F0B2E8C41}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Vedant Pravin Surve
AppCopyright=(c) 2026 Vedant Pravin Surve
AppComments=Prints paid student orders on the Xerox center printers.
DefaultDirName={localappdata}\Programs\{#AppName}
; Always the folder above (never the old program's folder, which is removed below).
UsePreviousAppDir=no
DefaultGroupName=XeoGo
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyPage=yes
DisableWelcomePage=no
PrivilegesRequired=lowest
OutputDir=out
OutputBaseFilename=XeoGoStation-Setup-{#AppVersion}
SetupIconFile=xeogo.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
WizardStyle=modern
WizardImageFile=wizard-100.bmp,wizard-200.bmp
WizardSmallImageFile=wizard-small-100.bmp,wizard-small-200.bmp
Compression=lzma2/ultra64
SolidCompression=yes
; An update closes the running app first (it holds its files open): see PrepareToInstall.
CloseApplications=no
RestartApplications=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
VersionInfoVersion={#AppVersion}
VersionInfoDescription={#AppName} setup
VersionInfoCompany=Vedant Pravin Surve

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel1=XeoGo Station
WelcomeLabel2=Designed and developed by Vedant Pravin Surve.%n%nThis installs the Xerox center app on this PC.%n%nAfter installing, it opens by itself. This PC can be the XeoGo server itself (the print service and its database start together with the app, every time), or it can connect to a XeoGo server on the internet. Then tick the printers to use, and paid orders start printing here automatically.%n%nNothing else needs to be installed. An update keeps all settings, orders and files.
FinishedHeadingLabel=XeoGo Station is installed
FinishedLabelNoIcons=XeoGo Station is installed.
FinishedLabel=XeoGo Station is installed. It opens now, so you can set up this PC and choose the printers.

[Tasks]
Name: "desktopicon"; Description: "Put a XeoGo icon on the desktop"

[InstallDelete]
; The copy from before the program was renamed, and anything an older version left in the new folder.
Type: filesandordirs; Name: "{localappdata}\Programs\{#OldName}"
Type: files; Name: "{autoprograms}\{#OldName}.lnk"
Type: files; Name: "{autodesktop}\{#OldName}.lnk"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\server"

[Files]
Source: "build\app-image\{#AppName}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; Comment: "Counter screen and printing for the Xerox center"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; The app itself switches "Start with Windows" on; uninstalling switches it off.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "CampusPrintStation"; ValueType: none; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#AppExe}"; Description: "Open XeoGo Station now"; Flags: nowait postinstall skipifsilent

[Code]
// The program keeps running next to the clock and holds its files open; when this PC is the XeoGo server, so do
// the database, the print service and the tunnel it started. Before the files are replaced (an update) or
// removed (an uninstall), all of them are stopped:
//   1. the database, properly, so it closes its files (its data is in the user's own folder and stays);
//   2. the Station (under its new and its old name);
//   3. whatever is still running from the program's own folder. Never anything else on the PC.
procedure StopDatabase(data: String);
var
  code: Integer;
  tool: String;
begin
  tool := ExpandConstant('{app}\server\pgsql\bin\pg_ctl.exe');
  if FileExists(tool) and FileExists(data + '\postmaster.pid') then
    Exec(tool, 'stop -D "' + data + '" -m fast -w -t 20 -s', '', SW_HIDE, ewWaitUntilTerminated, code);
end;

procedure StopEverything();
var
  code: Integer;
  folder: String;
begin
  StopDatabase(ExpandConstant('{localappdata}\CampusPrint\server\pgdata'));
  StopDatabase(ExpandConstant('{commonappdata}\XeoGo\server\pgdata'));
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM "{#AppExe}"', '', SW_HIDE, ewWaitUntilTerminated, code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM "{#OldExe}"', '', SW_HIDE, ewWaitUntilTerminated, code);
  folder := ExpandConstant('{app}') + '\';
  StringChangeEx(folder, '''', '''''', True);
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$d = ''' + folder + '''; ' +
    'Get-Process | Where-Object { $_.Path -and $_.Path.StartsWith($d, [StringComparison]::OrdinalIgnoreCase) } | Stop-Process -Force"',
    '', SW_HIDE, ewWaitUntilTerminated, code);
  Sleep(1200);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopEverything();
  Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then StopEverything();
end;
