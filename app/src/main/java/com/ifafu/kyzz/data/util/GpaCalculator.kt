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
     * 计算学分加权 GPA。调用方应先过滤出有效成绩（score>0 且 studyScore>0）。
     *
     * - 若存在 scorePoint>0 的课：用这些课的 scorePoint*学分 之和 / 这些课的学分之和。
     * - 否则（纯百分制成绩）：用 (百分制加权平均 / 25) 近似 GPA。
     */
    fun computeGpa(scores: List<Score>): Float {
        if (scores.isEmpty()) return 0f
        val withPoint = scores.filter { it.scorePoint > 0f && it.studyScore > 0f }
        return if (withPoint.isNotEmpty()) {
            val gpSum = withPoint.sumOf { (it.scorePoint * it.studyScore).toDouble() }.toFloat()
            val credits = withPoint.sumOf { it.studyScore.toDouble() }.toFloat()
            if (credits > 0) gpSum / credits else 0f
        } else {
            // 纯百分制兜底：百分制加权平均分 / 25 近似 5 分制 GPA
            val credits = scores.sumOf { it.studyScore.toDouble() }.toFloat()
            if (credits > 0) computeWeightedAvg(scores) / 25f else 0f
        }
    }

    /** 学分加权平均分。调用方应先过滤出有效成绩。 */
    fun computeWeightedAvg(scores: List<Score>): Float {
        if (scores.isEmpty()) return 0f
        val credits = scores.sumOf { it.studyScore.toDouble() }.toFloat()
        if (credits <= 0) return 0f
        val weightedSum = scores.sumOf { (it.score * it.studyScore).toDouble() }.toFloat()
        return weightedSum / credits
    }

    /** 算术平均分（不按学分加权）。调用方应先过滤出有效成绩。 */
    fun computeAvgScore(scores: List<Score>): Float {
        if (scores.isEmpty()) return 0f
        return scores.map { it.score }.average().toFloat()
    }
}
