# 魔镜采集端（安卓手机模拟）

阶段一用安卓手机替代魔镜硬件，目标是把采集链路、BLE 协议、端侧质量门槛全部跑通并固化，后续同一套协议直接平移到魔镜主板（RK3588 级 AOSP）。

## 打开与运行

1. Android Studio 打开 `mirror-android/` 目录（需要 AGP 8.7+、JDK 17）
2. 修改 `app/build.gradle.kts` 里的三个字段：

```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.100:8000\"")  // 服务端局域网 IP
buildConfigField("String", "DEVICE_TOKEN", "\"dev-mirror-token\"")
buildConfigField("String", "BIND_CODE", "\"演示家庭\"")                      // 与服务端家庭名一致
```

3. 真机运行（模拟器无蓝牙，无法验证地垫链路）
4. 首次启动授予相机、蓝牙、麦克风权限

## 采集流程

```
地垫上电广播 → BLE 扫描捕获 → 唤醒魔镜
  → 收到稳定重量，开启会话
  → 前置摄像头人脸识别，匹配家庭成员
  → 语音播报姓名，进入 120 秒刷牙引导
      · 第 3 秒 / 第 45 秒抓拍面部
      · 第 112 秒抓拍牙齿
  → 上报数值与影像 → 服务端分析 → 返回健康日报
```

## 关键模块

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | 权限申请、CameraX 预览、ML Kit 实时人脸检测、Compose 界面 |
| `ui/MirrorViewModel.kt` | 采集状态机与编排，失败自动入队 |
| `net/ApiClient.kt` | 服务端通信，带 `X-Request-Id` 幂等头 |
| `ble/MatScaleManager.kt` | 地垫 BLE GATT 连接与读数解析 |
| `data/LocalQueue.kt` | 断网缓存队列，开机自动补传 |

## BLE 协议

```
Service 0xFFF0
  0xFFF1 Notify  实时重量（4 字节小端 uint32，单位 0.1 g）
  0xFFF2 Notify  稳定重量（仅在读数稳定后上报一次）
```

采集侧只在收到**稳定重量**时才推进业务，且要求 ≥ 3 kg（排除宠物误踩）。

## 地垫没有实物时怎么联调

`MatScaleManager` 会一直处于扫描状态。可以临时改 `MirrorViewModel.start()`，跳过地垫直接调用 `onStableWeight(20.0)` 手动触发会话，先把影像与分析链路跑通。

## 平移至真实魔镜的改动点

| 项 | 手机方案 | 魔镜主板 |
|----|---------|---------|
| 人脸/牙齿采集 | 前置摄像头 | 同规格定焦广角 + 高显色白光补光 |
| 身高采集 | 人工补录 | 顶部 ToF / 超声测距模组 |
| 唤醒 | 地垫 BLE 触发 | 同 + 人体存在雷达 |
| 显示 | 手机屏幕 | 半透反射镜面 + 屏幕 |
| 语音 | TTS 扬声器 | 同 |

由于协议与状态机完全复用，移植工作量主要在相机标定与测距模块接入。

## 端侧采集质量门槛（待补）

`docs/03-硬件选型与接口协议.md` 定义了 5 项影像质量检查（人脸占比、姿态角、模糊度、亮度、张嘴度）。当前版本仅接入了 ML Kit 的「是否检测到人脸」，其余检查项需在 `MainActivity` 的 `ImageAnalysis` 回调中补齐，通过后才进入上传队列——**这一步不做，牙刷场景的影像废片率会很高**。
