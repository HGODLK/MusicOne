package com.musicone.source.sdk

import java.util.concurrent.CancellationException

/** 由音源主动声明的可选会员检测；默认空值，不启用检测或联网。 */
interface SourceMembershipProvider {
    val membershipDetector: SourceMembershipDetector? get() = null
}

interface SourceMembershipDetector {
    /** 只读本地已知结果，不得在读取属性时联网；只有 true 表示已确认会员。 */
    val currentMembership: Boolean? get() = null

    /** 按当前账号检测，只有 true 表示已确认会员；无会员或未知返回 false 或 null。 */
    suspend fun detectMembership(): Boolean?
}

fun SourceEntry.membershipDetectorOrNull(): SourceMembershipDetector? =
    (this as? SourceMembershipProvider)?.membershipDetector

/** 未声明为 false；已声明但未确认会员为未知，不从旧账号摘要推断结果。 */
fun SourceEntry.currentMembership(): Boolean? {
    val detector = membershipDetectorOrNull() ?: return false
    return detector.currentMembership.takeIf { it == true }
}

/** 仅显式调用时检测；普通失败保持未知，取消不能转成会员结果。 */
suspend fun SourceEntry.detectMembership(): Boolean? = try {
    val detector = membershipDetectorOrNull()
    if (detector == null) false else detector.detectMembership().takeIf { it == true }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
