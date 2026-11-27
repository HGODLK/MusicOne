package com.musicone

import android.content.Context
import androidx.profileinstaller.ProfileVerifier
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 等 ProfileInstaller 装完再查编译状态，不提前缓存“未安装”的中间结果。 */
internal class ProfileOptimizationStatus(private val context: Context) {
    suspend fun isPending(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = ProfileVerifier.getCompilationStatusAsync()
            if (!result.isDone) return@withContext false
            val code = result.get().profileInstallResultCode
            // ProfileVerifier 会在进程内缓存结果，这里顺便查下系统参考 Profile 文件，判断后台编译是不是已经跑完了。
            val reference = File("/data/misc/profiles/ref/${context.packageName}/primary.prof")
            profileOptimizationNoticeVisible(code, reference.length() > 0L)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}

/** 只有在明确处于“已排队等待编译”且还没生成参考 Profile 时才显示提示，其他不确定状态一律不提示。 */
internal fun profileOptimizationNoticeVisible(resultCode: Int, referenceProfilePresent: Boolean): Boolean =
    resultCode == ProfileVerifier.CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION &&
        !referenceProfilePresent
