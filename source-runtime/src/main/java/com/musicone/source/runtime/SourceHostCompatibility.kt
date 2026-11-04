package com.musicone.source.runtime

import com.musicone.source.sdk.SourceSdk

/** 兼容名单由宿主维护；依赖升级验收后保留仍受支持的旧编译版本。 */
data class SourceHostCompatibility(
    val versionCode: Long,
    val versionName: String,
    val kotlinVersions: Set<String> = setOf("2.3.21", SourceSdk.KOTLIN_VERSION),
    val composeVersions: Set<String> = setOf("1.11.4", SourceSdk.COMPOSE_VERSION),
) {
    fun check(manifest: SourceManifest) {
        manifest.minHostVersionCode?.let { minimum ->
            require(minimum > 0) { "最低宿主版本编号无效" }
            require(versionCode >= minimum) {
                val required = manifest.minHostVersionName?.let { "$it（code $minimum）" } ?: "code $minimum"
                "音源需要 MusicOne ≥ $required，当前为 $versionName（code $versionCode）"
            }
        }
        require(manifest.kotlinVersion in kotlinVersions) {
            "Kotlin 编译版本 ${manifest.kotlinVersion} 不在宿主兼容范围内：${kotlinVersions.joinToString()}"
        }
        require(manifest.composeVersion in composeVersions) {
            "Compose 编译版本 ${manifest.composeVersion} 不在宿主兼容范围内：${composeVersions.joinToString()}"
        }
    }
}
