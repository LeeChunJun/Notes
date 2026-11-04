package com.rhyme.lifecycle_learn.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 让根布局自动避开系统栏（状态栏 / 导航栏），配合 enableEdgeToEdge() 使用。
 */
fun View.applySystemBarInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
        insets
    }
}
