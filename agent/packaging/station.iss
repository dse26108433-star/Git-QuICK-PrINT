; ---------------------------------------------------------------------------
; XeoGo Station - the Windows installer (Inno Setup 6).
; Do not build this by hand: run build-installer.ps1, which first makes
; build\app-image\XeoGo Station (the app with its own Java inside).
;
; The result installs without administrator rights, needs nothing else on
; the PC (no Java), adds Start menu + desktop shortcuts and an uninstaller,
; and opens the app on the last page so the setup wizard can start.
; ---------------------------------------------------------------------------
#ifndef AppVersion
  #define AppVersion "4.3.0"
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
; An update closes the running app first (it holds its files open).
CloseApplications=force
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
WelcomeLabel2=Designed and developed by Vedant Pravin Surve.%n%nThis installs the Xerox center app on this PC.%n%nAfter installing, it opens by itself: connect it to your XeoGo server, tick the printers to use, and paid orders from the student website start printing here automatically.%n%nNothing else needs to be installed.
FinishedHeadingLabel=XeoGo Station is installed
FinishedLabelNoIcons=XeoGo Station is installed.
FinishedLabel=XeoGo Station is installed. It opens now, so you can connect this PC and choose the printers.

[Tasks]
Name: "desktopicon"; Description: "Put a XeoGo icon on the desktop"

[InstallDelete]
; The copy from before the program was renamed, and anything an older version left in the new folder.
Type: filesandordirs; Name: "{localappdata}\Programs\{#OldName}"
Type: files; Name: "{autoprograms}\{#OldName}.lnk"
Type: files; Name: "{autodesktop}\{#OldName}.lnk"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

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

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM ""{#AppExe}"""; Flags: runhidden; RunOnceId: "StopStation"

[Code]
// The program keeps running next to the clock, and holds its files open. Stop it (under its new and its
// old name) before the files are replaced; it is started again on the last page.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM "{#AppExe}"', '', SW_HIDE, ewWaitUntilTerminated, code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM "{#OldExe}"', '', SW_HIDE, ewWaitUntilTerminated, code);
  Sleep(800);
  Result := '';
end;
