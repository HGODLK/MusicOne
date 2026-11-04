# MusicOne 0.3.0 音源接入

MusicOne 音源 SDK、本仓库提供的音源包、接入示例与文档采用 [GPL 第 3 版或后续版本](../LICENSE)（`GPL-3.0-or-later`）；第三方代码、依赖与资源保留各自的许可证声明。

本目录提供与当前宿主版本完全配套的公开 SDK 二进制产物及全套接入文档，支持开发者在**不依赖宿主工程、无需阅读复杂示例**的前提下，从零独立构建第三方音源包：

* **`source-sdk.aar`**：与当前宿主版本完全配套的公开 SDK 编译产物，开箱即用。
* **[README.md](README.md)**：本页，提供音源类型选择、零门槛独立脚手架模板与打包流程。
* **[可自定义功能.md](可自定义功能.md)**：介绍页面组织形态、开页动效契约与宿主公共 UI 组件复用。
* **[Agent 音源接入完整规范.md](Agent音源接入完整规范.md)**：提供所有公开接口、数据模型、生命周期及第 17 章完整 SDK 声明源码。

---

## 一、选择音源类型

| 类型 | 标识（kind） | 用途说明 | 分发文件后缀 |
| --- | --- | --- | --- |
| **主音源** | `MAIN` | 提供完整的音乐首页、搜索、资料库、收藏及播放等业务，按实际能力声明 | `.mscm` |
| **补充音源** | `SUPPLEMENTAL` | 面向远端视频、电台节目或直链转换，提供音频解析、子条目提取、导入与订阅 | `.mscp` |

---

## 二、5 分钟从零独立创建音源（无需阅读示例工程）

开发者可以在任何空目录下创建属于自己的音源工程，直接拷贝本目录的 `source-sdk.aar` 即可开箱编译。

### 1. 最小工程目录结构
```text
my-source/
├── settings.gradle.kts
├── build.gradle.kts
├── libs/
│   └── source-sdk.aar          <-- 直接拷贝本目录下的 source-sdk.aar
└── src/main/
    ├── AndroidManifest.xml
    ├── assets/
    │   └── musicone-source.json  <-- 音源身份与能力清单
    └── java/org/example/mysource/
        └── MySourceEntry.kt     <-- 入口业务实现
```

### 2. `settings.gradle.kts`
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "my-source"
```

### 3. `build.gradle.kts`
```kotlin
plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

android {
    namespace = "org.example.mysource"
    compileSdk = 37

    // 核心规范：私有资源必须使用独立包 ID（如 0x7e），保留 0x7f 给宿主共享资源
    androidResources { additionalParameters += listOf("--package-id", "0x7e", "--allow-reserved-package-id") }

    defaultConfig {
        applicationId = "org.example.mysource"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 必须启用 v1 签名，否则宿主在部分 Android 系统上无法通过安全沙箱解析
    signingConfigs.getByName("debug") {
        enableV1Signing = true
        enableV2Signing = true
    }

    buildTypes.getByName("release") {
        // 开发验证可复用 debug 签名，正式对外发布换用自己独立的正式私钥
        signingConfig = signingConfigs.getByName("debug")
    }
}

dependencies {
    // 依赖本地 SDK AAR
    compileOnly(files("libs/source-sdk.aar"))

    // 共享运行时必须声明为 compileOnly，由宿主提供，严禁重复打包到外部包内
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly("androidx.compose.runtime:runtime:1.11.4")
    compileOnly("androidx.compose.ui:ui:1.11.4")
    compileOnly("androidx.compose.foundation:foundation:1.11.4")
    compileOnly("androidx.compose.material3:material3:1.4.0")
}
```

### 4. `src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.mysource">
    <application />
</manifest>
```

### 5. `src/main/assets/musicone-source.json`（音源清单）
```json
{
  "id": "org.example.mysource",
  "name": "我的音源",
  "kind": "SUPPLEMENTAL",
  "versionCode": 1,
  "versionName": "1.0",
  "entryClass": "org.example.mysource.MySourceEntry",
  "apiVersion": 1,
  "minHostVersionCode": 4,
  "minHostVersionName": "0.3.0-麦辣鸡腿堡",
  "kotlinVersion": "2.3.21",
  "composeVersion": "1.11.4",
  "minAndroid": 23,
  "capabilities": ["HOME", "CONTENT", "CHILD_ITEMS", "IMPORT", "PLAYBACK"],
  "useCustomPages": false
}
```

### 6. `src/main/java/org/example/mysource/MySourceEntry.kt`（最小业务代码）
```kotlin
package org.example.mysource

import com.musicone.source.sdk.*

class MySourceEntry : SimpleSupplementalSourceEntry() {
    override val id = "org.example.mysource"
    override val name = "我的音源"

    // 1. 解析输入的文本（直链、视频链接或内容 ID），返回曲目信息
    override suspend fun parseInput(input: String): SimpleInputResult {
        val track = SourceTrack(
            identity = TrackIdentity(sourceId = id, platformTrackId = "sample_001"),
            title = "示例音频",
            artist = "演示艺术家"
        )
        return SimpleInputResult.SingleTrack(track)
    }

    // 2. 解析曲目的实际音频播放直链
    override suspend fun resolvePlayback(track: SourceTrack): PlaybackAddress {
        return PlaybackAddress.Direct("https://example.com/audio.mp3")
    }
}
```

### 7. 构建打包与导入安装
```powershell
# 1. 运行 Release 构建
gradle assembleRelease

# 2. 将输出的 APK 文件直接改名为对应的分发后缀（补充音源为 .mscp，主音源为 .mscm）
# 注意：产物本身就是已签名的标准 APK，只需更改文件后缀名，切勿解压或重新打包！
Copy-Item build/outputs/apk/release/*.apk ./my-source.mscp

# 3. 导入与生效：
# 打开 MusicOne 客户端 -> 进入“设置” -> “音源管理” -> 点击“导入音源”选择 .mscp 文件
# 导入成功后重启应用进程，即可立即加载并使用您的专属音源！
```

---

## 三、通用接入原则与生命周期规范

1. **唯一且稳定的身份**：`id` 与 `entryClass` 必须全局唯一且发布后保持稳定；更新音源包时必须使用相同签名证书；建议递增 `versionCode`，也允许用户主动同版本覆盖和降级安装，重启后生效。
2. **两段式生命周期**：音源导入或更新后，宿主出于内存隔离保护，在**下次应用进程重启**时正式加载生效；同理，移除音源也有可撤销保护机制，进程重启后才会清理相关私有数据。
3. **页面组织形态**：
   * 补充音源若希望部分接管页面，可实现 `SourceSupplementalPageProvider`，并在 `useCustomPages=false` 下按页面选择 `DEFAULT`（公共模板）、`CUSTOM`（自定义页）或 `DISABLED`（关闭）。
   * `useCustomPages=true` 时，音源包自行组织完整页面流程与导航，但仍可直接复用宿主暴露的公共整页与原子组件（参见 [可自定义功能.md](可自定义功能.md)）。
4. **共享依赖红线**：SDK、Kotlin 运行时、Jetpack Compose 基础库均由宿主环境提供，音源包的 `build.gradle.kts` 中必须将其设为 `compileOnly`，严禁重复打入外部包，否则会导致 ClassLoader 类加载冲突。

---

最低宿主以 `minHostVersionCode` 正整数比较，名称仅展示。本轮宿主为 code 5，示例基线为 code 4；调用 `SourceHost.playRecommendation` 的包必须声明 code 5 及以上；旧包可省略此字段，新发布包应声明真实最低需求。API 1 表示接口大版本，可选接口仍需对应宿主。Kotlin／Compose 字段记录实际编译版本，由宿主兼容名单决定是否接受；当前名单为 2.3.21／1.11.4，不承诺任意补丁号双向兼容。旧宿主不会自动执行新字段的检查。主动导入仅与当前选定包（pending 优先，否则 active）版本及完整文件内容相同时跳过；已有其他 pending 时重选原包会更新待生效选择。预装自动更新只接纳更高版本，不自动套用主动覆盖／降级规则。详见[兼容与安装决策表](Agent音源接入完整规范.md#installation)。

## 四、进阶扩展能力索引

| 需求场景 | 推荐入口与接口 | 详细契约指引 |
| --- | --- | --- |
| **最小链接解析与播放** | `SimpleSupplementalSourceEntry` | [简化补充接入](Agent音源接入完整规范.md#simple-supplemental) |
| **逐页定制与复用公共 UI** | `SourceSupplementalPageProvider`、公共组件 | [可自定义功能](可自定义功能.md) |
| **自定义多档位音质与命名** | 可选 `SourceQualityCatalog` | [音质与缓存](Agent音源接入完整规范.md#playback) |
| **统计平台收听时长与打卡** | 可选 `SourcePlaybackSessionProvider` | [播放会话](Agent音源接入完整规范.md#playback-session) |
| **检测平台会员与专属角标** | 可选 `SourceMembershipProvider` | [会员检测](Agent音源接入完整规范.md#membership-detection) |
| **自建主音源全屏播放器** | 可选 `SourcePlayerUiProvider` | [播放器契约](Agent音源接入完整规范.md#player-ui) |
| **查看全部底层接口原件** | 第 17 章完整 SDK 源码声明 | [SDK 完整声明与默认实现](Agent音源接入完整规范.md#sdk-declarations) |

> 提示：如果需要参考官方已经编写好的多功能复杂音源（如多页登录、动态货架、故障注入隔离等），可以查看工程根目录的 `examples/` 示例目录；但对于普通的音源开发，依照本文档的脚手架即可完整独立交付。
