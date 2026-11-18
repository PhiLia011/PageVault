# 拾页 / PageVault

拾页是一个网页与视频解析浏览工具。输入公开网页或视频分享链接后，它会提取正文、图片、音视频和附件，先生成不执行远端脚本的临时预览；只有用户主动点击对应下载按钮时，内容才写入指定目录。

Copyright (C) 2026 PhiLia011. Licensed under GPL-3.0-only.

## 平台

- Windows 10/11 x64：WPF + WebView2，自包含单文件 EXE。
- Android 8.0+：原生 Android + WebView，签名的 release APK。
- 两端使用相同的保存目录结构：`index.html`、`article.txt`、`manifest.json`、`assets/`。

## 功能

- 自动补全 `https://`，支持 http/https。
- 提取文章正文、标题、段落、列表、表格、代码、引用。
- 保存 `<img>`、懒加载图片属性、直接 `<video>`、`<audio>`、`<source>` 与海报图。
- 解析 B 站、抖音、快手、小红书分享链接。
- Windows 与 Android 内置 yt-dlp，用于 YouTube、TikTok、X、Instagram、Vimeo 等站点。
- 保存页面链接中的常见静态附件。
- 移除脚本、表单、iframe、对象嵌入、导航、侧栏、评论和推荐区域。
- 解析后先生成临时预览，不自动加入资料库。
- 按网页/文字、图片、视频、音频、附件分别下载，也可下载完整 ZIP。
- Windows 支持自定义下载目录；Android 使用系统目录选择器。
- Windows 支持取消抓取；Android 支持长按删除和导出到 Downloads。

## 明确边界

- 不绕过登录、验证码、付费墙、DRM 或访问控制。
- 不执行远端 JavaScript，因此只由脚本生成的内容可能为空。
- 仅保存能直接播放或下载的媒体文件；需要合并分离音视频、DRM 或 HLS/DASH 分片的内容不在此版本范围内。
- 没有爬取全站、批量抓取或多层链接跟随功能。该工具面向用户主动选择并有权保存的页面。
- 网站结构千差万别，正文自动识别不能保证对所有站点完美；导出包中同时保留 `article.txt` 便于校对。

## 构建

### Windows

```powershell
dotnet restore .\desktop\PageVault\PageVault.csproj
.\desktop\build.ps1
```

输出：`dist/PageVault-1.1.0-win-x64.exe` 和同名 ZIP。

### Android

```powershell
.\android\build.ps1
```

脚本优先读取 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 与 `JAVA_HOME`，也接受
`-AndroidSdk`、`-JavaHome` 和 `-Gradle` 参数。输出：
`dist/PageVault-1.1.0-android.apk`。

首次构建会生成 `.signing/pagevault-release.jks` 和对应属性文件。发布后必须长期安全保存该密钥；后续更新必须使用同一签名证书。不要把 `.signing` 上传到公开仓库。

## 测试

`tests/site` 包含静态图片、MP4、WAV、附件、脚本、导航和侧栏，以及一个可复现的采集测试页。使用任意静态 HTTP 服务器托管该目录即可验证。

已验证：

- Windows 真实网页抓取、正文过滤和临时预览。
- B 站、抖音、快手视频解析与本地播放。
- 按类型下载视频、网页/文字与完整 ZIP。
- Android APK 安装、视频解析、目录选择、按类型下载和 ZIP 下载。

## 发布前仍需提供或确认

- Windows 面向公众分发前，购买并配置受信任的 Authenticode 代码签名证书。
- Android 上架 Google Play 时，补齐商店图标、截图、实际开发者名称、客服邮箱、隐私政策 URL，并按商店要求生成 AAB。
- 使用自己的应用名称、包名、签名主体和品牌素材；当前名称与包名可用于内部测试或自有渠道发布。

## 许可证

本项目以 **GNU General Public License v3.0 only**（`GPL-3.0-only`）发布。
完整条款见 [LICENSE](LICENSE)。第三方组件仍分别遵循
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 中列出的许可证。
