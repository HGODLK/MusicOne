package com.musicone.source.runtime

import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

internal class SourcePreferences(file: File) : SharedPreferences {
    private val atomic = AtomicFile(file)
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val values = mutableMapOf<String, Any>()
    init {
        if (file.exists()) {
            val json = JSONObject(atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            json.keys().forEach { key ->
                val item = json.getJSONObject(key)
                values[key] = when (item.getString("type")) {
                    "string" -> item.getString("value")
                    "boolean" -> item.getBoolean("value")
                    "int" -> item.getInt("value")
                    "long" -> item.getLong("value")
                    "float" -> item.getDouble("value").toFloat()
                    "set" -> item.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                    else -> error("音源偏好格式未知")
                }
            }
        }
    }
    @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    @Synchronized override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    @Synchronized override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { if (listener != null) listeners.add(listener) }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners.remove(listener) }
    override fun edit(): SharedPreferences.Editor = Editor()
    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = apply { updates[requireNotNull(key)] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { updates[requireNotNull(key)] = values?.toSet() }
        override fun putInt(key: String?, value: Int) = apply { updates[requireNotNull(key)] = value }
        override fun putLong(key: String?, value: Long) = apply { updates[requireNotNull(key)] = value }
        override fun putFloat(key: String?, value: Float) = apply { updates[requireNotNull(key)] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { updates[requireNotNull(key)] = value }
        override fun remove(key: String?) = apply { updates[requireNotNull(key)] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { check(commit()) { "保存音源偏好失败" } }
        override fun commit(): Boolean = synchronized(this@SourcePreferences) {
            val next = if (clear) mutableMapOf() else values.toMutableMap()
            updates.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
            val json = JSONObject()
            next.forEach { (key, value) -> json.put(key, JSONObject().apply {
                put("type", when (value) { is String -> "string"; is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; else -> "set" })
                put("value", if (value is Set<*>) JSONArray(value.toList()) else value)
            }) }
            atomic.baseFile.parentFile!!.mkdirs()
            val output = atomic.startWrite()
            try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
            catch (_: Exception) { atomic.failWrite(output); return@synchronized false }
            val changed = (values.keys + next.keys).filter { values[it] != next[it] }
            values.clear(); values.putAll(next)
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@SourcePreferences, key) } }
            true
        }
    }
}
