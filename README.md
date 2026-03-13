# 刷题截图发豆包

一个面向刷题场景的原生 Android 工具：

- 悬浮窗按钮一键截图并分享
- 优先定向分享到豆包，失败时回退系统分享面板
- 支持无障碍自动点击“发送”按钮
- 支持通过 Shizuku 做无障碍掉线恢复（可选）

## 现在能做什么

- 首页集中引导授权，并显示关键状态：悬浮窗、通知、截图会话、无障碍、Shizuku、守护开关、服务状态
- 前台服务常驻悬浮控件：
  - `截`：截图 + 分享
  - `回`：快速切回粉笔 App
  - 长按 `截`：关闭服务
- 截图链路基于 `MediaProjection`，图片保存到应用缓存目录，再通过 `FileProvider` 输出 `content://` URI
- 分享链路优先匹配豆包（包名/Activity/应用名），无法匹配时自动打开系统 chooser
- 无障碍服务在豆包页尝试自动点击“发送”，并带有短时重试节流
- 可选守护：当无障碍关闭且已开启守护时，前台服务会周期检测，尝试通过 Shizuku 恢复并发通知提醒

## 技术栈与版本

- Kotlin + Android View XML（非 Compose）
- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`
- Java 11 兼容
- 主要依赖：AndroidX、Material、Kotlin Coroutines、Shizuku API/Provider

## 目录与核心模块

- `app/src/main/java/com/example/myapplication/MainActivity.kt`：主页状态、权限入口、服务开关、Shizuku 授权流程
- `app/src/main/java/com/example/myapplication/service/FloatingWindowService.kt`：前台服务、悬浮按钮、截图与分享触发、守护巡检
- `app/src/main/java/com/example/myapplication/capture/ScreenCaptureManager.kt`：截图会话管理、位图生成与缓存落盘
- `app/src/main/java/com/example/myapplication/share/ShareManager.kt`：分享 Intent 构建与豆包定向
- `app/src/main/java/com/example/myapplication/service/DoubaoAutoSendAccessibilityService.kt`：豆包发送按钮自动点击
- `app/src/main/java/com/example/myapplication/service/ShizukuAccessibilityKeeper.kt`：Shizuku 恢复无障碍能力

## 快速开始

### 方式一：Android Studio

1. 用 Android Studio 打开项目根目录。
2. 等待 Gradle Sync 完成。
3. 连接真机（推荐）并运行 `app` 模块。

### 方式二：命令行构建（PowerShell）

```powershell
cd C:\Users\gtter\AndroidStudioProjects\MyApplication
.\gradlew.bat :app:assembleDebug
```

## 首次使用流程（建议按顺序）

1. 打开并授权悬浮窗权限。
2. Android 13+ 授权通知权限（建议）。
3. 打开“自动发送无障碍设置”，启用本应用无障碍服务。
4. （可选）连接并授权 Shizuku，用于无障碍保活恢复。
5. 申请截图授权，确认状态变为“本次服务会话已授权”。
6. 启动悬浮按钮服务，进入刷题场景使用。

## 权限与系统能力说明

- `SYSTEM_ALERT_WINDOW`：显示悬浮按钮
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`：前台截图服务
- `POST_NOTIFICATIONS`（Android 13+）：前台服务通知与守护提醒
- `BIND_ACCESSIBILITY_SERVICE`（由系统设置页启用）：自动点击发送
- `moe.shizuku.manager.permission.API_V23`：Shizuku 侧能力授权

## 已知限制

- `MediaProjection` 授权是“会话态”，进程被系统回收或会话失效后需要重新授权。
- 无障碍自动点击依赖豆包页面结构，豆包版本变化可能导致点击策略失效。
- Shizuku 保活为可选增强，依赖用户已安装并启动 Shizuku 服务。

## 常见问题排查

- 启动服务失败：先检查悬浮窗权限是否开启。
- 点 `截` 没反应或提示重授权：回到首页重新申请截图授权。
- 能分享但未自动发送：检查无障碍服务是否仍开启。
- 守护开启但仍掉线：确认 Shizuku 正在运行且本应用授权成功。

## 备注

- 截图文件位于应用缓存目录（`cache/captures`），用于即时分享。
- 项目里还有详细交接说明：`ANDROID_STUDIO_HANDOFF.md`。

