拾页 PageVault 1.0.0（Windows x64）

运行方式
1. 双击 PageVault.exe。
2. 粘贴公开网页的 http/https 地址，点击“抓取页面”。
3. 抓取完成后即可在应用内阅读，或使用“导出 ZIP”保存完整离线包。

系统要求
- Windows 10 64 位或 Windows 11。
- Microsoft Edge WebView2 Runtime。Windows 11 和较新的 Windows 10 通常已经安装。
- 不需要安装 .NET 运行时。

数据位置
- 默认资料库：%LOCALAPPDATA%\PageVault\pages
- 删除资料库条目会同时删除对应本地文件。
- 导出 ZIP 后，压缩包包含 index.html、article.txt、manifest.json 和 assets 目录。

功能边界
- 支持公开静态 HTML 页面中的正文、图片、直接音视频文件和常见附件。
- 不运行远端脚本，不登录账号，不绕过验证码、付费墙、DRM 或网站访问控制。
- 依赖 JavaScript 动态生成的内容、HLS/DASH 流以及平台 DRM 视频不在 1.0 范围内。
- 请仅保存你有权访问和使用的内容，并遵守网站条款与当地版权法律。

发布说明
- 当前 EXE 为自包含、单文件便携版。
- 面向公众大规模分发前，建议使用受信任的 Authenticode 代码签名证书签名，以减少 SmartScreen 警告。
