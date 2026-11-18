拾页 PageVault 1.1.0（Windows x64）

运行方式
1. 双击 PageVault.exe。
2. 粘贴公开网页或视频分享链接，点击“解析 / 抓取”。
3. 解析完成后先临时预览，不会自动保存。
4. 需要保存时，点击网页/文字、图片、视频、音频、附件或“下载全部 ZIP”。
5. 使用“更改保存位置”设置自定义下载目录。

系统要求
- Windows 10 64 位或 Windows 11。
- Microsoft Edge WebView2 Runtime。Windows 11 和较新的 Windows 10 通常已经安装。
- 不需要安装 .NET 运行时。

数据位置
- 临时预览：系统临时目录下的 PageVault\preview，退出应用后自动清理。
- 默认下载目录：用户 Downloads\PageVault，可在应用内修改。
- 历史资料库：%LOCALAPPDATA%\PageVault\pages
- 删除资料库条目会同时删除对应本地文件。
- 导出 ZIP 后，压缩包包含 index.html、article.txt、manifest.json 和 assets 目录。

功能边界
- 支持公开静态网页中的正文、图片、直接音视频文件和常见附件。
- 支持 B站、抖音、快手、小红书分享链接。
- 支持 yt-dlp 可解析的 YouTube、TikTok、X、Instagram、Vimeo 等常见公开视频。
- 不运行远端脚本，不登录账号，不绕过验证码、付费墙、DRM 或网站访问控制。
- 依赖 JavaScript 动态生成的内容、HLS/DASH 流以及平台 DRM 视频不在 1.0 范围内。
- 请仅保存你有权访问和使用的内容，并遵守网站条款与当地版权法律。

发布说明
- 当前 EXE 为自包含、单文件便携版。
- 面向公众大规模分发前，建议使用受信任的 Authenticode 代码签名证书签名，以减少 SmartScreen 警告。
