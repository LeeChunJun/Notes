package com.rhyme.lifecycle_learn.countdown

import android.os.CountDownTimer
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 倒计时的状态 */
enum class CountDownStatus {
    IDLE,
    RUNNING,
    PAUSED,
    FINISHED,
    SKIPPED
}

/** 倒计时页面需要渲染的数据 */
data class CountDownUiState(
    val remainingMillis: Long,
    val status: CountDownStatus
)

/**
 * 页面倒计时 ViewModel。
 *
 * - 计时由 [CountDownTimer] 实现（Android 自带的倒计时工具）；
 * - 剩余时间保存在 ViewModel 里，屏幕旋转导致 Activity 重建时不会从头开始；
 * - 计时器的启动 / 暂停由 Activity 的生命周期驱动：
 *   页面可见（ON_START）才开始跑，页面不可见（ON_STOP）立即暂停，
 *   这样既不会在后台空转消耗电量，也不会出现 Activity 泄漏（CountDownTimer 内部持有 Handler，
 *   如果不取消，Activity 销毁后定时任务还会继续跑，Handler 所在的 Looper 一直持有 Activity）。
 */
class CountDownViewModel : ViewModel() {

    companion object {
        const val TOTAL_SECONDS = 10
        private const val TICK_INTERVAL_MS = 1000L
        private const val TOTAL_MILLIS = TOTAL_SECONDS * 1000L
    }

    private val _uiState = MutableStateFlow(
        CountDownUiState(remainingMillis = TOTAL_MILLIS, status = CountDownStatus.IDLE)
    )
    val uiState: StateFlow<CountDownUiState> = _uiState.asStateFlow()

    private var timer: CountDownTimer? = null

    /** 开始（或从暂停处继续）倒计时 */
    fun start() {
        if (timer != null) return

        val current = _uiState.value
        if (current.status == CountDownStatus.FINISHED || current.status == CountDownStatus.SKIPPED) return
        if (current.remainingMillis <= 0L) {
            finishCountDown(CountDownStatus.FINISHED)
            return
        }

        timer = object : CountDownTimer(current.remainingMillis, TICK_INTERVAL_MS) {
            override fun onTick(millisUntilFinished: Long) {
                _uiState.update { it.copy(remainingMillis = millisUntilFinished) }
            }

            override fun onFinish() {
                timer = null
                finishCountDown(CountDownStatus.FINISHED)
            }
        }.also { it.start() }

        _uiState.update { it.copy(status = CountDownStatus.RUNNING) }
    }

    /**
     * 暂停倒计时。
     * CountDownTimer 本身不支持暂停，这里用 "cancel + 记录剩余时间" 的方式实现暂停，
     * 下次 [start] 时会用剩余时间重新创建一个 CountDownTimer。
     */
    fun pause() {
        timer?.cancel()
        timer = null
        _uiState.update {
            if (it.status == CountDownStatus.RUNNING) it.copy(status = CountDownStatus.PAUSED) else it
        }
    }

    /** 彻底停止并释放定时器（Activity 真正结束、ViewModel 被回收时调用） */
    fun release() {
        timer?.cancel()
        timer = null
    }

    /** 跳过倒计时 */
    fun skip() {
        release()
        _uiState.value = CountDownUiState(remainingMillis = 0L, status = CountDownStatus.SKIPPED)
    }

    /** 重新开始一轮倒计时 */
    fun restart() {
        release()
        _uiState.value = CountDownUiState(remainingMillis = TOTAL_MILLIS, status = CountDownStatus.IDLE)
        start()
    }

    private fun finishCountDown(status: CountDownStatus) {
        _uiState.value = CountDownUiState(remainingMillis = 0L, status = status)
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }
}
