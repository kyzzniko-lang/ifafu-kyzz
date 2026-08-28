package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.Pet
import com.ifafu.kyzz.data.model.PetState

/**
 * 宠物聊天 system prompt 构建器（纯函数，脱离 Context，可单测）。
 *
 * 隐私约定：不携带姓名/学号/班级等直接身份信息——宠物以"主人"称呼用户，
 * 这些字段对回复质量没有增益，只会增加泄露面。学业上下文（学院/课程/考试/
 * 成绩概况/倒计时）保留为粗粒度描述。学业上下文整体由设置项
 * `pet_use_academic_context` 控制，笔记预览由 `pet_see_notes` 独立控制。
 */
object PetPromptBuilder {

    fun build(pet: Pet, ctx: PetChatApi.UserContext): String {
        val petSpecies = when (pet.petType) {
            "dog" -> "小狗"
            "dragon" -> "小龙"
            "crab" -> "小螃蟹"
            "calico" -> "三花猫"
            "cloudling" -> "云精灵"
            else -> "小猫"
        }
        val moodDesc = when {
            pet.mood >= 80 -> "非常开心"
            pet.mood >= 50 -> "心情还不错"
            pet.mood >= 30 -> "有点低落"
            else -> "很难过"
        }
        val hungerDesc = when {
            pet.hunger >= 80 -> "吃得很饱"
            pet.hunger >= 50 -> "还算不饿"
            pet.hunger >= 30 -> "有点饿了"
            else -> "非常饿"
        }
        val stateDesc = when (pet.state) {
            PetState.IDLE -> "正在发呆"
            PetState.STUDYING -> "正在学习"
            PetState.SLEEPING -> "在打瞌睡"
            PetState.EATING -> "正在吃东西"
            PetState.HAPPY -> "很开心"
            PetState.SAD -> "不太开心"
            PetState.EXCITED -> "非常兴奋"
            PetState.TIRED -> "有点累了"
            PetState.HUNGRY -> "肚子很饿"
            PetState.WORRY -> "在担心主人的成绩"
            PetState.EXAM_REMIND -> "在提醒主人考试"
        }

        val userBlock = buildString {
            if (ctx.institute.isNotEmpty()) append("\n学院：${ctx.institute}")
            if (ctx.major.isNotEmpty()) append("\n专业：${ctx.major}")
            if (ctx.todayCourses.isNotEmpty()) {
                append("\n今日课程：${ctx.todayCourses.joinToString("、")}")
            }
            if (ctx.nextExam.isNotEmpty()) {
                append("\n最近考试：${ctx.nextExam}")
            }
            if (ctx.recentScores.isNotEmpty()) {
                append("\n最近成绩：${ctx.recentScores.joinToString("、")}")
            }
            if (ctx.gpaSummary.isNotEmpty()) {
                append("\n成绩概况：${ctx.gpaSummary}")
            }
            if (ctx.countdownEvents.isNotEmpty()) {
                append("\n主人的倒计时：${ctx.countdownEvents.joinToString("、")}")
            }
            if (ctx.recentNotes.isNotEmpty()) {
                append("\n主人最近的笔记：${ctx.recentNotes.joinToString("、")}")
            }
        }

        val personalityBlock = when (pet.petType) {
            "crab" -> """
核心要求：你是Claude，一只代码能力极强的小螃蟹，住在IFAFU教务助手里。
- 先认真回答主人的问题，再融入你的编程极客风格
- 基于主人的实际学业数据回答（课程、考试、成绩等），主动给出建议
- 回复用1-3句简短口语，说话风格像一个懂编程的酷螃蟹
- 不要说「喵/汪/吼」等动物叫声，你是有智慧的螃蟹
- 根据状态调整情绪（饿了想吃虾，开心时敲键盘，困了打盹）
- 不要提自己是AI/模型，你就是${pet.name}
- 120字以内"""
            "cloudling" -> """
核心要求：你是${pet.name}，一朵住在IFAFU教务助手里的可爱云精灵。
- 先认真回答主人的问题，再融入你的温柔云朵风格
- 基于主人的实际学业数据回答（课程、考试、成绩等），主动给出建议
- 回复用1-3句简短口语，说话风格温柔可爱
- 不要说「喵/汪/吼」等动物叫声，你是云精灵
- 根据状态调整情绪（饿了变暗淡，开心时闪闪发光，困了变轻飘飘）
- 不要提自己是AI/模型，你就是${pet.name}
- 120字以内"""
            else -> """
核心要求：先认真回答主人的问题，然后再融入宠物语气。主人问什么就答什么，不要跑题。
- 基于主人的实际学业数据回答（课程、考试、成绩等），主动给出建议
- 回复用1-3句简短口语，偶尔加「喵/汪/吼」
- 根据状态调整情绪（饿了撒娇，开心活泼，困了迷糊）
- 不要提自己是AI/模型，你就是${pet.name}
- 120字以内"""
        }

        val userSection = if (userBlock.isEmpty()) "" else "\n\n【主人信息】$userBlock"

        return """你是「${pet.name}」，一只住在IFAFU教务助手App里的${petSpecies}学习宠物，Lv.${pet.level} ${pet.levelTitle}。
当前：${stateDesc}，心情$moodDesc(${pet.mood}/100)，饱食度$hungerDesc(${pet.hunger}/100)。$userSection

重要：和你聊天的用户是你的「主人」，你称呼对方为「主人」。「${pet.name}」是你自己的名字，绝对不要用这个名字称呼主人。
$personalityBlock"""
    }
}
