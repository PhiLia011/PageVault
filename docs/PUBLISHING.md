# 发布检查表

## Android

- [x] `targetSdkVersion 35`，`minSdkVersion 26`。
- [x] Release APK 经 zipalign，使用 v2/v3 签名。
- [x] 包名 `com.pagevault.app`，版本号 `1.1.0` / `2`。
- [ ] Google Play 通常要求 Android App Bundle（AAB）；若仅通过自有网站发布，可直接分发 APK。
- [ ] 在 Play Console 填写实际开发者名称、客服邮箱、隐私政策公网 URL。
- [ ] 上传商店图标和手机截图。
- [ ] 完成 Data safety 问卷：本应用不收集或上传用户数据。
- [ ] 长期离线保存 `.signing/pagevault-release.jks`；丢失后无法对已发布应用进行同签名更新。

签名证书 SHA-256：

`99a8756890a729a1d8f42fc0f6308dfa3f5b22fa3d6e9075f49b202761e45776`

## Windows

- [x] 自包含单文件 EXE，无需 .NET 运行时。
- [x] 应用图标、版本信息、产品名称和文件说明。
- [x] 提供便携 ZIP、使用说明、隐私说明和第三方组件声明。
- [ ] 面向公众分发前，使用受信任的 Authenticode 代码签名证书签名。
- [ ] 在发布页注明 Windows 10/11 x64 与 WebView2 Runtime 要求。
- [ ] 上传 SHA-256 校验值。

## 内容合规

- [x] 不绕过登录、验证码、DRM 或访问控制。
- [x] 不执行网页脚本。
- [x] 默认仅处理用户主动输入的单个公开页面。
- [x] 资源、单文件和总大小有硬限制。
- [x] 界面和 README 明确提示用户遵守网站条款与版权要求。
- [x] 项目以 `GPL-3.0-only` 发布，并随 Release 提供完整许可证文本。
