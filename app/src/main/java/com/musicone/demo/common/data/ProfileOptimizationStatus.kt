package com.musicone.demo

import android.content.Context
import androidx.profileinstaller.ProfileVerifier
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 等待初始化器安装并验证内置 Profile，不提前缓存“尚未安装”的结果。 */
internal class ProfileOptimizationStatus(private val context: Context) {
    suspend fun isPending(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = ProfileVerifier.getCompilationStatusAsync()
            if (!result.isDone) return@withContext false
            val code = result.get().profileInstallResultCode
            // 验证器在进程内缓存结果；沿用其参考 Profile 路径检查后续后台优化完成。
            val reference = File("/data/misc/profiles/ref/${context.packageName}/primary.prof")
            profileOptimizationNoticeVisible(code, reference.length() > 0L)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}

/** 只有明确排队且尚无编译参考结果才提示；错误、不支持与未安装状态均不猜测。 */
internal fun profileOptimizationNoticeVisible(resultCode: Int, referenceProfilePresent: Boolean): Boolean =
    resultCode == ProfileVerifier.CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION &&
        !referenceProfilePresent
