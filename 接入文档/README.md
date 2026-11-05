# MusicOne 音源接入

MusicOne 音源 SDK、本仓库提供的音源包、接入示例与文档采用 [GPL 第 3 版或后续版本](../LICENSE)（`GPL-3.0-or-later`）；第三方代码、依赖与资源保留各自的许可证声明。

本目录提供公开 SDK 二进制产物及全套接入文档，支持开发者在**不依赖宿主工程、无需阅读复杂示例**的前提下，从零独立构建第三方音源包：

* **`source-sdk.aar`**：由本仓库 `source-sdk` 构建的公开 SDK 产物，与同一提交的宿主配套。
* **[README.md](README.md)**：本页，提供音源类型选择、独立脚手架模板与打包流程。
* **[可自定义功能.md](可自定义功能.md)**：介绍页面组织形态、开页动效契约与宿主公共 UI 组件复用。
* **[歌词注音与翻译规范.md](歌词注音与翻译规范.md)**：说明音源如何提供歌词翻译和注音。
* **[Agent音源接入完整规范.md](Agent音源接入完整规范.md)**：提供所有公开接口、数据模型、生命周期及第 17 章完整 SDK 声明源码。

---

## 一、选择音源类型

| 类型 | 标识（kind） | 用途说明 | 分发文件后缀 |
| --- | --- | --- | --- |
| **主音源** | `MAIN` | 提供完整的音乐首页、搜索、资料库、收藏及播放等业务，按实际能力声明 | `.mscm` |
| **补充音源** | `SUPPLEMENTAL` | 面向远端视频、电台节目或直链转换，提供音频解析、子条目提取、导入与订阅 | `.mscp` |

---

## 二、从零独立创建补充音源

开发者可以在任何空目录下创建自己的音源工程，拷贝本目录的 `source-sdk.aar` 即可编译。下面的模板与 [examples/source-supplemental-simple](../examples/source-supplemental-simple/README.md) 等价，只是把身份常量直接写在代码和清单里。

### 1. 最小工程目录结构
```text
my-source/
├── settings.gradle.kts
├── gradle.properties
├── build.gradle.kts
├── source-rules.pro
├── libs/
│   └── source-sdk.aar          <-- 拷贝本目录下的 source-sdk.aar
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

### 3. `gradle.properties`
```properties
android.useAndroidX=true
# Windows 下工程路径含中文等非 ASCII 字符时，AGP 默认拒绝构建
android.overridePathCheck=true
# Kotlin 标准库由宿主提供，不能让插件自动加入运行时依赖
kotlin.stdlib.default.dependency=false
```

### 4. `build.gradle.kts`
```kotlin
plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

android {
    namespace = "org.example.mysource"
    compileSdk = 37

    // 私有资源必须使用独立包 ID（0x02..0x7e），0x7f 保留给宿主共享资源
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

    buildFeatures { compose = true }

    // 必须启用 v1 签名，宿主验证器读取 v1/JAR 签名
    signingConfigs.getByName("debug") {
        enableV1Signing = true
        enableV2Signing = true
    }

    buildTypes.getByName("release") {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "source-rules.pro")
        // 开发验证可复用 debug 签名，正式对外发布换用自己固定的正式私钥
        signingConfig = signingConfigs.getByName("debug")
    }
}

dependencies {
    // SDK 与共享运行时由宿主提供，只能 compileOnly，严禁打进音源包
    compileOnly(files("libs/source-sdk.aar"))
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly("androidx.compose.runtime:runtime:1.11.4")
    compileOnly("androidx.compose.ui:ui:1.11.4")
    compileOnly("androidx.compose.foundation:foundation:1.11.4")
    compileOnly("androidx.compose.material3:material3:1.4.0")
}

// 兜底排除插件自动加入的标准库
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
```

### 5. `source-rules.pro`
```proguard
# 宿主按清单 entryClass 反射创建入口，入口类及其成员必须保留
-keep class org.example.mysource.MySourceEntry { *; }
# 私有实现收进自己的命名空间，避免和其他音源包混淆后重名
-repackageclasses org.example.mysource.internal
-dontwarn androidx.**
-dontwarn kotlin.**
```

### 6. `src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 包名由 build.gradle.kts 的 namespace 决定，这里不能再写 package 属性 -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application />
</manifest>
```

### 7. `src/main/assets/musicone-source.json`（音源清单）
```jsonc
{
  "id": "org.example.mysource",
  "name": "我的音源",
  "kind": "SUPPLEMENTAL",
  "versionCode": 1,
  "versionName": "1.0",
  "entryClass": "org.example.mysource.MySourceEntry",
  "apiVersion": 1,
  "minHostVersionCode": <宿主versionCode>,       // 填你实际开发、验证所用宿主的 versionCode，见第三节
  "minHostVersionName": "<宿主versionName>",     // 可选，仅用于提示
  "kotlinVersion": "2.3.21",
  "composeVersion": "1.11.4",
  "minAndroid": 23,
  "capabilities": ["HOME", "CONTENT", "CHILD_ITEMS", "IMPORT", "PLAYBACK"],
  "useCustomPages": false
}
```

实际文件是标准 JSON：替换两个占位值并删除 `//` 注释。清单须与入口 `descriptor` 完全一致：`id`、`name`、`versionCode`、`versionName` 与下方代码相同，`capabilities` 是简化基类的默认集合。

### 8. `src/main/java/org/example/mysource/MySourceEntry.kt`（最小业务代码）
```kotlin
package org.example.mysource

import com.musicone.source.sdk.*

class MySourceEntry : SimpleSupplementalSourceEntry(SimpleSupplementalSourceInfo(
    id = "org.example.mysource",
    name = "我的音源",
    versionCode = 1,
    versionName = "1.0",
)) {
    // 1. 把用户输入（链接、内容 ID 等）解析成完整曲目；返回多首时宿主显示子条目选择
    override suspend fun parseInput(input: String): List<SourceTrackDetails> {
        val url = input.trim()
        require(url.startsWith("https://")) { "请输入 HTTPS 音频直链" }
        // 真实平台应使用稳定内容 ID 作身份，临时签名地址放进私有载荷
        val identity = TrackIdentity(descriptor.id, null, url)
        val title = url.substringAfterLast('/').ifBlank { "直链音频" }
        return listOf(SourceTrackDetails(
            track = SourceTrack(identity, title),
            stableId = url,
            payload = SourcePayload("org.example.mysource.direct", 1, url),
        ))
    }

    // 2. 为曲目返回实际可播放的音频地址
    override suspend fun resolvePlayback(request: SourcePlaybackRequest): SourcePlaybackResource {
        val track = request.track
        require(track.payload.format == "org.example.mysource.direct") { "不支持的音频内容" }
        return SourcePlaybackResource(
            address = PlaybackAddress(track.payload.encoded, cacheIdentity = track.stableId),
            qualityId = request.qualityId ?: "default",
            format = "",
        )
    }
}
```

### 9. 构建打包与导入安装

模板不带 Gradle wrapper，需本机安装 Gradle 9.6.1 与 JDK 17 及以上（AGP 9.2.1 的要求）；也可用宿主源码工程的 wrapper 配合 `-p <工程目录>` 构建。

```powershell
# 1. 运行 Release 构建
gradle assembleRelease

# 2. 将输出的 APK 复制并改为分发后缀（补充音源 .mscp，主音源 .mscm）
# 产物本身就是已签名的标准 APK，只改后缀，切勿解压或重新打包
Copy-Item build/outputs/apk/release/*.apk ./my-source.mscp

# 3. 导入：MusicOne“设置”→“音源管理”→“导入音源”，选择 .mscp 文件
# 导入成功后重启应用进程即可加载
```

---

## 三、通用接入原则与生命周期规范

1. **唯一且稳定的身份**：`id` 与 `entryClass` 必须全局唯一且发布后保持稳定；更新音源包时必须使用相同签名证书；建议递增 `versionCode`，也允许用户主动同版本覆盖和降级安装，重启后生效。
2. **两段式生命周期**：音源导入或更新后，宿主在**下次应用进程重启**时正式加载生效；移除主音源也有可撤销保护，进程重启后才清理相关私有数据。
3. **页面组织形态**：
   * 补充音源若希望部分接管页面，可实现 `SourceSupplementalPageProvider`，并在 `useCustomPages=false` 下按页面选择 `DEFAULT`（公共模板）、`CUSTOM`（自定义页）或 `DISABLED`（关闭）。
   * `useCustomPages=true` 时，音源包自行组织完整页面流程与导航，但仍可直接复用宿主暴露的公共整页与原子组件（参见 [可自定义功能.md](可自定义功能.md)）。
4. **共享依赖红线**：SDK、Kotlin 运行时、Jetpack Compose 基础库均由宿主提供，音源包必须将其设为 `compileOnly`，并关闭标准库自动依赖；重复打入会导致 ClassLoader 类加载冲突。

### 最低宿主版本

- `minHostVersionCode` 填你**实际开发、验证所用宿主**的 versionCode（正整数，只有它参与比较）；`minHostVersionName` 可选，仅用于提示。旧包可省略这两个字段，新发布包应声明。
- 查看宿主 versionCode：`adb shell dumpsys package io.github.shuyunr.musicone | findstr versionCode`；声明高于当前宿主时，导入提示也会显示“当前为 …（code N）”。
- 用到较新宿主接口时，声明值不能低于该接口的门槛，见[宿主接口门槛](Agent音源接入完整规范.md#host-thresholds)。
- `apiVersion=1` 是接口大版本，可选接口仍需对应宿主。Kotlin／Compose 字段记录实际编译版本，由宿主兼容名单决定是否接受（目前为 2.3.21／1.11.4），不承诺任意补丁号双向兼容。旧宿主不会自动执行新字段的检查。
- 主动导入仅与当前选定包（pending 优先，否则 active）版本及完整文件内容都相同时跳过；已有其他 pending 时重选原包会更新待生效选择。预装自动更新只接纳更高版本。详见[兼容与安装决策表](Agent音源接入完整规范.md#installation)。

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

> 提示：需要参考多功能复杂音源（如多页登录、动态货架、故障注入隔离等）时，可查看仓库的 `examples/` 与 `source-packs/`；普通音源依照本页脚手架即可独立交付。
