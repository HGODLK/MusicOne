package com.musicone.source.bilibili

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { Result.failure(failure) }
internal fun JSONArray.searchObjects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun Throwable.asUserMessage(): String = message?.takeIf(String::isNotBlank) ?: "哔哩哔哩请求失败，请重试"
