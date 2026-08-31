# 飞秋安卓版

Android 版飞秋 / IPMsg 局域网聊天客户端。

## 仓库内容

- `android/`：Android 工程
- `docs/`：协议、工程说明、进度记录

## 构建

```powershell
cd android
.\gradlew.bat assembleDebug
```

## 安装

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

## 文档

- [协议还原与安卓互联开发指南](docs/01-协议还原与安卓互联开发指南.md)
- [安卓工程说明](docs/02-安卓工程说明.md)
- [功能完善路线图](docs/03-功能完善路线图.md)
- [当前功能进度与待办](docs/04-当前功能进度与待办.md)
- [完整复核记录](docs/05-2026-06-07完整复核记录.md)
- [FeiQ.exe 静态逆向记录](docs/06-FeiQ.exe静态逆向记录.md)
- [本轮复盘](docs/07-本轮复盘.md)

## 说明

仓库只保留 Android 相关代码和文档，不包含原始 Windows 程序、工具脚本或无关历史文件。
