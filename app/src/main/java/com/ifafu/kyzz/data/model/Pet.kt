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
    // 默认值必须是"现在"而非 0：0（1970 年）会让 tick() 一次性扣掉几十年
    // 的衰减量，新宠物创建 1 小时后饱食度/心情直接见底。Gson 反序列化
    // 不走构造函数，旧 JSON 缺字段时仍会是 0，由 tick() 里的零值防护兜底。
    var lastFeedTime: Long = System.currentTimeMillis(),
    var lastPlayTime: Long = 0L,
    var lastInteractTime: Long = System.currentTimeMillis(),
    var totalDays: Int = 1,
    var gradeAdviceShown: Boolean = false,
    var feedCount: Int = 3,       // 当前可用喂食次数
    var playCount: Int = 3,       // 当前可用玩耍次数
    var lastFeedRecoverTime: Long = System.currentTimeMillis(), // 上次喂食恢复时间
    var lastPlayRecoverTime: Long = System.currentTimeMillis(), // 上次玩耍恢复时间
    var points: Int = 0,            // 签到积分
    var lastCheckInDate: String = "", // 上次签到日期 yyyy-MM-dd
    // 上次签到的单调时间戳，防系统时间回拨刷签到；旧存档缺该字段时反序列化为 0，不影响
    var lastCheckInTime: Long = 0L,
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
        if (feedCount >= MAX_CHARGE) {
            // 满额时冻结恢复时钟：否则 elapsed 一直累积，消耗一次后
            // 下一次恢复判定会把次数瞬间回满，违背"每小时恢复 1 次"。
            lastFeedRecoverTime = now
        } else {
            val feedElapsed = now - lastFeedRecoverTime
            val feedRecovered = (feedElapsed / RECOVER_INTERVAL_MS).toInt()
            if (feedRecovered > 0) {
                feedCount = (feedCount + feedRecovered).coerceAtMost(MAX_CHARGE)
                lastFeedRecoverTime = now - (feedElapsed % RECOVER_INTERVAL_MS)
            }
        }
        // 恢复玩耍次数
        if (playCount >= MAX_CHARGE) {
            lastPlayRecoverTime = now
        } else {
            val playElapsed = now - lastPlayRecoverTime
            val playRecovered = (playElapsed / RECOVER_INTERVAL_MS).toInt()
            if (playRecovered > 0) {
                playCount = (playCount + playRecovered).coerceAtMost(MAX_CHARGE)
                lastPlayRecoverTime = now - (playElapsed % RECOVER_INTERVAL_MS)
            }
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
        // Gson 反序列化不走构造函数，旧版本 JSON 缺字段时会得到 0（1970 年），
        // 不重置的话衰减量会按几十年计算，宠物一次 tick 就"饿死"。
        if (lastTickTime <= 0L) lastTickTime = now
        if (lastFeedTime <= 0L) lastFeedTime = now
        if (lastInteractTime <= 0L) lastInteractTime = now
        if (now < lastTickTime) {
            lastTickTime = now
            return
        }
        val hoursSinceLastTick = ((now - lastTickTime) / (1000 * 60 * 60)).toInt()
        if (hoursSinceLastTick < 1) return

        // 衰减为增量口径(游标=lastTickTime):每次 tick 只补扣"自上次 tick 起
        // 新跨过的完整周期数"。hunger/mood 是逐次累减的有状态值,旧实现每次
        // tick 都按"距上次喂食/互动"的全额周期数重扣,宠物页常驻时(每分钟
        // tick、每小时生效一次)同一时段被反复扣减,衰减达设计值数倍;改为
        // 增量后断续打开 App 也只对每个周期补扣一次,与一次性追平结果一致。
        if (now < lastFeedTime) {
            lastFeedTime = now
        } else {
            val hungerPeriodsAt: (Long) -> Int = { at ->
                val hours = ((at - lastFeedTime) / (1000 * 60 * 60)).toInt()
                // 喂食后 4 小时起每满 4 小时扣 5 点；不足一个完整周期不扣。
                if (hours > 4) ((hours - 4) / 4).coerceAtMost(30) else 0
            }
            val newPeriods = hungerPeriodsAt(now) - hungerPeriodsAt(lastTickTime)
            if (newPeriods > 0) {
                hunger = (hunger - 5 * newPeriods).coerceAtLeast(0)
            }
        }

        if (now < lastInteractTime) {
            lastInteractTime = now
        } else {
            val moodPeriodsAt: (Long) -> Int = { at ->
                val hours = ((at - lastInteractTime) / (1000 * 60 * 60)).toInt()
                // 互动后 8 小时起每满 8 小时扣 2 点；不足一个完整周期不扣。
                if (hours > 8) ((hours - 8) / 8).coerceAtMost(30) else 0
            }
            val newPeriods = moodPeriodsAt(now) - moodPeriodsAt(lastTickTime)
            if (newPeriods > 0) {
                mood = (mood - 2 * newPeriods).coerceAtLeast(0)
            }
        }

        if (hunger < 30) {
            mood = (mood - 3).coerceAtLeast(0)
        }
        lastTickTime = now
    }

    fun checkIn(): Boolean {
        val now = System.currentTimeMillis()
        // 除日期串外再校验单调时间戳：把系统时间回拨一天再拨回来，"today !=
        // lastCheckInDate"会再次成立，签到(积分+经验+连签)就能无限刷
        if (now <= lastCheckInTime) return false
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
        lastCheckInTime = now
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
