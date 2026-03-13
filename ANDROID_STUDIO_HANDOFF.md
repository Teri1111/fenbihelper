# 刷题豆包截图助手 - Android Studio 交接文档

## 1. 项目目标

开发一个轻量原生 Android App，用于在刷题时通过悬浮按钮一键完成以下流程：

1. 点击悬浮按钮
2. 立即截图
3. 将截图保存为可分享图片
4. 通过系统分享直接发送给豆包 App

目标是替代当前手工流程：手动截屏 -> 切换到豆包 -> 选择图片 -> 发送。

## 2. 已确认结论

以下结论已经人工验证：

1. 豆包支持通过系统分享接收图片。
2. 因此第一版不需要依赖无障碍自动点击上传按钮。
3. 第一版应优先走原生分享链路，而不是 UI 自动化链路。

这意味着最小可行方案可以是：

悬浮按钮 -> MediaProjection 截图 -> 生成 content Uri -> Intent 分享给豆包

## 3. 第一版功能范围

只做最小闭环，不扩散需求。

必须实现：

1. 应用内有一个简单主页，用于引导授权和启动悬浮服务。
2. 有悬浮按钮常驻屏幕上层。
3. 点击悬浮按钮后执行截图。
4. 截图结果保存为图片，并生成可分享 Uri。
5. 优先直接分享给豆包。
6. 如果无法定向分享给豆包，则回退到系统分享面板。

第一版不要做：

1. OCR
2. 区域选框截图
3. 自动识别题目区域
4. 无障碍自动点击豆包界面
5. 聊天记录管理
6. 云同步

## 4. 推荐技术选型

建议技术栈：

1. Kotlin
2. Android Studio 最新稳定版
3. 最低兼容版本建议 Android 8.0+
4. UI 层可用 Jetpack Compose
5. 悬浮按钮使用前台服务 + WindowManager
6. 截图使用 MediaProjection API
7. 图片共享使用 MediaStore 或 FileProvider + content Uri
8. 对外发送使用 Intent.ACTION_SEND

## 5. 建议工程结构

建议先按这个结构组织：

```text
app/src/main/java/.../
  MainActivity.kt
  ui/
    AppScreen.kt
  service/
    FloatingWindowService.kt
  capture/
    ScreenCaptureManager.kt
    ScreenCapturePermissionStore.kt
  share/
    ShareManager.kt
  data/
    CaptureResult.kt
  util/
    NotificationHelper.kt
    PermissionHelper.kt
```

如果想更简单，也可以先只保留：

1. MainActivity
2. FloatingWindowService
3. ScreenCaptureManager
4. ShareManager

## 6. 核心实现思路

### 6.1 主页面

主页只需要提供这些能力：

1. 检查并引导开启悬浮窗权限
2. 请求截图授权
3. 启动或停止悬浮按钮服务
4. 显示当前授权状态

### 6.2 悬浮按钮服务

用前台服务承载悬浮窗，避免被系统轻易杀掉。

职责：

1. 创建悬浮按钮视图
2. 将视图挂到 WindowManager
3. 处理点击事件
4. 点击后调用截图模块
5. 截图完成后调用分享模块

建议：

1. 单击执行截图并分享
2. 长按关闭服务或打开设置页
3. 增加执行中状态，防止重复点击

### 6.3 截图模块

截图推荐使用 MediaProjection API。

大致流程：

1. 在 Activity 中通过 createScreenCaptureIntent 请求截图授权
2. 在 onActivityResult 或 Activity Result API 中拿到 resultCode 和 data Intent
3. 保存这两个值，供后续悬浮服务使用
4. 在真正截图时，通过 MediaProjectionManager.getMediaProjection 创建 MediaProjection
5. 使用 ImageReader 获取屏幕帧
6. 转成 Bitmap
7. 存储为 PNG 或 JPEG
8. 返回图片 Uri 或文件信息

注意事项：

1. 不要在每次点击悬浮按钮时都重新申请截图授权
2. 截图权限应在首次进入应用时申请一次，然后缓存授权结果
3. 部分设备可能在进程重启后需要重新授权，代码中要做好失效兜底

### 6.4 分享模块

优先使用显式或半显式 Intent 分享给豆包。

流程建议：

1. 创建 ACTION_SEND Intent
2. type 设置为 image/*
3. EXTRA_STREAM 放入 content Uri
4. 添加 FLAG_GRANT_READ_URI_PERMISSION
5. 如果已知豆包包名，则优先 setPackage
6. 如果定向失败，则退回系统 chooser

建议封装两个方法：

1. shareToDoubao(context, uri): Boolean
2. shareWithChooser(context, uri)

## 7. Manifest 关注点

需要重点关注这些声明：

1. 前台服务
2. SYSTEM_ALERT_WINDOW 对应的悬浮窗能力引导
3. 通知权限
4. FileProvider 或相关 provider 配置

需要根据目标 SDK 进一步确认的内容：

1. 前台服务类型
2. Android 13+ 的通知权限处理
3. Android 14+ 的前台服务限制

## 8. 权限与系统限制

至少要考虑这些：

1. 悬浮窗权限
2. 截图授权 MediaProjection
3. 通知权限 POST_NOTIFICATIONS
4. 前台服务相关限制

原则：

1. 图片分享优先走 content Uri，不要直接分享裸文件路径
2. 截图服务必须设计为可恢复或可重新授权
3. 所有失败路径都要有用户可理解的提示

## 9. 失败兜底策略

第一版就应设计好兜底，不要只做理想路径。

建议按这个顺序回退：

1. 定向分享给豆包
2. 系统分享面板
3. 只保存截图，并提示用户手动打开豆包

如果截图失败：

1. 给出失败提示
2. 引导重新申请截图权限

如果悬浮窗权限缺失：

1. 禁止启动悬浮服务
2. 跳转到系统权限页

## 10. 里程碑拆分

### 里程碑 1：截图链路跑通

目标：

1. 项目可编译运行
2. 首页可申请截图权限
3. 可以成功截取当前屏幕
4. 可以成功保存图片

### 里程碑 2：悬浮按钮跑通

目标：

1. 可启动前台服务
2. 屏幕上出现悬浮按钮
3. 点击按钮可触发截图

### 里程碑 3：分享链路跑通

目标：

1. 截图后优先分享给豆包
2. 无法定向到豆包时自动退回系统 chooser
3. 整体流程形成闭环

### 里程碑 4：打磨体验

可选优化：

1. 执行中 loading 状态
2. 长按关闭悬浮窗
3. 最近一次截图预览
4. 错误提示与重试

## 11. 优先级判断

当前最重要的事情不是做复杂 UI，而是尽快验证这四个点：

1. MediaProjection 权限申请与复用是否稳定
2. 前台服务 + 悬浮窗是否稳定
3. 截图保存和 content Uri 是否正确
4. 豆包定向分享是否稳定

只要这四个点成立，这个 App 的主目标就已经成立。

## 12. 建议先让 Copilot 生成的内容

进入 Android Studio 后，建议分步骤让 Copilot 生成，不要一次让它写完整项目。

推荐顺序：

1. 生成一个 Kotlin Android App 的基础结构，包含 MainActivity 和 Compose 首页
2. 在主页中加入悬浮窗权限检查和截图授权按钮
3. 新增一个前台服务 FloatingWindowService，显示可拖动悬浮按钮
4. 实现 ScreenCaptureManager，负责申请后的截图执行与保存
5. 实现 ShareManager，负责将截图通过 content Uri 分享给豆包，失败时退回 chooser
6. 最后再补充错误处理、通知渠道和状态管理

## 13. 可直接发给 Copilot 的提示词

可以把下面这段直接发给 Android Studio 里的 Copilot：

```text
请帮我实现一个 Kotlin 原生 Android App，目标是做一个“刷题截图发豆包”的轻量工具。要求如下：

1. 首页提供权限引导和启动悬浮服务按钮。
2. 使用前台服务 + WindowManager 显示悬浮按钮。
3. 点击悬浮按钮后，通过 MediaProjection API 截图。
4. 截图保存为图片，并生成可分享的 content Uri。
5. 优先通过 Intent.ACTION_SEND 将图片定向分享给豆包 App。
6. 如果无法定向分享给豆包，则自动退回系统分享面板。
7. 第一版不要做 OCR、区域截图、无障碍自动点击。
8. 请先给出项目结构、Manifest 需要的配置、核心类划分，然后再逐步生成代码。

实现时请优先保证：
- Android 8.0+ 可运行
- 权限处理清晰
- 使用 Kotlin
- 代码结构尽量简单，先实现最小可行版本
```

## 14. 当前决策摘要

当前技术路线已经确定：

1. 不再使用 Auto.js 或 AutoX.js 作为主方案
2. 直接实现原生 Android App
3. 第一版核心链路为：悬浮按钮 -> 截图 -> 系统分享给豆包
4. 因为豆包已经验证支持系统分享图片，所以暂不做无障碍自动上传

## 15. 后续可选增强

等第一版稳定后，再考虑这些增强项：

1. 题目区域裁剪
2. 发送前预览
3. 双击触发不同模式
4. 截图历史
5. OCR 或图像增强
6. 截图后自动回到原应用

当前阶段不要提前实现这些功能。