package com.rhyme.lifecycle_learn.common

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 生命周期事件记录器。
 *
 * 用途：把宿主的 Lifecycle 事件实时显示在页面上，方便观察 Activity 的生命周期流转，
 * 这正是"使用 lifecycle 来检测页面生命周期"的最小实现：
 * 只要往 Lifecycle 上注册一个观察者就能拿到所有事件。
 */
class LifecycleEventLog(private val maxSize: Int = 12) {

    private val _events = MutableStateFlow<List<String>>(emptyList())

    /** 最新事件排在最后 */
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private val observer = object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            _events.value = (_events.value + event.name).takeLast(maxSize)
        }
    }

    /** 开始观察宿主的生命周期 */
    fun observe(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(observer)
    }

    /** 停止观察，避免宿主销毁后仍被持有 */
    fun removeObserver(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(observer)
    }
}
