package com.qin.paperreader.reader

import android.graphics.Canvas
import com.qin.paperreader.engine.PageData

/** 页面坐标：第 chapter 章的第 page 页（章内页号，从 0 起）。 */
data class PageRef(val chapter: Int, val page: Int)

/** 相邻页解析结果。 */
sealed class Neighbor {
    /** 没有更方向的页（书首/书尾）。 */
    object None : Neighbor()

    /** 目标章尚未排版完成，等待 requestChapter 后重试。 */
    data class Pending(val chapterIdx: Int) : Neighbor()

    /** 可以翻过去。 */
    data class Ready(val ref: PageRef) : Neighbor()
}

/** 翻页动画模式。 */
enum class AnimMode(val id: String, val label: String) {
    NONE("none", "无动画"),
    COVER("cover", "覆盖"),
    SLIDE("slide", "平移"),
    CURL("curl", "仿真卷页");

    companion object {
        fun fromId(id: String): AnimMode = entries.firstOrNull { it.id == id } ?: COVER
    }
}

/**
 * 阅读视图与 ViewModel 之间的桥。视图只依赖该接口，排版/状态全部在 ViewModel 侧。
 * 所有方法在主线程调用；listener 可能从后台线程触发，实现方自行 post。
 */
interface ReaderClient {
    fun addInvalidateListener(l: () -> Unit)

    /** 首次可显示的页面（按保存进度定位）；未就绪返回 null，就绪后反复返回同一值。 */
    fun initialRef(): PageRef?

    /** 消费一次外部跳转（目录/搜索/进度条/重排版），返回后即清除。 */
    fun consumeJump(): PageRef?

    /** 把引用收敛到当前引擎的有效范围内。 */
    fun normalizeRef(ref: PageRef): PageRef

    fun page(ref: PageRef): PageData?

    fun neighbor(cur: PageRef, forward: Boolean): Neighbor

    /** 请求排版指定章节，完成后通过 invalidate listener 通知。 */
    fun requestChapter(chapterIdx: Int)

    /** 阅读视图尺寸变化（px）。 */
    fun onCanvasSize(width: Int, height: Int)

    fun draw(canvas: Canvas, ref: PageRef, width: Int, height: Int)

    /** 页面落定（含外部跳转后视图首次显示）：更新状态并持久化进度。 */
    fun onSettled(ref: PageRef)

    /** 页面背景色（卷页纸背等使用）。 */
    fun pageBgColor(): Int
}
