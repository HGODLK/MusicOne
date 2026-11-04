package com.musicone.validation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicone.validation.production.LegacySourceDataMigration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 同生产源码的Android存储算法测试，不冒充优化宿主的真实控制器回调。 */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationStorageAlgorithmTest {
    private val sign = "FIXTURE_ONLY_SIGNER"
    private val ids = listOf("qq", "kugou", "netease", "bilibili")
    private fun id(platform: String) = "com.musicone.source.$platform"

    @Test fun fourSourcesRepeatWithoutOverwritingNewLoginOrExplicitLogout() = isolated { host, target ->
        seed(host)
        ids.forEach { platform ->
            val pack = target(platform)
            migrate(host, pack, platform)
            val first = values(pack, platform)
            val image = File(pack.filesDir, "profile/netease_fixture.webp")
            if (platform == "netease") {
                assertTrue("网易旧背景必须复制", image.isFile)
                assertArrayEquals(File(host.filesDir, "profile/netease_fixture.webp").readBytes(), image.readBytes())
            }
            val originalImage = image.takeIf(File::isFile)?.readBytes()
            migrate(host, pack, platform)
            assertTrue("重复迁移不应改变已有会话字段或类型", first == values(pack, platform))
            originalImage?.let { assertArrayEquals(it, image.readBytes()) }
            assertEquals(1, pack.getSharedPreferences("host_legacy_migration", 0).getInt("version", 0))
            assertTrue("宿主旧会话必须保留", values(host, platform)[credentialKey(platform)] == credential(platform))
            if (platform == "qq") assertTrue("原音质偏好必须保留", pack.getSharedPreferences("musicone_playback_quality", 0).getString("preferred_qq", null) == "EXHIGH")
            if (platform == "bilibili") assertTrue("B站设备身份必须保留", pack.getSharedPreferences("musicone_bili_device", 0).getString("buvid3", null) == "FIXTURE_ONLY_BUVID")

            // 新登录与主动退出的空凭证均优先于旧数据，不拼接旧账号字段。
            listOf("FIXTURE_ONLY_NEW_LOGIN", "").forEachIndexed { index, credential ->
                val existing = target("$platform-existing-$index")
                val name = preferenceName(platform)
                existing.getSharedPreferences(name, 0).edit().putString(credentialKey(platform), credential)
                    .putString("fixture_new_owner", "FIXTURE_ONLY_NEW_OWNER").commit()
                val existingImage = File(existing.filesDir, "profile/netease_fixture.webp")
                if (platform == "netease") existingImage.apply { parentFile.mkdirs(); writeBytes(byteArrayOf(8, 9)) }
                val before = existing.getSharedPreferences(name, 0).all.toMap()
                migrate(host, existing, platform)
                assertTrue("已有登录或主动退出优先，不得混入旧账号资料", before == existing.getSharedPreferences(name, 0).all)
                if (platform == "netease") assertArrayEquals(byteArrayOf(8, 9), existingImage.readBytes())
            }
        }
        assertTrue(File(host.filesDir, "profile/netease_fixture.webp").isFile)
    }

    @Test fun interruptedCompletionMarkerCanRetryWithFreshMigratorAndPreserveBackground() = isolated { host, target ->
        seed(host)
        ids.forEach { platform ->
            val pack = target("$platform-interrupted")
            pack.failNextMarker = true
            try { migrate(host, pack, platform); fail("故障commit必须中止迁移") }
            catch (_: IllegalStateException) { }
            assertFalse(pack.getSharedPreferences("host_legacy_migration", 0).contains("version"))
            assertTrue("中断前复制字段应已保存", credential(platform) == pack.getSharedPreferences(preferenceName(platform), 0).getString(credentialKey(platform), null))
            val image = File(pack.filesDir, "profile/netease_fixture.webp")
            val copied = image.takeIf(File::isFile)?.readBytes()
            // 每次migrate都新建真实迁移类；第一次已写数据、未写完成标记。
            migrate(host, pack, platform)
            assertEquals(1, pack.getSharedPreferences("host_legacy_migration", 0).getInt("version", 0))
            assertTrue("重建迁移器重试后字段及类型必须一致", values(host, platform) == values(pack, platform))
            copied?.let { assertArrayEquals(it, image.readBytes()) }
        }
        assertArrayEquals(byteArrayOf(82, 73, 70, 70, 1, 2, 3), File(host.filesDir, "profile/netease_fixture.webp").readBytes())
    }

    private fun migrate(host: Context, target: Context, platform: String) =
        LegacySourceDataMigration(host, ids.associate { id(it) to sign }).migrate(id(platform), sign, target)

    private fun seed(host: Context) {
        listOf("qq", "kugou", "netease").forEach { platform ->
            host.getSharedPreferences("platform_settings", 0).edit()
                .putString("session_${platform}_credential", credential(platform))
                .putString("session_${platform}_device_id", "FIXTURE_ONLY_DEVICE")
                .putString("session_${platform}_user_id", "FIXTURE_ONLY_OWNER")
                .putInt("session_${platform}_follows", 7).putLong("session_${platform}_fixture_long", 9L).commit()
        }
        host.getSharedPreferences("musicone_bilibili_session", 0).edit().putString("cookie", credential("bilibili"))
            .putString("mid", "FIXTURE_ONLY_OWNER").commit()
        host.getSharedPreferences("musicone_bili_device", 0).edit().putString("buvid3", "FIXTURE_ONLY_BUVID").commit()
        host.getSharedPreferences("musicone_playback_quality", 0).edit().putString("preferred_qq", "EXHIGH").commit()
        File(host.filesDir, "profile/netease_fixture.webp").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(82, 73, 70, 70, 1, 2, 3))
        }
    }
    private fun credential(platform: String) = "FIXTURE_ONLY_${platform.uppercase()}_CREDENTIAL"
    private fun preferenceName(platform: String) = if (platform == "bilibili") "musicone_bilibili_session" else "platform_settings"
    private fun credentialKey(platform: String) = if (platform == "bilibili") "cookie" else "session_${platform}_credential"
    private fun values(context: Context, platform: String): Map<String, *> {
        val raw = context.getSharedPreferences(preferenceName(platform), 0).all
        return if (platform == "bilibili") raw else raw.filterKeys { it.startsWith("session_${platform}_") }
    }

    private fun isolated(block: (IsolatedStorageContext, (String) -> IsolatedStorageContext) -> Unit) {
        // Instrumentation运行在宿主UID中，仅借用其可写沙箱；所有名称和文件均由下方随机隔离层接管。
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "migration-storage-test-${UUID.randomUUID()}").apply { mkdirs() }
        val contexts = mutableListOf<IsolatedStorageContext>()
        fun context(name: String) = IsolatedStorageContext(base, File(root, name)).also { contexts += it }
        try { block(context("host"), ::context) }
        finally { contexts.forEach { it.clearTestPreferences() }; root.deleteRecursively() }
    }

    /** 只访问本测试随机前缀的偏好和专属文件目录，不读取任何真实会话名称。 */
    private class IsolatedStorageContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val prefix = "migration_storage_test_${UUID.randomUUID()}_"
        private val names = mutableSetOf<String>()
        var failNextMarker = false
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val qualified = prefix + name
            names += qualified
            val actual = baseContext.getSharedPreferences(qualified, mode)
            if (name != "host_legacy_migration") return actual
            return object : SharedPreferences by actual {
                override fun edit(): SharedPreferences.Editor {
                    val editor = actual.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putInt(key: String, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                        override fun commit(): Boolean {
                            if (failNextMarker) { failNextMarker = false; return false }
                            return editor.commit()
                        }
                    }
                }
            }
        }
        fun clearTestPreferences() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
