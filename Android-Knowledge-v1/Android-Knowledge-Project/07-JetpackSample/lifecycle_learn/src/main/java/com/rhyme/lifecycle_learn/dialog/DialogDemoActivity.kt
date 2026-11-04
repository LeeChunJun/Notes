package com.rhyme.lifecycle_learn.dialog

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rhyme.lifecycle_learn.R
import com.rhyme.lifecycle_learn.common.LifecycleEventLog
import com.rhyme.lifecycle_learn.common.applySystemBarInsets
import com.rhyme.lifecycle_learn.databinding.ActivityDialogDemoBinding
import com.rhyme.lifecycle_learn.databinding.DialogLifecycleSafeBinding
import kotlinx.coroutines.launch

/**
 * 示例二：用自定义 LifecycleOwner 解决"弹窗在 Activity 销毁时没有及时关闭"的问题。
 *
 * 对比两种写法：
 * - 旧写法：弹窗和 Activity 的生命周期完全脱节，Activity 先销毁就会残留窗口（WindowLeaked）；
 * - 优化写法：弹窗持有自定义 LifecycleOwner，宿主销毁 -> 自定义 owner 收到 ON_DESTROY -> 弹窗自动 dismiss。
 *
 * 验证方式：显示弹窗后点击"销毁当前 Activity"，安全版弹窗会被自动关闭；
 * 旧写法则会残留（在 logcat 里可以看到 WindowLeaked 的警告）。
 */
class DialogDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialogDemoBinding
    private val lifecycleLog = LifecycleEventLog()

    private var safeDialog: LifecycleAwareDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDialogDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        lifecycleLog.observe(this)

        binding.btnShowSafeDialog.setOnClickListener { showSafeDialog() }
        binding.btnShowLegacyDialog.setOnClickListener { showLegacyDialog() }
        binding.btnFinishActivity.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                lifecycleLog.events.collect { events ->
                    binding.tvLifecycleLog.text = events.joinToString("\n")
                }
            }
        }
    }

    /** 优化写法：弹窗挂到 Activity 的生命周期上，Activity 销毁时弹窗自动关闭 */
    private fun showSafeDialog() {
        val dialog = LifecycleAwareDialog(this)
        // 关键一步：把弹窗绑定到宿主的生命周期
        dialog.attachTo(this)
        dialog.onDismissed = {
            binding.tvDialogState.setText(R.string.dialog_state_dismissed)
        }
        safeDialog = dialog
        dialog.show()
        binding.tvDialogState.setText(R.string.dialog_state_shown)
    }

    /** 旧写法：弹窗与宿主生命周期脱节，Activity 销毁后窗口会残留 */
    private fun showLegacyDialog() {
        val view = DialogLifecycleSafeBinding.inflate(LayoutInflater.from(this)).root
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()
        dialog.setOnDismissListener {
            binding.tvDialogState.setText(R.string.dialog_state_dismissed)
        }
        dialog.show()
        binding.tvDialogState.setText(R.string.dialog_state_shown)
    }

    override fun onDestroy() {
        lifecycleLog.removeObserver(this)
        super.onDestroy()
    }
}
