package com.qin.paperreader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.qin.paperreader.ui.FontManager
import com.qin.paperreader.ui.splash.SplashRippleView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 启动页：品牌动画 + 后台初始化预热，两者都完成后无缝进入主界面。
 *
 * 时间轴：
 *   0–600ms     Logo 淡入 + 0.82→1.0 缩放；波纹淡入并开始循环扩散
 *   500–1200ms  署名「HIM开发-开源免费」轻微淡入（底部居中，13sp / 60% 透明度）
 *   600ms 起    Logo 进入 1.0↔1.05 呼吸循环（波纹持续循环）
 *   2200ms      动画结束（要求 1.5–2.5s）
 *   完成 → 等待后台初始化（兜底超时 1.5s）→ 根容器收缩+淡出 300ms → 淡入主界面
 */
class SplashActivity : ComponentActivity() {

    private lateinit var root: View
    private lateinit var ripple: SplashRippleView
    private lateinit var logo: View
    private lateinit var signature: TextView

    private val animators = ArrayList<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        root = findViewById(R.id.splash_root)
        ripple = findViewById(R.id.splash_ripple)
        logo = findViewById(R.id.splash_logo)
        signature = findViewById(R.id.splash_signature)

        val tasksDone = CompletableDeferred<Unit>()
        val animDone = CompletableDeferred<Unit>()

        // 后台初始化（与动画并行）：数据库预热 / 配置加载 / 字体目录扫描
        lifecycleScope.launch {
            runCatching { warmup() }
            tasksDone.complete(Unit)
        }

        // 动画时间轴
        lifecycleScope.launch {
            playIntro()
            delay(SPLASH_DURATION_MS)
            animDone.complete(Unit)
        }

        // 动画播完 且 初始化完成（兜底超时不卡死）→ 无缝进入主界面
        lifecycleScope.launch {
            animDone.await()
            withTimeoutOrNull(WARMUP_TIMEOUT_MS) { tasksDone.await() }
            exitToMain()
        }
    }

    /** 后台初始化任务：三项并行，全部走 IO 线程。 */
    private suspend fun warmup(): Unit = coroutineScope {
        val app = application as App
        // ① 数据库预热：真实触发 Room 建库 / v1→v2 迁移并做一次全表读
        async(Dispatchers.IO) { runCatching { app.repo.bookDao.getAll().size } }
        // ② 配置加载：DataStore 首读，后续界面读配置零等待
        async(Dispatchers.IO) { runCatching { app.settings.flow.first() } }
        // ③ 字体目录扫描：阅读设置面板首开更快
        async(Dispatchers.IO) { FontManager.options(app) }
        Unit
    }

    /** 开场动画：Logo 淡入放大 → 呼吸循环；波纹淡入并循环；署名延迟淡入。 */
    private fun playIntro() {
        val enter = DecelerateInterpolator(1.4f)
        fun add(a: Animator) {
            a.start()
            animators.add(a)
        }
        // Logo 淡入 + 缩放（0.82→1.0，600ms）
        add(ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).apply { duration = 600; interpolator = enter })
        add(ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.82f, 1f).apply { duration = 600; interpolator = enter })
        add(ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.82f, 1f).apply { duration = 600; interpolator = enter })
        // Logo 呼吸循环（600ms 后开始，1.0↔1.05，1400ms/周期，往复）
        val breatheX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 1f, 1.05f).apply {
            startDelay = 600; duration = 1400
            repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        }
        val breatheY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1f, 1.05f).apply {
            startDelay = 600; duration = 1400
            repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        }
        add(breatheX)
        add(breatheY)
        // 波纹淡入并启动循环扩散
        ripple.alpha = 0f
        add(ObjectAnimator.ofFloat(ripple, View.ALPHA, 0f, 1f).apply { duration = 400 })
        ripple.startRipple()
        // 署名轻微淡入（500ms 后开始，700ms 完成）
        add(ObjectAnimator.ofFloat(signature, View.ALPHA, 0f, 1f).apply {
            startDelay = 500; duration = 700; interpolator = DecelerateInterpolator()
        })
    }

    /** 退出：根容器收缩（1→0.90）+ 淡出，随后主界面淡入，视觉无缝衔接。 */
    private fun exitToMain() {
        ripple.stopRipple()
        animators.forEach { it.cancel() }
        animators.clear()
        root.animate()
            .scaleX(0.90f)
            .scaleY(0.90f)
            .alpha(0f)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .setListener(object : AnimatorListenerAdapter() {
                private var fired = false
                override fun onAnimationEnd(animation: Animator) {
                    if (fired) return
                    fired = true
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                    finish()
                }
            })
    }

    override fun onDestroy() {
        ripple.stopRipple()
        animators.forEach { it.cancel() }
        animators.clear()
        super.onDestroy()
    }

    companion object {
        /** 启动动画总时长 2.2s（需求区间 1.5–2.5s） */
        private const val SPLASH_DURATION_MS = 2200L

        /** 收缩淡出时长 */
        private const val EXIT_DURATION_MS = 300L

        /** 后台初始化兜底超时：预热异常也不阻塞进入主界面 */
        private const val WARMUP_TIMEOUT_MS = 1500L
    }
}
