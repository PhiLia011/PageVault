# 拾页 PageVault v1.1.0

新增视频解析与手动保存流程：解析结果先临时预览，不再默认写入本地。

## 新增

- B站、抖音、快手、小红书原视频链接解析。
- Windows / Android 内置 yt-dlp，支持 YouTube、TikTok、X、Instagram、Vimeo 等常见公开站点。
- 临时预览不自动进入资料库。
- 独立下载按钮：网页/文字、图片、视频、音频、附件。
- 完整 ZIP 下载继续保留。
- Windows 自定义下载目录。
- Android 使用系统目录选择器，支持持久授权和自定义目录。

## 已验证

- B站视频：下载 50.2 MB，封面和本地视频播放正常。
- 抖音视频：下载 20.7 MB，时长约 319 秒。
- 快手视频：下载 4.7 MB，独立“视频”按钮只保存视频和清单。
- Windows 临时预览、按类型下载和保存位置设置。
- Android API 35 APK 安装与视频解析流程。

## 说明

- 不绕过登录、验证码、DRM、付费墙或访问控制。
- 部分站点会要求 Cookie、代理或只提供分离音视频；当前版本会明确提示失败原因。
- 请仅保存你有权访问和使用的内容。

## 许可证

项目以 GPL-3.0-only 发布。yt-dlp、youtubedl-android 及其他第三方组件遵循
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) 中列出的许可证。
