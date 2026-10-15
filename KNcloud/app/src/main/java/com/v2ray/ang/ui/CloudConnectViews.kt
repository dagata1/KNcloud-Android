package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

/**
 * 简易模式连接按钮中的云形图标（与 Windows 版同一方案）：
 * - CONNECTING：一段光束沿云朵轮廓流动描线（周期 1.6s）
 * - CONNECTED ：云朵轮廓完整点亮
 * - IDLE      ：断开状态，静态灰色云朵轮廓，无光晕
 */
class CloudStrokeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Mode { CONNECTING, CONNECTED, IDLE }

    private val srcPath: Path = PathParser.createPathFromPathData(CLOUD_PATH)
    private val drawPath = Path()
    private val segPath = Path()
    private val measure = PathMeasure()
    private var length = 0f
    private var phase = 0f
    private var mode = Mode.CONNECTING
    private var animator: ValueAnimator? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    var color: Int = Color.WHITE
        set(value) { field = value; applyColor(); invalidate() }

    init {
        // BlurMaskFilter 需要软件层；视图很小，开销可忽略
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyColor()
    }

    private fun applyColor() {
        basePaint.color = ColorUtils.setAlphaComponent(color, 46)
        linePaint.color = color
        idlePaint.color = color
        glowPaint.color = ColorUtils.setAlphaComponent(color, 150)
    }

    fun setMode(m: Mode) {
        mode = m
        if (m == Mode.CONNECTING) start() else stop()
        invalidate()
    }

    private fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel(); animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.CONNECTING && isShown) start()
    }

    override fun onDetachedFromWindow() {
        stop(); super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && mode == Mode.CONNECTING) start() else if (visibility != VISIBLE) stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = minOf(w, h) / 24f
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((w - 24f * s) / 2f, (h - 24f * s) / 2f)
        }
        drawPath.reset(); srcPath.transform(m, drawPath)
        measure.setPath(drawPath, true); length = measure.length
        basePaint.strokeWidth = 1.6f * s
        linePaint.strokeWidth = 2f * s
        idlePaint.strokeWidth = 1.8f * s
        glowPaint.strokeWidth = 3.2f * s
        glowPaint.maskFilter = BlurMaskFilter(3f * s, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        if (length <= 0f) return
        if (mode == Mode.IDLE) {
            canvas.drawPath(drawPath, idlePaint)
            return
        }
        if (mode == Mode.CONNECTED) {
            canvas.drawPath(drawPath, glowPaint)
            canvas.drawPath(drawPath, linePaint)
            return
        }
        canvas.drawPath(drawPath, basePaint)
        // 光束长度占轮廓 14/74，与 Windows 版 stroke-dasharray: 14 60 一致
        val seg = length * 14f / 74f
        val startD = phase * length
        segPath.reset()
        val endD = startD + seg
        measure.getSegment(startD, minOf(endD, length), segPath, true)
        if (endD > length) measure.getSegment(0f, endD - length, segPath, true)
        canvas.drawPath(segPath, glowPaint)
        canvas.drawPath(segPath, linePaint)
    }

    companion object {
        const val CLOUD_PATH = "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"
    }
}

/**
 * 连接按钮外圈的呼吸光晕：连接中为主题色（2s），已连接为绿色（2.4s）。
 */
class ConnectHaloView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    private var animator: ValueAnimator? = null
    private var haloColor = Color.TRANSPARENT
    private var innerRadius = 0f

    /** 光晕从按钮边缘（buttonDiameter/2）向外扩散 */
    var buttonDiameterPx: Float = 0f
        set(value) { field = value; rebuildShader() }

    fun show(color: Int, periodMs: Long) {
        haloColor = color
        rebuildShader()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { level = it.animatedValue as Float; invalidate() }
            start()
        }
        visibility = VISIBLE
    }

    fun hide() {
        animator?.cancel(); animator = null
        level = 0f
        visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildShader()

    private fun rebuildShader() {
        val r = minOf(width, height) / 2f
        if (r <= 0f) return
        innerRadius = if (buttonDiameterPx > 0f) buttonDiameterPx / 2f else r * 0.8f
        val stop = (innerRadius / r).coerceIn(0f, 0.99f)
        paint.shader = RadialGradient(
            width / 2f, height / 2f, r,
            intArrayOf(haloColor, haloColor, ColorUtils.setAlphaComponent(haloColor, 0)),
            floatArrayOf(0f, stop, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (haloColor == Color.TRANSPARENT) return
        // 0.35 ~ 1.0 之间呼吸，保证已连接时光晕始终可见
        paint.alpha = (255 * (0.35f + 0.65f * level)).toInt()
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
    }
}
