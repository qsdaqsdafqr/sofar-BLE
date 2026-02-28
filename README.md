# 苏法尔 MiniBox3-35 蓝牙控制端

[English README](README.en.md)

这是一个面向苏法尔（Sofar）`MiniBox3-35` 家用增压泵的 Android 控制应用。
项目通过 BLE 直连设备，目标是替代“苏法尔智控”小程序，提供一个更直接、可维护、便于排查问题的本地控制端。

## 适用设备

- 品牌：苏法尔（Sofar）
- 型号：`MiniBox3-35`
- 场景：家用增压泵控制与状态调试

## 项目用途

- 作为 `MiniBox3-35` 的专用控制端
- 使用原生 Android 应用替代现有小程序
- 保留本地直连能力，减少中间环节
- 为后续协议分析、联调和维护提供基础

## 当前功能

- BLE 扫描与设备选择
- BLE 连接与断开管理
- 面向 Sofar / Modbus 风格报文的协议编码与会话逻辑
- Jetpack Compose 控制界面

## 构建环境

- Android Studio 稳定版，或命令行 Android SDK 工具链
- Android SDK Platform 36
- JDK 17 或更高版本

虽然应用代码目标字节码仍为 Java 11，但当前使用的 Android Gradle Plugin `8.12.0` 需要 JDK 17+ 才能运行构建。

仓库不会提交 `local.properties`。如果使用 Android Studio，通常会自动生成；如果使用命令行构建，需要确保 `sdk.dir` 指向你本机的 Android SDK。

目前更推荐在 Windows 环境下构建，并使用本机 Windows Android SDK。对应命令优先使用 `gradlew.bat`。

## 快速开始

在 Windows 终端中执行：

```bat
gradlew.bat assembleDebug
```

生成的 Debug APK 默认输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如需查看更多构建说明，可参考 [BUILDING.md](BUILDING.md)。

## 项目结构

- `app/`：Android 应用模块
- `gradle/`：Gradle Wrapper 配置
- `gradlew` / `gradlew.bat`：Gradle Wrapper 启动脚本

## 许可证

本项目使用 [MIT License](LICENSE)。

## 说明

- 这是一个个人维护项目，不是苏法尔官方发布的应用。
- 仓库当前以实际可用和便于调试为优先，界面、协议细节和功能仍可继续迭代。
