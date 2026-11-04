package com.rhyme.lifecycle_learn.dialog

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 自定义 LifecycleOwner。
 *
 * 背景：Android 只把 Activity、Fragment、Service、ViewModelStoreOwner 等框架组件当成
 * LifecycleOwner，普通对象（例如一个 Dialog）想"感知生命周期"是没有现成能力的。
 *
 * 做法：自己实现一个 LifecycleOwner，内部用 [LifecycleRegistry] 手动维护一份生命周期，
 * 再把宿主的生命周期事件转发过来。于是：
 *
 * ```
 * 宿主 Activity:  ON_CREATE -> ON_START -> ON_RESUME -> ON_PAUSE -> ON_STOP -> ON_DESTROY
 *                       |
 *                       | 转发
 *                       v
 * 自定义 Owner:  ON_CREATE -> ON_START -> ON_RESUME -> ON_PAUSE -> ON_STOP -> ON_DESTROY
 * ```
 *
 * 宿主销毁时，自定义 Owner 一样会收到 ON_DESTROY，弹窗就可以据此把自己关掉。
 */
class LifecycleDialogOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    private var host: LifecycleOwner? = null
    private var hostObserver: LifecycleEventObserver? = null

    override val lifecycle: Lifecycle get() = registry

    /** 当前自定义生命周期的状态 */
    val currentState: Lifecycle.State get() = registry.currentState

    /**
     * 把自定义 owner 挂到宿主的生命周期上：宿主的事件会被转发过来。
     */
    fun bindTo(hostOwner: LifecycleOwner) {
        unbind()
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                registry.handleLifecycleEvent(event)
            }
        }
        host = hostOwner
        hostObserver = observer
        hostOwner.lifecycle.addObserver(observer)
    }

    /** 解绑，避免继续持有宿主造成内存泄漏 */
    fun unbind() {
        val currentHost = host
        val currentObserver = hostObserver
        if (currentHost != null && currentObserver != null) {
            currentHost.lifecycle.removeObserver(currentObserver)
        }
        host = null
        hostObserver = null
    }

    /** 手动销毁自定义生命周期，会把 ON_DESTROY 派发给所有观察者 */
    fun destroy() {
        unbind()
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
