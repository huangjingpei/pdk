# PDK 智播云控 OBS 插件开发与交付文档库

欢迎查阅 **PDK 智播云控 OBS 插件 (`obs-plugin-zhibo-live`)** 官方技术文档库。

---

## 📚 文档目录索引

1. 🚀 **[01-编译与运行指南](./01-编译与运行指南.md)**
   * 开发与构建环境准备（VS2022、CMake、Qt6、自包含依赖库）；
   * Release（生产环境）与 Debug（本地联调）双模式构建；
   * 一键安装脚本与手动部署路径规范；
   * 首次卡密激活、推流联动与日志查看排错。

2. 🛡️ **[02-OBS版本兼容性与ABI防护方案](./02-OBS版本兼容性与ABI防护方案.md)**
   * 宿主 OBS 升级可能引发的四大兼容风险（ABI 结构体尺寸、Qt5/Qt6 断层、CRT 依赖、32/64 位）；
   * 408 字节 ABI 锁定技术规范与踩坑记录；
   * 运行时动态版本守卫机制 (`obs_get_version` 防崩设计)；
   * 安装包前置版本嗅探与商业一体化规避策略。

3. 📦 **[03-软件发布与打包全流程指南](./03-软件发布与打包全流程指南.md)**
   * 核心解答：“独立插件 EXE” 与 “OBS 一体化整套打包” 的利弊与商业选型；
   * **模式一（独立插件安装器）**：Inno Setup 自动化打包脚本模板 (`installer.iss`)；
   * **模式二（一体化绿色专版，商业标配）**：Portable 便携模式配置、预置场景调优与一键交付方案；
   * 正式发版检查清单 (Release Checklist)。

---

## 🛠️ 常用操作速查

* **Release 生产版编译**：直接运行 `build.bat` 或 `cmake --build build --config Release`
* **Debug 本地联调编译**：`cmake --build build --config Debug`
* **本地一键安装到 OBS**：以管理员身份运行 `install.bat`
* **OBS 插件日志定位**：打开 `%APPDATA%\obs-studio\logs` 检索关键字 `[ZhiboLive]`
