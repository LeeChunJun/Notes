package com.rhyme.lifecycle_learn.dialog

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rhyme.lifecycle_learn.databinding.DialogLifecycleSafeBinding

/**
 * 生命周期感知的 Dialog。
 *
 * ## 要解决的问题
 * 常见的写法是：Activity 里 `dialog.show()`，然后指望 Activity 的 onDestroy() 里 `dialog.dismiss()`。
 * 但 onDestroy() 已经太晚了 —— 此时 Activity 的窗口已经被移除，而弹窗的窗口还挂在旧 token 上，
 * 轻则弹窗瞬间消失、回调不执行，重则直接抛出：
 * `android.view.WindowLeaked: Activity xxx has leaked window android.view.Window$BadTokenException`。
 * 屏幕旋转、用户快速返回、进程被回收前的回调，都会命中这个问题。
 *
 * ## 优化方式
 * 给弹窗本身一个**自定义的 LifecycleOwner**（见 [LifecycleDialogOwner]），
 * 把宿主 Activity 的生命周期转发进去；宿主一旦销毁，弹窗自己的生命周期会走到 ON_DESTROY，
 * 弹窗在这里立刻 dismiss()，窗口在 Activity 窗口销毁之前就被安全回收。
 */
class LifecycleAwareDialog(context: Context) {

    /** 弹窗自己的自定义 LifecycleOwner */
    val dialogOwner = LifecycleDialogOwner()

    private val alertDialog: AlertDialog

    /** 弹窗关闭回调 */
    var onDismissed: (() -> Unit)? = null

    init {
        val binding = DialogLifecycleSafeBinding.inflate(LayoutInflater.from(context))
        alertDialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .create()

        binding.btnConfirm.setOnClickListener { dismiss() }

        alertDialog.setOnDismissListener { onDismissed?.invoke() }

        // 自定义生命周期走到 ON_DESTROY => 说明宿主已被销毁 => 必须立刻关闭弹窗
        dialogOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dismiss()
            }
        })
    }

    val isShowing: Boolean get() = alertDialog.isShowing

    /**
     * 把弹窗挂到宿主的生命周期上。
     * 必须先调用 [attachTo] 再 [show]，宿主销毁时弹窗才会自动关闭。
     */
    fun attachTo(host: LifecycleOwner) {
        dialogOwner.bindTo(host)
    }

    fun show() {
        alertDialog.show()
    }

    /** 关闭弹窗，并同步销毁自定义生命周期 */
    fun dismiss() {
        dialogOwner.destroy()
        if (alertDialog.isShowing) {
            alertDialog.dismiss()
        }
    }
}
