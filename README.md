# 飞秋安卓版

[中文](#中文) | [English](#english)

## 中文

飞秋安卓版是一个兼容 Windows 飞秋 / IPMsg 的局域网聊天客户端。

手机和电脑连接到同一个 Wi-Fi 后，可以在局域网内自动发现设备并进行点对点通信，不经过云服务器。项目重点是适配老版本 Windows 飞秋的文字、表情、图片和文件通信能力。

预编译 APK 请前往 [Releases](https://github.com/zyqg/feiq-android/releases) 下载。

### 功能

- 自动发现同一局域网内的 Windows 飞秋和 Android 设备
- Wi-Fi 切换、断网恢复后的网络重连与重新广播
- 私聊、未读提示、会话预览和历史记录
- 文本与飞秋内置表情混合发送
- 96 个飞秋内置表情面板，支持动画显示和 Backspace 删除
- 图片发送与飞秋内联图片接收
- 单文件、多文件和文件夹传输
- 文件管理、文件打开、筛选和批量删除
- 全局搜索，并跳转到对应会话中的消息位置
- 联系人分组、我的分组、手动添加 IP
- 可配置协议端口，默认端口为 `2425`
- 本机头像、昵称和分组设置
- 微信式聊天输入栏、表情面板和附件面板
- 系统分享 Intent，可从相册或文件管理器分享内容

### 当前限制

- 电脑端头像同步协议尚未完全确认，当前头像主要用于 Android 本机显示。
- Windows 飞秋真正的群聊/群组网络协议尚未完整逆向；联系人分组消息目前按普通私聊逐个发送。
- 当前历史记录仍以 JSON 保存，超大规模历史数据还需要迁移到 SQLite 或 Room。
- APK 目前为开发阶段构建，正式签名和自动更新还未配置。

### 系统要求

- Android 7.0（API 24）或以上
- Windows 飞秋电脑端或其他兼容 IPMsg 的局域网设备
- 手机和电脑处于同一个局域网
- 路由器不能开启阻止设备互通的 AP 隔离
- Android 需要允许本应用访问附近网络和文件

如果发现不了电脑，请确认手机和电脑连接的是同一个 Wi-Fi，并检查 Windows 防火墙是否允许 UDP/TCP `2425` 端口通信。

### 使用

Android：

1. 在 [Releases](https://github.com/zyqg/feiq-android/releases) 下载最新 APK。
2. 在手机上打开 APK，并按系统提示允许安装未知来源应用。
3. 启动应用，允许网络和文件访问权限。
4. 确认电脑飞秋使用默认端口 `2425`，或在 Android 的“我的”页面修改端口。
5. 在会话列表中点击联系人开始聊天。

从源码构建：

1. 安装 Android Studio、Android SDK 和 JDK 17。
2. 进入 `android/` 目录。
3. 执行构建命令，APK 输出到 `app/build/outputs/apk/`。

### 技术栈

- Kotlin
- Android Views、XML 布局和 ViewBinding
- UDP 广播与单播进行设备发现和控制消息
- TCP 进行文件和目录传输
- IPMsg / FeiQ 兼容协议
- 本地 JSON 消息持久化
- 本地 GIF 资源驱动的飞秋表情动画

### 端口

- UDP：设备发现和控制消息，默认 `2425`
- TCP：文本、图片和文件传输，默认 `2425`

### 开发与构建

```powershell
cd android
.\gradlew.bat assembleDebug
```

安装到已连接的 Android 设备：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 文档

- [协议还原与安卓互联开发指南](docs/01-协议还原与安卓互联开发指南.md)
- [安卓工程说明](docs/02-安卓工程说明.md)
- [功能完善路线图](docs/03-功能完善路线图.md)
- [当前功能进度与待办](docs/04-当前功能进度与待办.md)
- [完整复核记录](docs/05-2026-06-07完整复核记录.md)
- [FeiQ.exe 静态逆向记录](docs/06-FeiQ.exe静态逆向记录.md)
- [本轮复盘](docs/07-本轮复盘.md)

## English

FeiQ Android is a LAN chat client compatible with Windows FeiQ / IPMsg.

Connect the phone and the Windows computer to the same Wi-Fi network. The app discovers peers locally and communicates directly without a cloud server. The project focuses on compatibility with the legacy Windows FeiQ text, emoticon, image, and file protocols.

Download prebuilt APKs from [Releases](https://github.com/zyqg/feiq-android/releases).

### Features

- Automatic discovery of Windows FeiQ and Android peers on the same LAN
- Network restart and rebroadcast after Wi-Fi changes or connectivity recovery
- Private chats, unread indicators, previews, and local history
- Mixed text and FeiQ built-in emoticon messages
- 96 built-in FeiQ emoticons with animated rendering and Backspace deletion
- Image sending and FeiQ inline-image receiving
- Single-file, multi-file, and folder transfer
- File manager with opening, filtering, and batch deletion
- Global search with navigation to the matching message in a chat
- Contact groups, local groups, and manual IP peers
- Configurable protocol port, defaulting to `2425`
- Local profile settings for avatar, nickname, and group
- WeChat-like chat composer, emoticon panel, and attachment panel
- Android share intents from gallery and file managers

### Current limitations

- The Windows avatar synchronization protocol is not fully confirmed; avatars are currently primarily local to Android.
- The native Windows FeiQ group-chat protocol has not been fully reverse engineered. Contact-group messaging currently sends ordinary private messages one by one.
- History is currently stored as JSON and may later move to SQLite or Room for very large datasets.
- The APK is currently a development build without production signing or automatic updates.

### Requirements

- Android 7.0 (API 24) or later
- A Windows FeiQ peer or another compatible IPMsg LAN client
- The phone and computer must be on the same LAN
- Router AP isolation must be disabled
- Android must allow network and file access

If peers are not discovered, verify that both devices use the same Wi-Fi and that Windows Firewall allows UDP/TCP traffic on port `2425`.

### Usage

1. Download the latest APK from [Releases](https://github.com/zyqg/feiq-android/releases).
2. Open it on the phone and allow installation from unknown sources if prompted.
3. Launch the app and grant network and file permissions.
4. Keep the Windows FeiQ peer on port `2425`, or change the port from the Android “Me” page.
5. Select a peer from the session list to start chatting.

### Development

JDK 17 and the Android SDK are required.

```powershell
cd android
.\gradlew.bat assembleDebug
```

The repository contains only the Android source and project documentation. The original Windows executable, Windows runtime files, local tools, generated APKs, and build caches are intentionally excluded.
