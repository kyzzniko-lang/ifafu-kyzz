package com.ifafu.kyzz.ui.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB

    abstract fun createBinding(): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        // On some devices (e.g. iQOO), restored fragments conflict with Hilt injection
        // and cause alternating crash patterns (1st OK, 2nd crash, 3rd OK...).
        // Try restoring normally first; fall back to null only if restoration crashes.
        try {
            super.onCreate(savedInstanceState)
        } catch (_: Exception) {
            super.onCreate(null)
        }
        binding = createBinding()
        setContentView(binding.root)
    }
}
