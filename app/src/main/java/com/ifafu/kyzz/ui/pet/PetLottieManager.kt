package com.ifafu.kyzz.ui.pet

import android.content.Context
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.PetState

object PetLottieManager {

    private const val LOTTIE_DIR = "lottie"

    // 使用 GIF 动画的宠物类型
    val gifPetTypes = setOf("crab", "calico", "cloudling")

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

    // 各 GIF 宠物的状态→GIF 文件映射
    private val gifStateToGif = mapOf(
        "crab" to mapOf(
            PetState.IDLE to "clawd-idle.gif",
            PetState.STUDYING to "clawd-thinking.gif",
            PetState.HUNGRY to "clawd-idle-reading.gif",
            PetState.WORRY to "clawd-carrying.gif",
            PetState.EXAM_REMIND to "clawd-headphones-groove.gif",
            PetState.HAPPY to "clawd-happy.gif",
            PetState.EXCITED to "clawd-notification.gif",
            PetState.SAD to "clawd-error.gif",
            PetState.SLEEPING to "clawd-sleeping.gif",
            PetState.TIRED to "clawd-sweeping.gif",
            PetState.EATING to "clawd-typing.gif"
        ),
        "calico" to mapOf(
            PetState.IDLE to "calico-idle.gif",
            PetState.STUDYING to "calico-thinking.gif",
            PetState.HUNGRY to "calico-idle.gif",
            PetState.WORRY to "calico-carrying.gif",
            PetState.EXAM_REMIND to "calico-juggling.gif",
            PetState.HAPPY to "calico-happy.gif",
            PetState.EXCITED to "calico-notification.gif",
            PetState.SAD to "calico-error.gif",
            PetState.SLEEPING to "calico-sleeping.gif",
            PetState.TIRED to "calico-sweeping.gif",
            PetState.EATING to "calico-typing.gif"
        ),
        "cloudling" to mapOf(
            PetState.IDLE to "cloudling-idle.gif",
            PetState.STUDYING to "cloudling-thinking.gif",
            PetState.HUNGRY to "cloudling-idle-reading.gif",
            PetState.WORRY to "cloudling-carrying.gif",
            PetState.EXAM_REMIND to "cloudling-juggling.gif",
            PetState.HAPPY to "cloudling-attention.gif",
            PetState.EXCITED to "cloudling-notification.gif",
            PetState.SAD to "cloudling-error.gif",
            PetState.SLEEPING to "cloudling-sleeping.gif",
            PetState.TIRED to "cloudling-sweeping.gif",
            PetState.EATING to "cloudling-typing.gif"
        )
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
        if (petType in gifPetTypes) {
            loadGif(context, view, state, petType)
            return
        }

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
        if (petType in gifPetTypes) return true
        val file = petTypeToIdleFile[petType] ?: petTypeToIdleFile["cat"]
        return file != null && assetExists(context, "$LOTTIE_DIR/$file")
    }

    private fun loadGif(context: Context, view: LottieAnimationView, state: PetState, petType: String) {
        val stateMap = gifStateToGif[petType]
        val gifName = stateMap?.get(state) ?: stateMap?.get(PetState.IDLE) ?: return
        val assetPath = "pet/$petType/$gifName"
        try {
            Glide.with(context)
                .asGif()
                .load("file:///android_asset/$assetPath")
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(object : com.bumptech.glide.request.target.ViewTarget<com.airbnb.lottie.LottieAnimationView, com.bumptech.glide.load.resource.gif.GifDrawable>(view) {
                    override fun onResourceReady(resource: com.bumptech.glide.load.resource.gif.GifDrawable, transition: com.bumptech.glide.request.transition.Transition<in com.bumptech.glide.load.resource.gif.GifDrawable>?) {
                        view.cancelAnimation()
                        view.setImageDrawable(resource)
                        resource.start()
                    }
                })
        } catch (_: Exception) {
            view.setImageResource(R.drawable.pet_cat_idle)
        }
    }

    fun loadGifDirect(context: Context, view: android.widget.ImageView, state: PetState, petType: String) {
        val stateMap = gifStateToGif[petType]
        val gifName = stateMap?.get(state) ?: stateMap?.get(PetState.IDLE) ?: return
        val assetPath = "pet/$petType/$gifName"
        try {
            Glide.with(context)
                .asGif()
                .load("file:///android_asset/$assetPath")
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(view)
        } catch (_: Exception) {
            view.setImageResource(R.drawable.pet_cat_idle)
        }
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
