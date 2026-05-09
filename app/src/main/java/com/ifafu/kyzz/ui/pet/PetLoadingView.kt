package com.ifafu.kyzz.ui.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.ifafu.kyzz.R

class PetLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val lottieView: LottieAnimationView
    private val hintText: TextView
    private val dotView: View
    private var hintAnimator: ValueAnimator? = null
    private var dotAnimator: ValueAnimator? = null

    private val hints = listOf(
        "正在努力加载中...",
        "小农帮你翻资料...",
        "稍等一下下~",
        "马上就好啦...",
        "加载中，别着急~",
        "小农正在努力！",
        "翻箱倒柜找数据...",
        "再等一小会儿~"
    )

    init {
        setBackgroundColor(Color.parseColor("#F5F0EB"))

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        lottieView = LottieAnimationView(context).apply {
            layoutParams = LinearLayout.LayoutParams(240, 240)
            setImageResource(R.drawable.pet_cat_idle)
        }
        container.addView(lottieView)

        // Breathing dot
        dotView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(12, 12).apply { topMargin = 24 }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(resources.getColor(R.color.claude_terracotta, null))
            }
            alpha = 0.3f
        }
        container.addView(dotView)

        hintText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setTextColor(resources.getColor(R.color.claude_text_secondary, null))
            textSize = 13f
            typeface = resources.getFont(R.font.claude_serif)
            gravity = Gravity.CENTER
        }
        container.addView(hintText)

        addView(container)
    }

    fun startLoading(petType: String = "cat") {
        val lottieFile = when (petType) {
            "dog" -> "lottie/lottie_dog_idle.json"
            "dragon" -> "lottie/lottie_dragon_idle.json"
            else -> "lottie/lottie_cat_idle.json"
        }
        try {
            lottieView.setAnimation(lottieFile)
            lottieView.playAnimation()
        } catch (_: Exception) {
            lottieView.setImageResource(R.drawable.pet_cat_idle)
        }

        alpha = 0f
        translationY = 40f
        visibility = VISIBLE
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        startHintRotation()
        startDotBreathing()
    }

    fun stopLoading() {
        hintAnimator?.cancel()
        dotAnimator?.cancel()
        lottieView.cancelAnimation()
        animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { visibility = GONE }
            .start()
    }

    private fun startHintRotation() {
        hintText.text = hints.random()
        hintAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (it.animatedFraction >= 0.99f) {
                    hintText.text = hints.random()
                }
            }
            start()
        }
    }

    private fun startDotBreathing() {
        dotAnimator = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { dotView.alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hintAnimator?.cancel()
        dotAnimator?.cancel()
        lottieView.cancelAnimation()
    }
}
