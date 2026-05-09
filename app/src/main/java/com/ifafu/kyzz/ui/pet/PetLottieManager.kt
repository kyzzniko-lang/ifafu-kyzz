package com.ifafu.kyzz.ui.pet

import android.content.Context
import com.airbnb.lottie.LottieAnimationView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.PetState

object PetLottieManager {

    private const val LOTTIE_DIR = "lottie"

    // 每种动物的 idle 动画文件
    private val petTypeToIdleFile = mapOf(
        "cat" to "lottie_cat_idle.json",
        "dog" to "lottie_dog_idle.json",
        "dragon" to "lottie_dragon_idle.json"
    )

    // 所有状态都使用 idle 动画（每种动物只有一个 Lottie 文件）
    private val stateToLottieKey = mapOf(
        PetState.IDLE to "idle",
        PetState.STUDYING to "idle",
        PetState.HUNGRY to "idle",
        PetState.WORRY to "idle",
        PetState.EXAM_REMIND to "idle",
        PetState.HAPPY to "idle",
        PetState.EXCITED to "idle",
        PetState.SAD to "idle",
        PetState.SLEEPING to "idle",
        PetState.TIRED to "idle",
        PetState.EATING to "idle"
    )

    // 兜底 drawable（猫）— Lottie 文件不存在时使用
    private val stateToDrawable = mapOf(
        PetState.IDLE to R.drawable.pet_cat_idle,
        PetState.STUDYING to R.drawable.pet_cat_excited,
        PetState.HUNGRY to R.drawable.pet_cat_sad,
        PetState.WORRY to R.drawable.pet_cat_sad,
        PetState.EXAM_REMIND to R.drawable.pet_cat_excited,
        PetState.HAPPY to R.drawable.pet_cat_happy,
        PetState.EXCITED to R.drawable.pet_cat_excited,
        PetState.SAD to R.drawable.pet_cat_sad,
        PetState.SLEEPING to R.drawable.pet_cat_sleepy,
        PetState.TIRED to R.drawable.pet_cat_sleepy,
        PetState.EATING to R.drawable.pet_cat_happy
    )

    fun applyAnimation(context: Context, view: LottieAnimationView, state: PetState, petType: String = "cat") {
        val fileName = petTypeToIdleFile[petType] ?: petTypeToIdleFile["cat"]

        if (fileName != null && assetExists(context, "$LOTTIE_DIR/$fileName")) {
            view.setAnimation("$LOTTIE_DIR/$fileName")
            view.playAnimation()
        } else {
            // 最终回退到猫的 static drawable
            val drawableRes = stateToDrawable[state] ?: R.drawable.pet_cat_idle
            view.setImageResource(drawableRes)
        }
    }

    fun hasLottieAnimations(context: Context, petType: String = "cat"): Boolean {
        val file = petTypeToIdleFile[petType] ?: petTypeToIdleFile["cat"]
        return file != null && assetExists(context, "$LOTTIE_DIR/$file")
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return try {
            context.assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
