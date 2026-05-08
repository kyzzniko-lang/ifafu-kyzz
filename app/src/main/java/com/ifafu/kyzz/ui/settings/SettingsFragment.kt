package com.ifafu.kyzz.ui.settings

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.about.AboutActivity
import com.ifafu.kyzz.ui.comment.DiscussionActivity
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var cacheManager: CacheManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setupDarkMode()
        setupAccountInfo()
        setupSwitchAccount()
        setupTermFirstDay()
        setupClearCache()
        setupVersion()
        setupCheckUpdate()
        setupFeedback()
        setupAbout()
    }

    private fun setupDarkMode() {
        val pref = findPreference<androidx.preference.SwitchPreferenceCompat>("dark_mode") ?: return
        pref.isChecked = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        pref.setOnPreferenceChangeListener { _, newValue ->
            val mode = if (newValue == true) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
            requireContext().getSharedPreferences("ifafu_user", android.content.Context.MODE_PRIVATE)
                .edit().putInt("dark_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
            true
        }
    }

    private fun setupAccountInfo() {
        val pref = findPreference<Preference>("account_info") ?: return
        val user = userRepository.getUser()
        pref.summary = if (user.isLogin) "${user.name} · ${user.account}" else "未登录"
    }

    private fun setupSwitchAccount() {
        val pref = findPreference<Preference>("switch_account") ?: return
        pref.setOnPreferenceClickListener {
            showAccountSwitcher()
            true
        }
    }

    private fun showAccountSwitcher() {
        val profiles = userRepository.getAccountProfiles()
        val currentAccount = userRepository.getUser().account
        val items = profiles.map { p ->
            val tag = if (p.account == currentAccount) " (当前)" else ""
            "${p.name}${tag}\n${p.account}"
        }.toMutableList()
        items.add("+ 添加新账号")

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("切换账号")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < profiles.size) {
                    val profile = profiles[which]
                    if (profile.account != currentAccount) {
                        userRepository.switchAccount(profile)
                        Toast.makeText(requireContext(), "已切换到 ${profile.name}", Toast.LENGTH_SHORT).show()
                        startActivity(android.content.Intent(requireContext(), com.ifafu.kyzz.ui.login.LoginActivity::class.java))
                        requireActivity().finish()
                    }
                } else {
                    startActivity(android.content.Intent(requireContext(), com.ifafu.kyzz.ui.login.LoginActivity::class.java))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupTermFirstDay() {
        val pref = findPreference<Preference>("term_first_day") ?: return
        updateTermFirstDaySummary(pref)
        pref.setOnPreferenceClickListener {
            showTermFirstDayPicker(pref)
            true
        }
    }

    private fun updateTermFirstDaySummary(pref: Preference) {
        val existing = userRepository.termFirstDay
        pref.summary = if (existing.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val date = sdf.parse(existing)
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date!!)
            } catch (_: Exception) { "已设置" }
        } else {
            "未设置（自动推算）"
        }
    }

    private fun showTermFirstDayPicker(pref: Preference) {
        val cal = Calendar.getInstance()
        val existing = userRepository.termFirstDay
        if (existing.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                sdf.parse(existing)?.let { cal.time = it }
            } catch (_: Exception) {}
        }

        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                userRepository.termFirstDay = sdf.format(selected.time)
                userRepository.termFirstDayManual = true
                updateTermFirstDaySummary(pref)
                Toast.makeText(requireContext(), "学期首日已设置为 $year-${month + 1}-$dayOfMonth", Toast.LENGTH_SHORT).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setupClearCache() {
        val pref = findPreference<Preference>("clear_cache") ?: return
        updateCacheSummary(pref)
        pref.setOnPreferenceClickListener {
            cacheManager.clearAll()
            updateCacheSummary(pref)
            Toast.makeText(requireContext(), "缓存已清除", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun updateCacheSummary(pref: Preference) {
        val size = cacheManager.getCacheSize()
        pref.summary = if (size > 0) "当前缓存 ${formatSize(size)}" else "无缓存"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
        }
    }

    private fun setupVersion() {
        val pref = findPreference<Preference>("version") ?: return
        try {
            val pm = requireContext().packageManager
            val info = pm.getPackageInfo(requireContext().packageName, 0)
            pref.summary = "${info.versionName} (${info.longVersionCode})"
        } catch (_: Exception) {
            pref.summary = "1.0.0"
        }
    }

    private fun setupCheckUpdate() {
        val pref = findPreference<Preference>("check_update") ?: return
        pref.setOnPreferenceClickListener {
            Toast.makeText(requireContext(), "正在检查更新...", Toast.LENGTH_SHORT).show()
            UpdateChecker.checkForUpdate(requireContext()) { release ->
                activity?.runOnUiThread {
                    if (release != null) {
                        showUpdateDialog(release)
                    } else {
                        Toast.makeText(requireContext(), "已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
    }

    private fun showUpdateDialog(release: UpdateChecker.ReleaseInfo) {
        val sizeText = release.apkAsset?.let { " (${UpdateChecker.formatSize(it.size)})" } ?: ""
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("发现新版本 v${release.versionName}")
            .setMessage("${release.body ?: "修复已知问题并优化体验"}\n\n大小: $sizeText")
            .setPositiveButton("立即更新") { _, _ ->
                UpdateChecker.downloadAndInstall(requireContext(), release)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun setupFeedback() {
        val pref = findPreference<Preference>("feedback") ?: return
        pref.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), DiscussionActivity::class.java))
            true
        }
    }

    private fun setupAbout() {
        val pref = findPreference<Preference>("about") ?: return
        pref.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), AboutActivity::class.java))
            true
        }
    }
}
