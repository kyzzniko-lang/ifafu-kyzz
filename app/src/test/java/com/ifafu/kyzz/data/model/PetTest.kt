package com.ifafu.kyzz.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宠物饥饿/心情衰减的增量口径回归测试。
 *
 * 背景：tick() 旧实现每次都按"距上次喂食/互动"的全额周期数重扣 hunger/mood，
 * 而 PetViewModel 在宠物页常驻时每分钟调用一次 tick()（每小时生效一次），
 * 同一时段被反复扣减，24 小时常驻可把衰减放大到设计值的数倍。
 * 修复后以 lastTickTime 为游标，只补扣"自上次 tick 起新跨过的完整周期数"。
 *
 * 时间语义说明：tick() 内部取 System.currentTimeMillis()，测试通过把宠物各
 * 时间戳回拨到相对当前时刻的虚拟时刻来模拟时钟推进；语句间隔远小于 1 小时，
 * 整除取整不受影响。
 */
class PetTest {

    private val HOUR = 60L * 60 * 1000

    /**
     * 场景一：宠物页常驻 24 小时（每小时 tick 一次，期间未喂食/未互动）。
     * 设计衰减：饱食度 4 小时起每满 4 小时扣 5 => 24h 共 (24-4)/4 = 5 个周期，
     * hunger 60-25=35；心情 8 小时起每满 8 小时扣 2 => 2 个周期，mood 80-4=76。
     * 旧实现逐次全额重扣，hunger 早在 ~17 小时就被扣穿到 0。
     */
    @Test
    fun residentPageFor24Hours_deductsEachPeriodOnce() {
        val pet = Pet(hunger = 60, mood = 80)
        for (i in 1..24) {
            val now = System.currentTimeMillis()
            // 第 i 次 tick 的虚拟时刻 = 喂食/互动后第 i 小时；上一次 tick 在 1 小时前
            pet.lastTickTime = now - HOUR
            pet.lastFeedTime = now - i * HOUR
            pet.lastInteractTime = now - i * HOUR
            pet.tick()
        }
        assertEquals(35, pet.hunger)
        assertEquals(76, pet.mood)
    }

    /**
     * 场景二：断续打开 App——关闭 24 小时后打开补扣一次，随后常驻页面上
     * 后续的每小时 tick 不得对已扣过的时段重复扣减。
     */
    @Test
    fun intermittentOpen_deductsCatchUpOnlyOnce() {
        val pet = Pet(hunger = 60, mood = 80)
        val now = System.currentTimeMillis()
        pet.lastFeedTime = now - 24 * HOUR
        pet.lastInteractTime = now - 24 * HOUR
        pet.lastTickTime = now - 24 * HOUR
        // 24 小时后首次打开：一次性追平，与场景一总额一致
        pet.tick()
        assertEquals(35, pet.hunger)
        assertEquals(76, pet.mood)

        // 紧接着的第二次 tick（不足 1 小时）：直接早退，不扣
        pet.tick()
        assertEquals(35, pet.hunger)

        // 1 小时后的又一次 tick：距喂食 25 小时仍只有 5 个完整周期，
        // 不得再次扣 5×5（旧实现会把 hunger 再扣 25 到 10）。
        // tick() 取真实时钟，模拟"真实过了 1 小时"需把喂食/互动锚点一并
        // 回拨到 25 小时前：只回拨 lastTickTime 会让虚拟时间线自相矛盾
        // （游标落在距喂食 23h 处，23h→24h 跨过第 5 个周期边界，补扣 1
        // 次恰恰是增量口径的正确行为，而非"重复扣减"）。
        val now3 = System.currentTimeMillis()
        pet.lastTickTime = now3 - HOUR
        pet.lastFeedTime = now3 - 25 * HOUR
        pet.lastInteractTime = now3 - 25 * HOUR
        pet.tick()
        assertEquals(35, pet.hunger)
        assertEquals(76, pet.mood)
    }
}
