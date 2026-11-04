package com.rhyme.lifecycle_learn

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rhyme.lifecycle_learn.common.LifecycleEventLog
import com.rhyme.lifecycle_learn.common.applySystemBarInsets
import com.rhyme.lifecycle_learn.countdown.CountDownActivity
import com.rhyme.lifecycle_learn.databinding.ActivityLifecycleLearnBinding
import com.rhyme.lifecycle_learn.dialog.DialogDemoActivity
import kotlinx.coroutines.launch

/**
 * lifecycle_learn 模块首页：两个实践例子的入口。
 *
 * 这里同时演示了三种最常用的 Lifecycle 用法：
 * 1. addObserver 观察生命周期事件；
 * 2. repeatOnLifecycle(STARTED) 只在页面可见时收集数据；
 * 3. lifecycleScope 随宿主销毁自动取消协程。
 */
class LifecycleLearnActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLifecycleLearnBinding
    private val lifecycleLog = LifecycleEventLog()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLifecycleLearnBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        // 观察本页的生命周期事件
        lifecycleLog.observe(this)

        binding.btnCountDownDemo.setOnClickListener {
            startActivity(Intent(this, CountDownActivity::class.java))
        }
        binding.btnDialogDemo.setOnClickListener {
            startActivity(Intent(this, DialogDemoActivity::class.java))
        }

        // 只有页面处于 STARTED 状态时才去收集数据，后台不更新 UI
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                lifecycleLog.events.collect { events ->
                    binding.tvLifecycleLog.text = events.joinToString("\n")
                }
            }
        }
    }

    override fun onDestroy() {
        lifecycleLog.removeObserver(this)
        super.onDestroy()
    }
}
