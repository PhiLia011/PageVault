# 拾页 PageVault v1.0.0

首个公开版本，把公开网页保存成可离线阅读和导出的本地资料。

## 功能

- 输入 http/https 网页地址，自动提取标题、正文、图片、直接音视频文件和常见附件。
- 移除远端脚本、表单、iframe、导航、侧栏、评论和推荐区域。
- 应用内离线阅读，支持图片显示、视频/音频播放。
- 导出包含 `index.html`、`article.txt`、`manifest.json` 和全部资源的 ZIP。
- Windows 支持取消任务；Android 支持长按删除和导出到 `Downloads/PageVault`。

## 平台支持

### Windows

- Windows 10/11 x64。
- `PageVault-1.0.0-win-x64.exe` 为自包含单文件版，无需安装 .NET。
- 需要 Microsoft Edge WebView2 Runtime；Windows 11 和新版 Windows 10 通常已预装。
- 当前 EXE 未使用商业 Authenticode 证书签名。

### Android

- Android 8.0+（minSdk 26），targetSdk 35。
- APK 已使用 4096 位 RSA 密钥签名，并通过 APK Signature Scheme v2/v3 验签。

## 下载文件

- `PageVault-1.0.0-win-x64.exe` — Windows 单文件程序。
- `PageVault-1.0.0-win-x64.zip` — Windows 便携包，含使用说明和隐私说明。
- `PageVault-1.0.0-android.apk` — Android 安装包。
- `PageVault-1.0.0-store-assets.zip` — 应用图标、Android 实机截图和发布清单。
- `PageVault-1.0.0-source-gpl.zip` — 与本版本对应的完整源码和 GPL-3.0 许可证。
- `PageVault-1.0.0-checksums.sha256` — SHA-256 校验值。

## 功能边界

- 不绕过登录、验证码、付费墙、DRM 或网站访问控制。
- 不执行远端 JavaScript，因此完全由脚本生成的内容可能无法保存。
- 直接媒体文件支持 MP4/WebM 等常见格式；HLS/DASH 流不在此版本范围内。
- 请仅保存你有权访问和保存的内容，并遵守网站条款与版权要求。

## 验证

- Windows 已测试真实网页抓取、正文过滤、媒体落盘和 ZIP 导出。
- Android API 35 模拟器已测试安装、抓取、离线阅读、音视频控件和导出到 Downloads。
- Android 运行日志未发现崩溃或 ANR。

## 许可证

项目源码和本项目原创内容以 **GPL-3.0-only** 发布。AngleSharp、jsoup、
WebView2 SDK、.NET 与 Android SDK 等第三方组件分别遵循其原始许可证。
