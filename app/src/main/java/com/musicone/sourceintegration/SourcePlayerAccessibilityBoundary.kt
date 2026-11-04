package com.musicone.sourceintegration

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import java.util.IdentityHashMap

/** Compose 的虚拟节点动作直接进入节点提供者，不能依靠父 View 的触摸捕获。 */
internal class SourcePlayerAccessibilityBoundary(
    private val boundary: SourcePlayerInputBoundary,
    private val interactive: () -> Boolean,
) {
    private data class Installed(val original: AccessibilityDelegateCompat?, val guarded: AccessibilityDelegateCompat)
    private val installed = IdentityHashMap<View, Installed>()

    fun install(root: View) {
        val current = ViewCompat.getAccessibilityDelegate(root)
        if (installed[root]?.guarded !== current || !installed.containsKey(root)) {
            val guarded = Delegate(current)
            ViewCompat.setAccessibilityDelegate(root, guarded)
            installed[root] = Installed(current, guarded)
        }
        if (root is ViewGroup) for (index in 0 until root.childCount) install(root.getChildAt(index))
    }

    fun clear() {
        installed.forEach { (view, delegates) ->
            // 不覆盖包或 Compose 后来主动替换的新委托。
            if (ViewCompat.getAccessibilityDelegate(view) === delegates.guarded) {
                ViewCompat.setAccessibilityDelegate(view, delegates.original)
            }
        }
        installed.clear()
    }

    internal fun guardProvider(provider: AccessibilityNodeProviderCompat): AccessibilityNodeProviderCompat = Provider(provider)

    private inner class Delegate(private val original: AccessibilityDelegateCompat?) : AccessibilityDelegateCompat() {
        private val providers = IdentityHashMap<AccessibilityNodeProviderCompat, AccessibilityNodeProviderCompat>()

        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? = boundary.call(null) {
            val provider = original?.getAccessibilityNodeProvider(host) ?: return@call null
            providers.getOrPut(provider) { guardProvider(provider) }
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
            interactive() && boundary.call(false) {
                original?.performAccessibilityAction(host, action, args)
                    ?: super.performAccessibilityAction(host, action, args)
            }

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            boundary.call(Unit) {
                if (original != null) original.onInitializeAccessibilityNodeInfo(host, info)
                else super.onInitializeAccessibilityNodeInfo(host, info)
            }
        }

        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (original != null) original.onInitializeAccessibilityEvent(host, event)
            else super.onInitializeAccessibilityEvent(host, event)
        }
        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            if (original != null) original.onPopulateAccessibilityEvent(host, event)
            else super.onPopulateAccessibilityEvent(host, event)
        }
        override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
            original?.dispatchPopulateAccessibilityEvent(host, event) ?: super.dispatchPopulateAccessibilityEvent(host, event)
        override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean =
            original?.onRequestSendAccessibilityEvent(host, child, event) ?: super.onRequestSendAccessibilityEvent(host, child, event)
        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            if (original != null) original.sendAccessibilityEvent(host, eventType)
            else super.sendAccessibilityEvent(host, eventType)
        }
        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            if (original != null) original.sendAccessibilityEventUnchecked(host, event)
            else super.sendAccessibilityEventUnchecked(host, event)
        }
    }

    private inner class Provider(private val original: AccessibilityNodeProviderCompat) : AccessibilityNodeProviderCompat() {
        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean =
            interactive() && boundary.call(false) { original.performAction(virtualViewId, action, arguments) }
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat? =
            boundary.call(null) { original.createAccessibilityNodeInfo(virtualViewId) }
        override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfoCompat>? =
            boundary.call(null) { original.findAccessibilityNodeInfosByText(text, virtualViewId) }
        override fun findFocus(focus: Int): AccessibilityNodeInfoCompat? =
            boundary.call(null) { original.findFocus(focus) }
        override fun addExtraDataToAccessibilityNodeInfo(virtualViewId: Int, info: AccessibilityNodeInfoCompat,
            extraDataKey: String, arguments: Bundle?) {
            boundary.call(Unit) { original.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments) }
        }
    }
}
