# 拾页 / PageVault

拾页是一个本地网页离线资料库。输入公开网页地址后，它会提取正文、图片、直接音视频与常见附件，生成不执行远端脚本的本地阅读页，并支持整页 ZIP 导出。

Copyright (C) 2026 PhiLia011. Licensed under GPL-3.0-only.

## 平台

- Windows 10/11 x64：WPF + WebView2，自包含单文件 EXE。
- Android 8.0+：原生 Android + WebView，签名的 release APK。
- 两端使用相同的保存目录结构：`index.html`、`article.txt`、`manifest.json`、`assets/`。

## 功能

- 自动补全 `https://`，支持 http/https。
- 提取文章正文、标题、段落、列表、表格、代码、引用。
- 保存 `<img>`、懒加载图片属性、直接 `<video>`、`<audio>`、`<source>` 与海报图。
- 保存页面链接中的常见静态附件。
- 移除脚本、表单、iframe、对象嵌入、导航、侧栏、评论和推荐区域。
- 应用内离线阅读，图片和音视频可播放。
- 单页完整 ZIP 导出。
- Windows 支持取消抓取；Android 支持长按删除和导出到 Downloads。

## 明确边界

- 不绕过登录、验证码、付费墙、DRM 或访问控制。
- 不执行远端 JavaScript，因此只由脚本生成的内容可能为空。
- 直接媒体文件可保存；HLS `.m3u8` 与 DASH `.mpd` 清单不在此版本范围内。
- 没有爬取全站、批量抓取或多层链接跟随功能。该工具面向用户主动选择并有权保存的页面。
- 网站结构千差万别，正文自动识别不能保证对所有站点完美；导出包中同时保留 `article.txt` 便于校对。

## 构建

### Windows

```powershell
dotnet restore .\desktop\PageVault\PageVault.csproj
.\desktop\build.ps1
```

输出：`dist/PageVault-1.0.0-win-x64.exe` 和同名 ZIP。

### Android

```powershell
.\android\build.ps1
```

脚本优先读取 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 与 `JAVA_HOME`，也接受
`-AndroidSdk` 和 `-JavaHome` 参数。输出：`dist/PageVault-1.0.0-android.apk`。

首次构建会生成 `.signing/pagevault-release.jks` 和对应属性文件。发布后必须长期安全保存该密钥；后续更新必须使用同一签名证书。不要把 `.signing` 上传到公开仓库。

## 测试

`tests/site` 包含静态图片、MP4、WAV、附件、脚本、导航和侧栏，以及一个可复现的采集测试页。使用任意静态 HTTP 服务器托管该目录即可验证。

已验证：

- Windows 真实网页抓取、正文过滤和离线阅读。
- 图片、视频、音频、附件落盘与阅读页显示。
- ZIP 完整导出。
- Android release APK 构建、zipalign 和 v2/v3 签名校验。

## 发布前仍需提供或确认

- Windows 面向公众分发前，购买并配置受信任的 Authenticode 代码签名证书。
- Android 上架 Google Play 时，补齐商店图标、截图、实际开发者名称、客服邮箱、隐私政策 URL，并按商店要求生成 AAB。
- 使用自己的应用名称、包名、签名主体和品牌素材；当前名称与包名可用于内部测试或自有渠道发布。

## 许可证

本项目以 **GNU General Public License v3.0 only**（`GPL-3.0-only`）发布。
完整条款见 [LICENSE](LICENSE)。第三方组件仍分别遵循
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 中列出的许可证。
