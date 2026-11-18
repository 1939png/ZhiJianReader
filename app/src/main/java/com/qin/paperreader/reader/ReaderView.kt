package com.qin.paperreader.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.exp

/**
 * 阅读页面视图：手势（左/中/右分区点按、水平拖拽跟手）与翻页动画编排。
 * 页面内容由 ReaderClient（ViewModel 侧的排版引擎）绘制，本视图负责合成：
 * - 无动画：直接切换；
 * - 覆盖：新页从右侧盖在旧页上（后退时旧页向右滑开）；
 * - 平移：两页无缝推移，接缝处带阴影；
 * - 仿真卷页：折痕位置跟手、纸背用纸色柱面渐变、折痕在前后页上投影，松手按位置/速度决定完成或回弹。
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Host {
        fun client(): ReaderClient?
        fun animMode(): AnimMode
        fun onTapCenter()
        fun onEdge(message: String)
    }

    var host: Host? = null

    // 触控判定偏激进：用系统“翻页滑动”阈值（比普通 touchSlop 约小一半），并限制上限
    private val pagingSlop = maxOf(
        4,
        minOf(
            ViewConfiguration.get(context).scaledPagingTouchSlop,
            (12f * resources.displayMetrics.density).toInt(),
        ),
    )
    private val dp = resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastMoveT = 0L
    private var velocity = 0f
    private var dragging = false

    private var cur: PageRef? = null
    private var anim: PageAnim? = null

    private val animator = ValueAnimator()

    private val seamPaint = Paint()
    private val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * dp
        color = 0xFFFAF6EE.toInt()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * dp
        color = 0x99807060.toInt()
        textAlign = Paint.Align.CENTER
    }
    private var chipText: String? = null
    private var chipUntil = 0L

    init {
        animator.interpolator = DecelerateInterpolator(1.3f)
        animator.addUpdateListener {
            val a = anim ?: return@addUpdateListener
            val f = it.animatedFraction
            a.value = a.fromV + (a.toV - a.fromV) * f
            if (f >= 1f) {
                finishAnim()
            } else {
                invalidate()
            }
        }
    }

    private class PageAnim(
        val forward: Boolean,
        val type: AnimMode,
        val front: PageRef,
        val back: PageRef,
        val drag: Boolean,
    ) {
        /** 平移/覆盖：progress ∈ [0,1]；卷页：value = 折痕 x 像素。 */
        var value = 0f
        var fromV = 0f
        var toV = 1f
        var anchorY = 0f
        var commit = false

        val progress: Float
            get() = if (type == AnimMode.CURL) value else value
    }

    /** 音量键等外部触发的快速翻页；返回是否接受。 */
    fun turnByKey(forward: Boolean): Boolean {
        if (anim != null) return false
        return beginTurn(forward, drag = false)
    }

    private fun beginTurn(forward: Boolean, drag: Boolean): Boolean {
        val c = host?.client() ?: return false
        if (cur == null) {
            cur = c.initialRef()
            if (cur == null) return false
        }
        val curRef = cur!!
        if (anim != null) return false
        when (val nb = c.neighbor(curRef, forward)) {
            is Neighbor.None -> {
                host?.onEdge(if (forward) "已经是最后一页" else "已经是第一页")
                return false
            }
            is Neighbor.Pending -> {
                c.requestChapter(nb.chapterIdx)
                chip("排版中…")
                return false
            }
            is Neighbor.Ready -> {
                val type = host?.animMode() ?: AnimMode.COVER
                if (type == AnimMode.NONE) {
                    cur = nb.ref
                    c.onSettled(nb.ref)
                    invalidate()
                    return true
                }
                val a = PageAnim(forward, type, front = curRef, back = nb.ref, drag = drag)
                a.value = 0f
                a.anchorY = height * 0.62f
                anim = a
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (!drag) {
                    animateTo(commit = true)
                } else {
                    invalidate()
                }
                return true
            }
        }
    }

    private fun beginDrag(forward: Boolean, x: Float, y: Float): Boolean {
        val c = host?.client() ?: return false
        val curRef = cur ?: return false
        if (anim != null) return false
        when (val nb = c.neighbor(curRef, forward)) {
            is Neighbor.Ready -> {
                val type = host?.animMode() ?: AnimMode.COVER
                if (type == AnimMode.NONE) return false
                val a = PageAnim(forward, type, front = curRef, back = nb.ref, drag = true)
                a.anchorY = y
                anim = a
                return true
            }
            is Neighbor.Pending -> {
                c.requestChapter(nb.chapterIdx)
                chip("排版中…")
                return false
            }
            is Neighbor.None -> return false
        }
    }

    private fun updateDrag(x: Float, y: Float) {
        val a = anim ?: return
        val w = width.toFloat()
        when (a.type) {
            AnimMode.CURL -> {
                a.value = if (a.forward) x.coerceIn(w * 0.02f, w) else x.coerceIn(0f, w * 0.98f)
                a.anchorY = y
            }
            AnimMode.SLIDE, AnimMode.COVER -> {
                a.value = if (a.forward) ((downX - x) / w).coerceIn(0f, 1f) else ((x - downX) / w).coerceIn(0f, 1f)
            }
            AnimMode.NONE -> {}
        }
        invalidate()
    }

    private fun endDrag(vx: Float) {
        val a = anim ?: return
        val w = width.toFloat()
        val commit = when (a.type) {
            AnimMode.CURL ->
                if (a.forward) a.value < w * 0.62f || vx < -500f
                else a.value > w * 0.38f || vx > 500f
            else -> a.value > 0.28f ||
                (a.forward && vx < -500f) || (!a.forward && vx > 500f)
        }
        animateTo(commit)
    }

    private fun animateTo(commit: Boolean) {
        val a = anim ?: return
        val w = width.toFloat()
        a.commit = commit
        a.fromV = a.value
        a.toV = when (a.type) {
            AnimMode.CURL -> if (a.forward) (if (commit) w * 0.03f else w) else (if (commit) w * 0.97f else 0f)
            else -> if (commit) 1f else 0f
        }
        val dist = if (a.type == AnimMode.CURL) abs(a.toV - a.fromV) / w else abs(a.toV - a.fromV)
        animator.cancel()
        animator.setFloatValues(0f, 1f)
        animator.duration = (130f + 340f * dist).toLong().coerceIn(130, 470)
        animator.start()
    }

    private fun finishAnim() {
        val a = anim ?: return
        animator.cancel()
        anim = null
        if (a.commit) {
            cur = a.back
            host?.client()?.onSettled(a.back)
        }
        invalidate()
    }

    private fun handleTap(x: Float, y: Float) {
        val w = width.toFloat()
        when {
            x < w / 3f -> beginTurn(false, drag = false)
            x > w * 2f / 3f -> beginTurn(true, drag = false)
            else -> host?.onTapCenter()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastMoveT = event.eventTime
                velocity = 0f
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && abs(dx) > pagingSlop && abs(dx) > abs(dy) * 0.7f) {
                    dragging = beginDrag(dx < 0, event.x, event.y)
                    if (dragging) {
                        downX = event.x // 以实际拖拽起点为基准，消除 slop 跳变
                        updateDrag(event.x, event.y)
                    }
                }
                if (dragging) {
                    updateDrag(event.x, event.y)
                    val dt = (event.eventTime - lastMoveT).coerceAtLeast(1)
                    velocity = (event.x - lastX) / dt * 1000f
                    lastX = event.x
                    lastMoveT = event.eventTime
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    endDrag(velocity)
                } else {
                    handleTap(event.x, event.y)
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) animateTo(commit = false)
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        (host?.client())?.let { it.onCanvasSize(w, h) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = host?.client() ?: return
        if (anim == null) {
            c.consumeJump()?.let { j ->
                cur = c.normalizeRef(j)
                c.onSettled(cur!!)
                invalidate()
            } ?: run {
                if (cur == null) c.initialRef()?.let { cur = it }
            }
            val curRef = cur
            if (curRef == null) {
                drawHint(canvas, "正在排版…")
                return
            }
            val fixed = c.normalizeRef(curRef)
            if (fixed != curRef) cur = fixed
            c.draw(canvas, fixed, width, height)
        } else {
            val a = anim!!
            when (a.type) {
                AnimMode.SLIDE -> drawSlide(canvas, a)
                AnimMode.COVER -> drawCover(canvas, a)
                AnimMode.CURL -> drawCurl(canvas, a)
                AnimMode.NONE -> {}
            }
        }
        drawChip(canvas)
    }

    // ---------- 动画绘制 ----------

    private fun drawSlide(canvas: Canvas, a: PageAnim) {
        val c = host?.client() ?: return
        val w = width.toFloat()
        val p = a.value
        if (a.forward) {
            canvas.save(); canvas.translate(w * (1f - p), 0f); c.draw(canvas, a.back, width, height); canvas.restore()
            canvas.save(); canvas.translate(-w * p, 0f); c.draw(canvas, a.front, width, height); canvas.restore()
            seamShadow(canvas, w * (1f - p))
        } else {
            canvas.save(); canvas.translate(-w * (1f - p), 0f); c.draw(canvas, a.back, width, height); canvas.restore()
            canvas.save(); canvas.translate(w * p, 0f); c.draw(canvas, a.front, width, height); canvas.restore()
            seamShadow(canvas, w * p)
        }
    }

    private fun drawCover(canvas: Canvas, a: PageAnim) {
        val c = host?.client() ?: return
        val w = width.toFloat()
        val p = a.value
        if (a.forward) {
            c.draw(canvas, a.front, width, height)
            canvas.save(); canvas.translate(w * (1f - p), 0f); c.draw(canvas, a.back, width, height); canvas.restore()
            edgeShadow(canvas, w * (1f - p))
        } else {
            c.draw(canvas, a.back, width, height)
            canvas.save(); canvas.translate(w * p, 0f); c.draw(canvas, a.front, width, height); canvas.restore()
            edgeShadow(canvas, w * p)
        }
    }

    private fun seamShadow(canvas: Canvas, x: Float) {
        val w = 18f * dp
        seamPaint.shader = LinearGradient(x - w, 0f, x, 0f, 0x00000000, 0x28000000, Shader.TileMode.CLAMP)
        canvas.drawRect(x - w, 0f, x, height.toFloat(), seamPaint)
        seamPaint.shader = LinearGradient(x, 0f, x + w, 0f, 0x28000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(x, 0f, x + w, height.toFloat(), seamPaint)
        seamPaint.shader = null
    }

    private fun edgeShadow(canvas: Canvas, x: Float) {
        val w = 26f * dp
        seamPaint.shader = LinearGradient(x, 0f, x + w, 0f, 0x30000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(x, 0f, x + w, height.toFloat(), seamPaint)
        seamPaint.shader = null
    }

    /** 仿真卷页。折痕 x：前进时从右缘向左推进；后退时从左缘向右展开。 */
    private fun drawCurl(canvas: Canvas, a: PageAnim) {
        val c = host?.client() ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val ay = a.anchorY.coerceIn(h * 0.08f, h * 0.92f)
        val fx = if (a.forward) {
            a.value.coerceIn(w * 0.01f, w)
        } else {
            a.value.coerceIn(0f, w * 0.99f)
        }
        val fromRight = a.forward

        // 底页
        c.draw(canvas, a.back, width, height)
        val foldW = (if (fromRight) w - fx else fx).coerceAtLeast(0f)

        // 前页可见部分
        canvas.save()
        if (fromRight) canvas.clipRect(0f, 0f, fx, h) else canvas.clipRect(fx, 0f, w, h)
        c.draw(canvas, a.front, width, height)
        canvas.restore()

        if (foldW < 3f) return

        // 折起的纸背：纸色 + 柱面渐变，折痕随触摸点纵向鼓出
        val paperColor = darken(c.pageBgColor(), 0.965f)
        foldPaint.color = paperColor
        val n = 24
        val slabH = h / n
        val sigma = h / 3.2f
        val bulgeAmp = foldW * 0.22f
        val gradW = (26f * dp).coerceAtMost(foldW * 0.5f)
        for (i in 0 until n) {
            val y0 = i * slabH
            val ym = y0 + slabH / 2f
            val g = exp(-((ym - ay) * (ym - ay)) / (2f * sigma * sigma))
            val bulge = bulgeAmp * g
            val crease = if (fromRight) fx - bulge else fx + bulge
            val x0 = if (fromRight) crease - foldW else crease
            val x1 = if (fromRight) crease else crease + foldW
            canvas.drawRect(x0, y0, x1, y0 + slabH + 0.5f, foldPaint)
            // 柱面明暗：靠折痕处最深，外侧略回光
            if (fromRight) {
                gradPaint.shader = LinearGradient(
                    x1 - gradW, 0f, x1, 0f,
                    intArrayOf(0x00000000, 0x2A000000, 0x12000000),
                    floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(x1 - gradW, y0, x1, y0 + slabH + 0.5f, gradPaint)
            } else {
                gradPaint.shader = LinearGradient(
                    x0, 0f, x0 + gradW, 0f,
                    intArrayOf(0x12000000, 0x2A000000, 0x00000000),
                    floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(x0, y0, x0 + gradW, y0 + slabH + 0.5f, gradPaint)
            }
        }
        gradPaint.shader = null

        // 折痕在前页上的投影
        val shW = 30f * dp
        canvas.save()
        if (fromRight) canvas.clipRect(0f, 0f, fx, h) else canvas.clipRect(fx, 0f, w, h)
        gradPaint.shader = if (fromRight) {
            LinearGradient(fx - shW, 0f, fx, 0f, 0x00000000, 0x32000000, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(fx, 0f, fx + shW, 0f, 0x32000000, 0x00000000, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(if (fromRight) fx - shW else fx, 0f, if (fromRight) fx else fx + shW, h, gradPaint)
        canvas.restore()

        // 折痕在底页上的投影
        gradPaint.shader = if (fromRight) {
            LinearGradient(fx, 0f, fx + shW, 0f, 0x2E000000, 0x00000000, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(fx - shW, 0f, fx, 0f, 0x00000000, 0x2E000000, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(if (fromRight) fx else fx - shW, 0f, if (fromRight) fx + shW else fx, h, gradPaint)
        gradPaint.shader = null
    }

    private fun darken(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    // ---------- 提示 ----------

    private fun chip(text: String) {
        chipText = text
        chipUntil = System.currentTimeMillis() + 1600
        postInvalidate()
    }

    private fun drawChip(canvas: Canvas) {
        val t = chipText ?: return
        if (System.currentTimeMillis() > chipUntil) {
            chipText = null
            return
        }
        val tw = chipTextPaint.measureText(t)
        val padH = 8f * dp
        val padV = 5f * dp
        val rect = RectF(width / 2f - tw / 2f - padH, 18f * dp, width / 2f + tw / 2f + padH, 18f * dp + chipTextPaint.textSize + padV * 2)
        chipBgPaint.color = 0x66000000
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, chipBgPaint)
        val ty = rect.centerY() - (chipTextPaint.fontMetrics.ascent + chipTextPaint.fontMetrics.descent) / 2f
        canvas.drawText(t, rect.centerX(), ty, chipTextPaint)
        postInvalidateDelayed(200)
    }

    private fun drawHint(canvas: Canvas, text: String) {
        canvas.drawText(text, width / 2f, height / 2f, hintPaint)
    }

    companion object {
        private const val TAG = "ReaderView"
    }
}
