package com.qin.paperreader.ui.splash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * 启动页波纹扩散动画（主动画）。
 *
 * 实现方式：单个 ValueAnimator（线性插值、无限循环）驱动相位 phase ∈ [0,1)，
 * 每帧只在 onDraw 里用缓存的 Paint 画圆，无对象分配，配合硬件 vsync 稳定 60fps。
 *
 * 动画参数（均可按需调整）：
 * @property cycleMs      每一圈从中心扩散到消失的周期，1600ms
 * @property ringCount    同时存在的圈数，3 圈（每圈相位错开 1/3 周期，形成连绵的波纹）
 * @property minRadius    内圈起始半径，46dp ≈ 贴着 Logo 边缘
 * @property maxRadius    外圈消失半径，140dp（超出容器不裁出硬边：此处 alpha 已衰减为 0）
 * @property maxAlpha     圈的最高不透明度 0.30，安静不抢戏
 * 另有一个贴着 Logo 的柔光圆作「呼吸」底光，随相位缓慢明暗。
 */
class SplashRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val rippleColor = context.getColor(com.qin.paperreader.R.color.splash_ripple)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val minRadius = 46f * dp
    private val maxRadius = 140f * dp
    private val cycleMs = 1600f
    private val ringCount = 3
    private val maxAlpha = 0.30f

    /** 动画相位 ∈ [0,1)，线性循环 */
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = cycleMs.toLong()
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun startRipple() {
        if (!animator.isRunning) animator.start()
    }

    fun stopRipple() = animator.cancel()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (width == 0 || height == 0) return

        // 呼吸底光：柔光圆随相位缓慢明暗（两个呼吸周期/波纹周期）
        val breath = 0.5f + 0.5f * sin((phase * 2f * PI).toFloat() * 2f)
        glowPaint.color = rippleColor
        glowPaint.alpha = (10f + 22f * breath).toInt()
        canvas.drawCircle(cx, cy, (50f + 5f * breath) * dp, glowPaint)

        // 波纹圈：easeOut 先快后慢，半径外推、透明度线性衰减到 0
        for (i in 0 until ringCount) {
            val p = (phase + i.toFloat() / ringCount) % 1f
            val eased = 1f - (1f - p) * (1f - p)
            val r = minRadius + (maxRadius - minRadius) * eased
            ringPaint.color = rippleColor
            ringPaint.alpha = ((1f - p) * maxAlpha * 255f).toInt()
            ringPaint.strokeWidth = (2.4f - 1.6f * eased) * dp
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopRipple()
        super.onDetachedFromWindow()
    }
}
