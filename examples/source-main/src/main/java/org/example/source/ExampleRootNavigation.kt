package org.example.source

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import com.musicone.source.sdk.SourcePageRequest
import com.musicone.source.sdk.SourcePageState
import com.musicone.source.sdk.SourceRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 两个根页各自保存内部导航，只有活跃页能更新宿主的返回状态。 */
internal class ExampleRootNavigation(private val sourceId: String) {
    class Root {
        var screen by mutableStateOf("root")
        var search by mutableStateOf<SourcePageRequest.Search?>(null, referentialEqualityPolicy())
        var searchRevision: String? = null
        val publicUiDemo = ExamplePublicUiDemoState()
    }

    private val roots = mapOf("home" to Root(), "my" to Root())
    private var activeRoot = "home"
    private val mutablePage = MutableStateFlow(SourcePageState())
    val pageState = mutablePage.asStateFlow()

    fun rootPage(route: SourceRoute): String = when (route.page) {
        "search" -> activeRoot
        "my" -> "my"
        else -> "home"
    }

    fun root(page: String): Root = roots.getValue(page)

    fun synchronize(route: SourceRoute) {
        if (route.arguments["active"] == "false") return
        activeRoot = rootPage(route)
        val root = root(activeRoot)
        if (route.page == "search") {
            val revision = route.arguments["requestRevision"].orEmpty()
            // 同一次宿主搜索请求只消费一次；关闭后须等下一次请求才重新打开。
            if (root.searchRevision != revision) {
                root.searchRevision = revision
                root.search = SourcePageRequest.Search(sourceId)
            }
        } else {
            root.search = null
        }
        publish()
    }

    fun navigate(page: String, screen: String) {
        val root = root(page)
        if (screen == "root") root.publicUiDemo.reset()
        root.screen = screen
        if (page == activeRoot) publish()
    }

    fun closeSearch(page: String, request: SourcePageRequest.Search) {
        if (root(page).search !== request) return
        root(page).search = null
        if (page == activeRoot) publish()
    }

    fun onBack(): Boolean {
        val root = root(activeRoot)
        if (root.search != null) {
            root.search = null
        } else if (root.screen == "public-ui" && root.publicUiDemo.onBack()) {
            return true
        } else if (root.screen != "root") {
            navigate(activeRoot, "root")
        } else return false
        publish()
        return true
    }

    fun reset() {
        roots.values.forEach {
            it.publicUiDemo.reset()
            it.screen = "root"
            it.search = null
            it.searchRevision = null
        }
        activeRoot = "home"
        publish()
    }

    private fun publish() {
        val root = root(activeRoot)
        mutablePage.value = SourcePageState(
            page = if (root.search != null) "search" else if (root.screen == "root") activeRoot else root.screen,
            atRoot = root.screen == "root" && root.search == null
        )
    }
}
