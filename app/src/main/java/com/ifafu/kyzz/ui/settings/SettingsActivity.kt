package com.ifafu.kyzz.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ifafu.kyzz.databinding.ActivitySettingsBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(com.ifafu.kyzz.R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}
