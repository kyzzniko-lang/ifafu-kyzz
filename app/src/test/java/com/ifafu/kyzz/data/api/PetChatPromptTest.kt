package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.Pet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私回归测试：宠物聊天 system prompt 绝不携带直接身份信息。
 * 若有人往 UserContext/PetPromptBuilder 加回姓名、学号、班级字段，这里会立即失败。
 */
class PetChatPromptTest {

    private val pet = Pet(name = "小农", petType = "cat", level = 3, mood = 90, hunger = 70)

    @Test
    fun `prompt 不含姓名学号班级等身份标记`() {
        val ctx = PetChatApi.UserContext(
            institute = "计算机与信息学院",
            todayCourses = listOf("高等数学(第1-2节,教学楼201)"),
            nextExam = "线性代数(3天后,考场A101)",
            recentScores = listOf("高等数学 88分"),
            gpaSummary = "平均分85.2，平均绩点3.45，共20门课",
            countdownEvents = listOf("距离四六级还有12天")
        )
        val prompt = PetPromptBuilder.build(pet, ctx)

        assertFalse(prompt.contains("姓名："))
        assertFalse(prompt.contains("学号："))
        assertFalse(prompt.contains("班级："))
        // 学业上下文仍应保留（粗粒度）
        assertTrue(prompt.contains("学院：计算机与信息学院"))
        assertTrue(prompt.contains("今日课程"))
        assertTrue(prompt.contains("最近考试"))
        assertTrue(prompt.contains("成绩概况"))
    }

    @Test
    fun `空上下文不产生主人信息块`() {
        val prompt = PetPromptBuilder.build(pet, PetChatApi.UserContext())
        assertFalse(prompt.contains("【主人信息】"))
    }

    @Test
    fun `笔记仅在显式提供时出现`() {
        val withNotes = PetPromptBuilder.build(
            pet,
            PetChatApi.UserContext(recentNotes = listOf("「复习计划」线代第三章"))
        )
        assertTrue(withNotes.contains("主人最近的笔记"))

        val withoutNotes = PetPromptBuilder.build(pet, PetChatApi.UserContext())
        assertFalse(withoutNotes.contains("主人最近的笔记"))
    }

    @Test
    fun `宠物人格与状态描述保留`() {
        val prompt = PetPromptBuilder.build(pet, PetChatApi.UserContext())
        assertTrue(prompt.contains("「小农」"))
        assertTrue(prompt.contains("Lv.3"))
        assertTrue(prompt.contains("主人"))
    }

    @Test
    fun `UserContext 不存在身份字段编译期保证`() {
        // 编译期防线：若身份字段被加回，此处立即失败
        val forbidden = setOf("userName", "studentId", "className")
        val names = PetChatApi.UserContext::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(
            "UserContext 禁止携带直接身份字段: ${names intersect forbidden}",
            (names intersect forbidden).isEmpty()
        )
    }
}
