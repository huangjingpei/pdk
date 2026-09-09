; =====================================================================
; PDK 智播云控 OBS 插件独立安装程序脚本 (Inno Setup 6+)
; =====================================================================
#define MyAppName "智播云控 OBS 拉流插件"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "PDK"
#define MyAppURL "https://pdk.graddu.com"
#define PluginDllPath "build\Release\obs-zhibo-live.dll"
[Setup]
AppId={{D37F89B2-2C9A-4E38-A98B-7F126938BA01}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={code:GetDefaultObsPath}
DirExistsWarning=no
DisableDirPage=no
OutputBaseFilename=智播云控-OBS拉流插件-v{#MyAppVersion}-Setup
Compression=lzma2/ultra64
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64
PrivilegesRequired=admin
WizardStyle=modern
[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Default.isl"
[Files]
; 1. 插件核心 DLL
Source: "{#PluginDllPath}"; DestDir: "{app}\obs-plugins\64bit"; Flags: ignoreversion restartreplace
; 2. 多语言包
Source: "assets\locale\*"; DestDir: "{app}\data\obs-plugins\obs-zhibo-live\locale"; Flags: ignoreversion recursesubdirs createallsubdirs
; 3. (可选) 微软 VC++ 2015-2022 x64 运行库
; Source: "redist\VC_redist.x64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall
[Run]
; 静默安装 VC++ 运行库 (如果本地未安装)
; Filename: "{tmp}\VC_redist.x64.exe"; Parameters: "/install /quiet /norestart"; Flags: runhidden waituntilterminated
[Code]
// 自动从注册表探测 OBS Studio 64位安装目录
function GetDefaultObsPath(Param: string): string;
var
  InstallPath: string;
begin
  if RegQueryStringValue(HKLM64, 'SOFTWARE\OBS Studio', '', InstallPath) then
  begin
    Result := InstallPath;
  end
  else if RegQueryStringValue(HKLM32, 'SOFTWARE\OBS Studio', '', InstallPath) then
  begin
    Result := InstallPath;
  end
  else
  begin
    Result := 'C:\Program Files\obs-studio';
  end;
end;
// 安装前检查 OBS 是否正在运行
function InitializeSetup(): Boolean;
var
  ErrorCode: Integer;
begin
  Result := True;
  // 通过 tasklist 检查 obs64.exe 是否正在运行
  if Exec('cmd.exe', '/c tasklist /FI "IMAGENAME eq obs64.exe" | findstr /I "obs64.exe"', '', SW_HIDE, ewWaitUntilTerminated, ErrorCode) then
  begin
    if ErrorCode = 0 then
    begin
      MsgBox('检测到 OBS Studio 正在运行中！' #13#10 #13#10 '为了确保插件成功安装，请先保存并关闭 OBS，然后再继续安装。', mbConfirmation, MB_OK);
    end;
  end;
end;