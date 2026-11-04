package com.rhyme.lifecycle_learn.countdown

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rhyme.lifecycle_learn.R
import com.rhyme.lifecycle_learn.common.LifecycleEventLog
import com.rhyme.lifecycle_learn.common.applySystemBarInsets
import com.rhyme.lifecycle_learn.databinding.ActivityCountDownBinding
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 示例一：常见的页面倒计时（开屏广告 / 引导页跳过倒计时）。
 *
 * 关键点：
 * 1. 用 [android.os.CountDownTimer] 实现计时；
 * 2. 用 Lifecycle 检测页面生命周期，页面可见才计时、不可见就暂停、销毁就释放；
 * 3. 点击倒计时数字或"跳过"按钮都可以立刻结束倒计时。
 */
class CountDownActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCountDownBinding
    private val viewModel: CountDownViewModel by viewModels()
    private val lifecycleLog = LifecycleEventLog()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCountDownBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        lifecycleLog.observe(this)

        // 用 Lifecycle 检测页面生命周期，从而决定 CountDownTimer 的启停
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // 页面可见：开始 / 继续倒计时
                viewModel.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                // 页面不可见：暂停倒计时，避免后台空转
                viewModel.pause()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                // 页面销毁：释放定时器，避免 CountDownTimer 持有 Activity 导致泄漏
                viewModel.release()
            }
        })

        // 点击倒计时数字 = 跳过
        binding.tvCountDown.setOnClickListener { viewModel.skip() }
        // 点击"跳过"按钮 = 跳过
        binding.btnSkip.setOnClickListener { viewModel.skip() }
        binding.btnRestart.setOnClickListener { viewModel.restart() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderCountDown(state)
                    }
                }
                launch {
                    lifecycleLog.events.collect { events ->
                        binding.tvLifecycleLog.text = events.joinToString("\n")
                    }
                }
            }
        }
    }

    private fun renderCountDown(state: CountDownUiState) {
        val seconds = ceil(state.remainingMillis / 1000.0).toInt()
        binding.tvCountDown.text = seconds.toString()

        val progress = if (state.remainingMillis > 0) {
            (state.remainingMillis * 100 / (CountDownViewModel.TOTAL_SECONDS * 1000L)).toInt()
        } else {
            0
        }
        binding.progressCountDown.progress = progress

        binding.tvStatus.setText(
            when (state.status) {
                CountDownStatus.IDLE -> R.string.count_down_idle
                CountDownStatus.RUNNING -> R.string.count_down_running
                CountDownStatus.PAUSED -> R.string.count_down_paused
                CountDownStatus.FINISHED -> R.string.count_down_finished
                CountDownStatus.SKIPPED -> R.string.count_down_skipped
            }
        )

        val canSkip = state.status != CountDownStatus.FINISHED && state.status != CountDownStatus.SKIPPED
        binding.btnSkip.isEnabled = canSkip
        binding.tvCountDown.isEnabled = canSkip
    }

    override fun onDestroy() {
        lifecycleLog.removeObserver(this)
        super.onDestroy()
    }
}
