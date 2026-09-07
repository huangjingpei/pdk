# OBS 版本兼容性与 ABI 防护方案

在 C++ / Qt 插件生态中，**OBS Studio 主程序版本与插件版本不匹配**是导致插件加载失败、功能丢失甚至 OBS 启动崩溃的最核心原因。

本文档深入剖析可能引发版本不匹配的四大技术根因，并给出从**代码编译期防御**、**运行时动态守卫**、**安装包前置校验**到**商业级一体化发版**的完整应对方案。

---

## 一、 为什么会发生版本不匹配？四大技术根因

```
                    ┌──────────────────────────────────────────────┐
                    │      OBS Studio 升级或版本不一致风险点       │
                    └──────────────────────┬───────────────────────┘
                                           │
         ┌──────────────────┬──────────────┴─────┬──────────────────┐
         ▼                  ▼                    ▼                  ▼
  1. 核心 ABI 结构体  2. Qt 主版本断层    3. VC++ 运行时库   4. 32位 vs 64位
     尺寸超标拦截         (Qt5 vs Qt6)         (CRT 缺失)         架构不兼容
```

### 1. 核心 ABI 结构体尺寸限制 (`obs_source_info` 越界拦截)
* **原理**：OBS 的音视频源与滤镜通过 `obs_register_source(struct obs_source_info *info)` 注册进核心引擎。
* **机制**：OBS 底层 `obs.dll` 在注册时，会严格检查传入结构体的大小：
  ```c
  if (size > sizeof(struct obs_source_info)) {
      blog(LOG_WARNING, "Tried to register obs_source_info with size %lu which is more than libobs currently supports (%lu)", size, sizeof(struct obs_source_info));
      return false;
  }
  ```
* **实际案例**：若使用 OBS 31（master 分支）的头文件编译，尾部增加了 `get_dark_icon` 和 `get_light_icon`，使得结构体膨胀至 **424 字节**。当该 DLL 运行在当前主流的 **OBS Studio 30**（结构体上限 408 字节）上时，OBS 底层会**静默拦截并拒绝注册**，导致滤镜或源完全不显示！

### 2. Qt 图形界面主版本断层 (Qt5 与 Qt6 不兼容)
* **OBS 27 及更早版本**：基于 **Qt 5.15** 构建；
* **OBS 28 及之后版本**：彻底放弃 Qt5，全线升级为 **Qt 6.x**；
* **后果**：如果主播的电脑还在使用 OBS 27，而插件链接的是 `Qt6Widgets.dll`，OBS 启动加载插件时会因找不到 Qt6 符号而触发 Windows 错误代码 `127 (ERROR_PROC_NOT_FOUND)`，并导致 OBS 启动报错或插件被抛弃。

### 3. MSVC 运行时库依赖 (Visual C++ Redistributable 缺失)
* 插件使用 Visual Studio 2022 (MSVC v143) 编译；
* 若终端主播是纯净操作系统，未安装 `Microsoft Visual C++ 2015-2022 Redistributable (x64)`，OBS 加载插件将直接报 `LoadLibrary failed: 126 (找不到指定的模块)`。

### 4. 体系架构不兼容 (32位 x86 vs 64位 x64)
* 本插件编译为纯 64 位（x86_64）PE 动态库；
* 若极少数用户下载了第三方远古 32 位 OBS，64 位 DLL 无法在 32 位进程中加载 (`193 %1 不是有效的 Win32 应用程序`)。

---

## 二、 全套防护与兼容性解决方案

为了让产品在各种用户电脑上具备极高的稳定性和容错能力，我们制定了四层防护矩阵：

```
第一层 (编译期)       第二层 (安装期)       第三层 (运行时)       终极方案 (分发期)
头文件 ABI 锁定  --> 安装程序版本探测  --> 运行时版本守卫  --> 一体化集成专版
 (408字节基准)        (阻止低版本安装)     (平稳降级防崩)       (完全消除差异)
```

---

### 方案 1：编译期 ABI 规范基准锁定（当前已落实）

为了确保插件对 **OBS 29、OBS 30、OBS 31** 的广泛向下兼容性：
1. **统一头文件定义**：在工程内部的 `deps/obs-studio-headers/libobs/obs-source.h` 中，将 `struct obs_source_info` 严格对齐 OBS 30 长期支持规范（**408 字节**）；
2. **禁止引入超前字段**：不引入高于目标运行基准的测试字段，确保向下兼容至 OBS 29/30，向上自然兼容 OBS 31（因为 OBS 允许注册尺寸小于自身上限的旧版本结构体，但严禁大于）。

---

### 方案 2：运行时动态版本守卫机制（Runtime Version Guard）

在插件生命周期入口 `obs_module_load()` 中，利用 libobs 提供的官方版本探测接口：
* `EXPORT uint32_t obs_get_version(void);`
* `EXPORT const char *obs_get_version_string(void);`

在模块加载瞬间对 OBS 版本进行安全嗅探：
```cpp
bool obs_module_load(void) {
    // 1. 获取当前宿主 OBS 核心主版本号
    uint32_t ver = obs_get_version();
    uint8_t major = (uint8_t)(ver >> 24);
    uint8_t minor = (uint8_t)(ver >> 16);

    blog(LOG_INFO, "[ZhiboLive] 宿主 OBS 版本探测: %s (v%u.%u)", obs_get_version_string(), major, minor);

    // 2. 版本兼容性守卫：Qt6 最低要求 OBS 28，推荐 OBS 29/30+
    if (major < 28) {
        blog(LOG_ERROR, "[ZhiboLive] 宿主 OBS 版本过低 (v%u.%u)，插件需要 OBS 29.0 或更高版本！", major, minor);
        
        // 安全拦截：弹窗友好告知，安全退出，避免 OBS 崩溃闪退
        MessageBoxW(NULL, 
            L"检测到当前 OBS Studio 版本过低（低于 28.0）。\n\n智播云控插件基于现代图形与 Qt6 架构，请将 OBS 升级至 30.0 或更高版本以获得正常支持！", 
            L"智播云控插件 - 版本兼容提示", 
            MB_ICONWARNING | MB_OK);
        return false; // 返回 false，OBS 将安全跳过该插件，OBS 主程序正常运行
    }

    // 3. 版本校验通过，继续后续初始化...
    ...
}
```
* **效果**：即使主播使用了 OBS 27 等极老版本，也不会闪退崩溃，而是弹出清晰的升级引导，安全保护主播的原有直播环境。

---

### 方案 3：安装包前置版本嗅探与防呆拦截

在制作独立安装包（Inno Setup / NSIS）时，安装包在写入文件前执行前置检查：
1. **检查 64 位注册表**：
   读取 `HKEY_LOCAL_MACHINE\SOFTWARE\OBS Studio` 中的 `InstallPath`；
2. **检查 `obs64.exe` 的 PE 文件版本**：
   * 若提取的主版本号 `< 29`，安装程序直接弹窗阻断并终止安装：
     > *“检测到您安装的 OBS Studio 版本为 27.x，本插件仅支持 OBS 29.0 及以上版本。请前往官网升级 OBS 后再安装。”*
3. **打包携带 VC++ Redistributable**：
   安装包内嵌官方 `VC_redist.x64.exe`，安装时自动静默执行 `/install /quiet /norestart`，彻底根治“缺少 DLL”报错。

---

### 方案 4：商业级“一体化集成专版”（终极推荐方案）

如果用户群体是非技术主播、兼职主播或矩阵公会开播，**最理想、返修率最低的方案是将 OBS 与插件直接打包为一套专属客户端**。

* **为什么这是行业终极方案？**
  * 斗鱼、虎牙、快手等主流平台的“直播伴侣”，本质上全都是深度封装的 OBS 专版；
  * 完全免除用户自行下载 OBS 带来的“装错版本、装错盘符、漏装插件、不会加滤镜”等 99% 的售后问题。
* **具体实施细节详见下篇文档**：《03-软件发布与打包全流程指南.md》。
