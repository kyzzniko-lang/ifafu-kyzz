package com.ifafu.kyzz.data.model

// 喂食/玩耍机制：每小时恢复1次，上限3次
data class Pet(
    var name: String = "小农",
    var petType: String = "cat",  // cat / dog / dragon / crab / calico / cloudling
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
    var feedCount: Int = 3,       // 当前可用喂食次数
    var playCount: Int = 3,       // 当前可用玩耍次数
    var lastFeedRecoverTime: Long = System.currentTimeMillis(), // 上次喂食恢复时间
    var lastPlayRecoverTime: Long = System.currentTimeMillis(), // 上次玩耍恢复时间
    var points: Int = 0,            // 签到积分
    var lastCheckInDate: String = "", // 上次签到日期 yyyy-MM-dd
    var checkInStreak: Int = 0,      // 连续签到天数
    var lastTickTime: Long = System.currentTimeMillis() // 上次 tick 时间
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
        "crab" -> when {
            level >= 30 -> "学霸Claude"
            level >= 20 -> "优等Claude"
            level >= 15 -> "努力Claude"
            level >= 10 -> "进步Claude"
            level >= 5 -> "学习Claude"
            else -> "Claude"
        }
        "calico" -> when {
            level >= 30 -> "学霸猫"
            level >= 20 -> "优等猫"
            level >= 15 -> "努力猫"
            level >= 10 -> "进步猫"
            level >= 5 -> "学习猫"
            else -> "小奶猫"
        }
        "cloudling" -> when {
            level >= 30 -> "学霸云"
            level >= 20 -> "优等云"
            level >= 15 -> "努力云"
            level >= 10 -> "进步云"
            level >= 5 -> "学习云"
            else -> "小云朵"
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
        const val MAX_CHARGE = 3
        const val RECOVER_INTERVAL_MS = 60 * 60 * 1000L // 1小时
    }

    fun recoverCharges() {
        val now = System.currentTimeMillis()
        // 恢复喂食次数
        val feedElapsed = now - lastFeedRecoverTime
        val feedRecovered = (feedElapsed / RECOVER_INTERVAL_MS).toInt()
        if (feedRecovered > 0 && feedCount < MAX_CHARGE) {
            feedCount = (feedCount + feedRecovered).coerceAtMost(MAX_CHARGE)
            lastFeedRecoverTime = now - (feedElapsed % RECOVER_INTERVAL_MS)
        }
        // 恢复玩耍次数
        val playElapsed = now - lastPlayRecoverTime
        val playRecovered = (playElapsed / RECOVER_INTERVAL_MS).toInt()
        if (playRecovered > 0 && playCount < MAX_CHARGE) {
            playCount = (playCount + playRecovered).coerceAtMost(MAX_CHARGE)
            lastPlayRecoverTime = now - (playElapsed % RECOVER_INTERVAL_MS)
        }
    }

    fun canFeed(): Boolean {
        recoverCharges()
        return feedCount > 0
    }

    fun canPlay(): Boolean {
        recoverCharges()
        return playCount > 0
    }

    fun remainingFeeds(): Int {
        recoverCharges()
        return feedCount
    }

    fun remainingPlays(): Int {
        recoverCharges()
        return playCount
    }

    fun nextFeedRecoverIn(): Long {
        recoverCharges()
        if (feedCount >= MAX_CHARGE) return 0
        return RECOVER_INTERVAL_MS - (System.currentTimeMillis() - lastFeedRecoverTime)
    }

    fun nextPlayRecoverIn(): Long {
        recoverCharges()
        if (playCount >= MAX_CHARGE) return 0
        return RECOVER_INTERVAL_MS - (System.currentTimeMillis() - lastPlayRecoverTime)
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
        recoverCharges()
        if (feedCount <= 0) return
        hunger = (hunger + 30).coerceAtMost(100)
        mood = (mood + 10).coerceAtMost(100)
        lastFeedTime = System.currentTimeMillis()
        feedCount--
        // 不要重置 lastFeedRecoverTime，保留部分充能恢复进度
        addExp(5)
    }

    fun play() {
        recoverCharges()
        if (playCount <= 0) return
        mood = (mood + 20).coerceAtMost(100)
        hunger = (hunger - 10).coerceAtLeast(0)
        lastPlayTime = System.currentTimeMillis()
        playCount--
        // 不要重置 lastPlayRecoverTime，保留部分充能恢复进度
        addExp(10)
    }

    fun tick() {
        val now = System.currentTimeMillis()
        if (now < lastTickTime) {
            lastTickTime = now
            return
        }
        val hoursSinceLastTick = ((now - lastTickTime) / (1000 * 60 * 60)).toInt()
        if (hoursSinceLastTick < 1) return

        if (now < lastFeedTime) {
            lastFeedTime = now
        } else {
            val hoursSinceLastFeed = ((now - lastFeedTime) / (1000 * 60 * 60)).toInt()
            if (hoursSinceLastFeed > 4) {
                val periods = ((hoursSinceLastFeed - 4) / 4).coerceIn(1, 30)
                hunger = (hunger - 5 * periods).coerceAtLeast(0)
            }
        }

        if (now < lastInteractTime) {
            lastInteractTime = now
        } else {
            val hoursSinceLastInteract = ((now - lastInteractTime) / (1000 * 60 * 60)).toInt()
            if (hoursSinceLastInteract > 8) {
                val periods = ((hoursSinceLastInteract - 8) / 8).coerceIn(1, 30)
                mood = (mood - 2 * periods).coerceAtLeast(0)
            }
        }

        if (hunger < 30) {
            mood = (mood - 3).coerceAtLeast(0)
        }
        lastTickTime = now
    }

    fun checkIn(): Boolean {
        val chinaZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
            timeZone = chinaZone
        }
        val today = sdf.format(java.util.Date())
        if (lastCheckInDate == today) return false
        val yesterday = run {
            val cal = java.util.Calendar.getInstance().apply { timeZone = chinaZone }
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            sdf.format(cal.time)
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
        val chinaZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).apply {
            timeZone = chinaZone
        }
        val today = sdf.format(java.util.Date())
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
