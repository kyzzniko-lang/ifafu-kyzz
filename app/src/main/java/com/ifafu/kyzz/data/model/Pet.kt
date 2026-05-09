package com.ifafu.kyzz.data.model

// 喂食/玩耍机制：每天各限3次，凌晨0点重置次数
data class Pet(
    var name: String = "小农",
    var petType: String = "cat",  // cat / dog / dragon
    var level: Int = 1,
    var exp: Int = 0,
    var mood: Int = 80,        // 0-100 心情值
    var hunger: Int = 60,      // 0-100 饱食度（越高越饱）
    var state: PetState = PetState.IDLE,
    var lastFeedTime: Long = 0L,
    var lastPlayTime: Long = 0L,
    var lastInteractTime: Long = 0L,
    var totalDays: Int = 1,
    var gradeAdviceShown: Boolean = false,
    var feedCountToday: Int = 0,   // 今日喂食次数
    var playCountToday: Int = 0,   // 今日玩耍次数
    var countResetDate: String = "", // 次数重置日期标记（yyyy-MM-dd）
    var points: Int = 0,            // 签到积分
    var lastCheckInDate: String = "", // 上次签到日期 yyyy-MM-dd
    var checkInStreak: Int = 0       // 连续签到天数
) {
    val expToNextLevel: Int get() = level * 50 + 100
    val levelTitle: String get() = when (petType) {
        "dog" -> when {
            level >= 30 -> "学霸犬"
            level >= 20 -> "优等犬"
            level >= 15 -> "努力犬"
            level >= 10 -> "进步犬"
            level >= 5 -> "学习犬"
            else -> "小奶狗"
        }
        "dragon" -> when {
            level >= 30 -> "学霸龙"
            level >= 20 -> "优等龙"
            level >= 15 -> "努力龙"
            level >= 10 -> "进步龙"
            level >= 5 -> "学习龙"
            else -> "小龙崽"
        }
        else -> when {
            level >= 30 -> "学霸猫"
            level >= 20 -> "优等猫"
            level >= 15 -> "努力猫"
            level >= 10 -> "进步猫"
            level >= 5 -> "学习猫"
            else -> "小奶猫"
        }
    }

    companion object {
        const val MAX_DAILY_FEED = 3
        const val MAX_DAILY_PLAY = 3
    }

    private fun ensureDailyReset() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        if (countResetDate != today) {
            feedCountToday = 0
            playCountToday = 0
            countResetDate = today
        }
    }

    fun canFeed(): Boolean {
        ensureDailyReset()
        return feedCountToday < MAX_DAILY_FEED
    }

    fun canPlay(): Boolean {
        ensureDailyReset()
        return playCountToday < MAX_DAILY_PLAY
    }

    fun remainingFeeds(): Int {
        ensureDailyReset()
        return (MAX_DAILY_FEED - feedCountToday).coerceAtLeast(0)
    }

    fun remainingPlays(): Int {
        ensureDailyReset()
        return (MAX_DAILY_PLAY - playCountToday).coerceAtLeast(0)
    }

    fun addExp(amount: Int): Boolean {
        exp += amount
        var leveled = false
        while (exp >= expToNextLevel) {
            exp -= expToNextLevel
            level++
            leveled = true
        }
        return leveled
    }

    fun feed() {
        ensureDailyReset()
        hunger = (hunger + 30).coerceAtMost(100)
        mood = (mood + 10).coerceAtMost(100)
        lastFeedTime = System.currentTimeMillis()
        feedCountToday++
        addExp(5)
    }

    fun play() {
        ensureDailyReset()
        mood = (mood + 20).coerceAtMost(100)
        hunger = (hunger - 10).coerceAtLeast(0)
        lastPlayTime = System.currentTimeMillis()
        playCountToday++
        addExp(10)
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val hoursSinceLastFeed = (now - lastFeedTime) / (1000 * 60 * 60)
        val hoursSinceLastInteract = (now - lastInteractTime) / (1000 * 60 * 60)
        if (hoursSinceLastFeed > 4) {
            hunger = (hunger - 5).coerceAtLeast(0)
        }
        if (hoursSinceLastInteract > 8) {
            mood = (mood - 2).coerceAtLeast(0)
        }
        if (hunger < 30) {
            mood = (mood - 3).coerceAtLeast(0)
        }
    }

    fun checkIn(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        if (lastCheckInDate == today) return false
        val yesterday = run {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(cal.time)
        }
        checkInStreak = if (lastCheckInDate == yesterday) checkInStreak + 1 else 1
        val bonus = when {
            checkInStreak >= 7 -> 20
            checkInStreak >= 3 -> 10
            else -> 5
        }
        points += bonus
        lastCheckInDate = today
        addExp(15)
        return true
    }

    fun isCheckedInToday(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        return lastCheckInDate == today
    }

    val colorTier: Int get() = when {
        level >= 20 -> 2  // terracotta orange
        level >= 10 -> 1  // yellow
        else -> 0         // white/default
    }
}

enum class PetState(val label: String) {
    IDLE("发呆中"),
    STUDYING("学习中"),
    SLEEPING("睡觉中"),
    EATING("吃东西"),
    HAPPY("开心~"),
    SAD("难过中"),
    EXCITED("超兴奋!"),
    TIRED("好累啊"),
    HUNGRY("肚子饿了"),
    WORRY("担心成绩"),
    EXAM_REMIND("考试提醒")
}
