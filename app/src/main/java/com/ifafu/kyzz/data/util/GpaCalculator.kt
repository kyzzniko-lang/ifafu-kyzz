package com.ifafu.kyzz.data.util

import com.ifafu.kyzz.data.model.Score

/**
 * 统一的 GPA / 加权平均分计算，供成绩页、成绩趋势、首页宠物摘要复用，
 * 避免各处各自实现导致口径不一致。
 *
 * 核心修正：当成绩列表里"部分课有绩点、部分课没有（scorePoint==0）"时，
 * 旧实现分子只累加有绩点的课、分母却用了全部课的学分，导致 GPA 系统性偏低。
 * 这里在有绩点分支只用 scorePoint>0 的课同时算分子和分母。
 */
object GpaCalculator {

    /**
     * 过滤有效成绩：解析器用负数哨兵标记免修(-1)/缺考(-2)课程，
     * 学分为 0 的课也不参与统计。此前依赖调用方自觉过滤，成绩单等页面漏过滤
     * 会导致平均分/绩点被拉成负数，这里统一强制执行。
     * score==0 但有补考分的真实 0 分挂科保留（进入 [normalize] 后按补考分归一）。
     */
    private fun valid(scores: List<Score>): List<Score> =
        scores.filter { (it.score > 0f || (it.score == 0f && it.makeupScore > 0f)) && it.studyScore > 0f }

    /**
     * 统计前的口径归一，解决四年使用中必然出现的两类偏差：
     *
     * 1. 重修叠加：挂科记录与重修记录是两行独立成绩。不去重的话，总学分凭空
     *    多出重修学分，加权平均分按 (挂科分+重修分)/2 学分拉低（高数 45 分 + 重修
     *    85 分会被算成 65 分）。教务档案按"最新一次覆盖"，这里同学号课程只保留
     *    学年学期最晚的一条。
     * 2. 补考不计入：挂科后补考及格的课（score<60 且 makeupScore>=60）按补考分
     *    参与统计，否则学生补考通过后 App 的 GPA 永远停留在挂科状态。
     *
     * 此外对"教务绩点列为空（scorePoint==0）但已及格"的课程按 score/25 补线性
     * 绩点：补考通过后教务通常不给新绩点、等级制成绩（优/良/中/及格换算为
     * 95/85/75/65 分）绩点列也为空。不补的话这些课被 computeGpa 的
     * scorePoint>0 过滤器整体剔除——补考白考、等级制课从 GPA 里消失，而同卡片
     * 的平均分却包含它们，口径自相矛盾。线性近似与纯百分制兜底分支（加权平均/25）
     * 完全同口径，混合列表与纯百分制学期的 GPA 因此可比。
     *
     * 返回副本，不改动调用方持有的原始 Score 对象（成绩页列表共用这些实例）。
     */
    private fun normalize(scores: List<Score>): List<Score> {
        val withMakeup = scores.map { s ->
            if (s.score >= 0f && s.score < 60f && s.makeupScore >= 60f) {
                val fixed = s.copy(score = s.makeupScore)
                if (fixed.scorePoint <= 0f) fixed.copy(scorePoint = fixed.score / 25f) else fixed
            } else if (s.scorePoint <= 0f && s.score >= 60f) {
                s.copy(scorePoint = s.score / 25f)
            } else {
                s
            }
        }
        return withMakeup
            .groupBy { it.courseCode.ifEmpty { it.courseName } }
            .flatMap { (_, group) ->
                if (group.size == 1) group
                else listOf(group.maxWithOrNull(
                    compareBy({ it.year }, { it.term.toIntOrNull() ?: 0 })
                ) ?: group.first())
            }
    }

    /**
     * 计算学分加权 GPA。
     *
     * - 若存在 scorePoint>0 的课：用这些课的 scorePoint*学分 之和 / 这些课的学分之和。
     *   绩点列为空的及格课（补考、等级制）已在 [normalize] 中按 score/25 补齐，
     *   不会像旧版那样被剔出分子分母。
     * - 否则（纯百分制成绩）：用 (百分制加权平均 / 25) 近似 GPA。
     */
    fun computeGpa(scores: List<Score>): Float {
        val scores = normalize(valid(scores))
        if (scores.isEmpty()) return 0f
        val withPoint = scores.filter { it.scorePoint > 0f && it.studyScore > 0f }
        return if (withPoint.isNotEmpty()) {
            val gpSum = withPoint.sumOf { (it.scorePoint * it.studyScore).toDouble() }.toFloat()
            val credits = withPoint.sumOf { it.studyScore.toDouble() }.toFloat()
            if (credits > 0) gpSum / credits else 0f
        } else {
            // 纯百分制兜底：百分制加权平均分 / 25 近似 5 分制 GPA。
            // 与绩点分支口径对齐，剔除挂科（补考已在上一步归一），否则趋势图里
            // 有绩点的学期剔除挂科、纯百分制的学期不剔除，相邻点不可比。
            val passed = scores.filter { it.score >= 60f }
            if (passed.isEmpty()) return 0f
            val credits = passed.sumOf { it.studyScore.toDouble() }.toFloat()
            if (credits > 0) computeWeightedAvg(passed) / 25f else 0f
        }
    }

    /** 学分加权平均分。 */
    fun computeWeightedAvg(scores: List<Score>): Float {
        val scores = normalize(valid(scores))
        if (scores.isEmpty()) return 0f
        val credits = scores.sumOf { it.studyScore.toDouble() }.toFloat()
        if (credits <= 0) return 0f
        val weightedSum = scores.sumOf { (it.score * it.studyScore).toDouble() }.toFloat()
        return weightedSum / credits
    }

    /** 算术平均分（不按学分加权）。 */
    fun computeAvgScore(scores: List<Score>): Float {
        val scores = normalize(valid(scores))
        if (scores.isEmpty()) return 0f
        return scores.map { it.score }.average().toFloat()
    }

    /**
     * 总学分（与 GPA/平均分同口径：重修去重、剔除免修/缺考/0 学分课）。
     * 成绩单等页面若直接对原始列表求和，挂科+重修两行会把学分双计，
     * 与同卡片上的 GPA（已归一）互相矛盾。
     */
    fun computeTotalCredits(scores: List<Score>): Float =
        normalize(valid(scores)).sumOf { it.studyScore.toDouble() }.toFloat()
}
