; ---------------------------------------------------------------------------
; Campus Print Station - the Windows installer (Inno Setup 6).
; Do not build this by hand: run build-installer.ps1, which first makes
; build\app-image\Campus Print Station (the app with its own Java inside).
;
; The result installs without administrator rights, needs nothing else on
; the PC (no Java), adds Start menu + desktop shortcuts and an uninstaller,
; and opens the app on the last page so the setup wizard can start.
; ---------------------------------------------------------------------------
#ifndef AppVersion
  #define AppVersion "4.1.0"
#endif
#define AppName "Campus Print Station"
#define AppExe  "Campus Print Station.exe"

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
DefaultGroupName=Campus Print
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyPage=yes
DisableWelcomePage=no
PrivilegesRequired=lowest
OutputDir=out
OutputBaseFilename=CampusPrintStation-Setup-{#AppVersion}
SetupIconFile=campus-print.ico
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
WelcomeLabel1=Campus Print Station
WelcomeLabel2=Designed and developed by Vedant Pravin Surve.%n%nThis installs the Xerox center app on this PC.%n%nAfter installing, it opens by itself: connect it to your Campus Print server, tick the printers to use, and paid orders from the student website start printing here automatically.%n%nNothing else needs to be installed.
FinishedHeadingLabel=Campus Print Station is installed
FinishedLabelNoIcons=Campus Print Station is installed.
FinishedLabel=Campus Print Station is installed. It opens now, so you can connect this PC and choose the printers.

[Tasks]
Name: "desktopicon"; Description: "Put a Campus Print icon on the desktop"

[Files]
Source: "build\app-image\{#AppName}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; Comment: "Counter screen and printing for the Xerox center"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; The app itself switches "Start with Windows" on; uninstalling switches it off.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "CampusPrintStation"; ValueType: none; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#AppExe}"; Description: "Open Campus Print Station now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM ""{#AppExe}"""; Flags: runhidden; RunOnceId: "StopStation"
