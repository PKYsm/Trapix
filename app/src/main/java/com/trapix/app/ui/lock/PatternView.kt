package com.trapix.app.ui.lock

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.trapix.app.util.DebugLogger
import kotlin.math.min
import kotlin.math.sqrt

class PatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    interface OnPatternListener {
        fun onPatternStart()
        fun onPatternComplete(pattern: List<Int>)
    }

    companion object {
        private const val GRID_SIZE = 3
        private const val NODE_COUNT = GRID_SIZE * GRID_SIZE
        private const val MIN_NODES = 4
        private const val TAG = "PATTERN"
    }

    private var cellSize = 0f
    private var dotRadius = 0f
    private var selectedRadius = 0f
    private var touchRadius = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private val normalPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val glowPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val nodePositions = Array(NODE_COUNT) { PointF() }
    private val selectedNodes = mutableListOf<Int>()
    private var currentTouchX = 0f
    private var currentTouchY = 0f
    private var isDrawing = false
    private var isError   = false
    private var isSuccess = false

    var onPatternListener: OnPatternListener? = null
    var onTooFewNodes: (() -> Unit)? = null

    // BUG 1 FIX: Handler reference rakhna zaroori hai taaki naya touch aane pe
    // pehle wala clearPattern() cancel ho sake (race condition fix)
    private val clearHandler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (w > 0 && h > 0) min(w, h) else (300 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        recalculate(w, h)
    }

    private fun recalculate(w: Int, h: Int) {
        val size = min(w, h).toFloat()
        cellSize      = size / GRID_SIZE.toFloat()
        dotRadius     = cellSize * 0.12f
        selectedRadius = cellSize * 0.18f
        touchRadius   = cellSize * 0.38f
        offsetX = (w - size) / 2f
        offsetY = (h - size) / 2f
        for (i in 0 until NODE_COUNT) {
            val col = i % GRID_SIZE; val row = i / GRID_SIZE
            nodePositions[i] = PointF(
                offsetX + cellSize * col + cellSize / 2f,
                offsetY + cellSize * row + cellSize / 2f
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize == 0f) recalculate(width, height)

        val activeColor = when { isError -> 0xFFFF1744.toInt(); isSuccess -> 0xFF00E676.toInt(); else -> 0xFF00E5FF.toInt() }
        val activeDim   = when { isError -> 0x44FF1744.toInt(); isSuccess -> 0x4400E676.toInt(); else -> 0x4400E5FF.toInt() }

        if (selectedNodes.size > 1) {
            linePaint.color = activeColor; linePaint.alpha = 160
            for (i in 0 until selectedNodes.size - 1) {
                val f = nodePositions[selectedNodes[i]]; val t = nodePositions[selectedNodes[i + 1]]
                canvas.drawLine(f.x, f.y, t.x, t.y, linePaint)
            }
        }
        if (isDrawing && selectedNodes.isNotEmpty()) {
            linePaint.color = activeColor; linePaint.alpha = 80
            val last = nodePositions[selectedNodes.last()]
            canvas.drawLine(last.x, last.y, currentTouchX, currentTouchY, linePaint)
        }
        for (i in 0 until NODE_COUNT) {
            val pos = nodePositions[i]
            if (selectedNodes.contains(i)) {
                glowPaint.color = activeDim; canvas.drawCircle(pos.x, pos.y, selectedRadius * 2f, glowPaint)
                outerRingPaint.color = activeColor; outerRingPaint.alpha = 200; canvas.drawCircle(pos.x, pos.y, selectedRadius, outerRingPaint)
                selectedPaint.color = activeColor; canvas.drawCircle(pos.x, pos.y, dotRadius, selectedPaint)
            } else {
                outerRingPaint.color = 0x44FFFFFF.toInt(); canvas.drawCircle(pos.x, pos.y, dotRadius + 8f, outerRingPaint)
                normalPaint.color = 0xAAFFFFFF.toInt(); canvas.drawCircle(pos.x, pos.y, dotRadius, normalPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                clearRunnable?.let { clearHandler.removeCallbacks(it) }
                clearRunnable = null
                parent?.requestDisallowInterceptTouchEvent(true)
                isError = false; isSuccess = false
                selectedNodes.clear()
                isDrawing = true
                currentTouchX = event.x; currentTouchY = event.y
                onPatternListener?.onPatternStart()
                checkNodeHit(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentTouchX = event.x; currentTouchY = event.y
                checkNodeHit(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDrawing = false
                val count = selectedNodes.size
                // Bug 9 Fix: Removed DebugLogger calls from inside touch events.
                // DebugLogger.log() does a synchronous SharedPreferences read + write
                // on the calling thread. Inside onTouchEvent (main thread, fires dozens
                // of times/sec during drawing), this caused ANR freezes on the pattern
                // lock screen. Logging kept at ACTION_UP only as a one-time event log.
                DebugLogger.log(TAG, "Pattern complete: nodes=$count")
                when {
                    count >= MIN_NODES -> onPatternListener?.onPatternComplete(selectedNodes.toList())
                    count > 0 -> { setError(); onTooFewNodes?.invoke() }
                }
                invalidate()
                return true
            }
        }
        return false
    }

    private fun checkNodeHit(x: Float, y: Float) {
        for (i in 0 until NODE_COUNT) {
            if (selectedNodes.contains(i)) continue
            val pos = nodePositions[i]
            val dx = x - pos.x; val dy = y - pos.y
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist <= touchRadius) {
                selectedNodes.add(i); invalidate()
            }
        }
    }

    fun clearPattern() {
        clearRunnable?.let { clearHandler.removeCallbacks(it) }
        clearRunnable = null
        selectedNodes.clear(); isDrawing = false; isError = false; isSuccess = false
        invalidate()
    }

    fun setError() {
        isError = true; invalidate()
        // BUG 1 FIX #3: Runnable ko track karo taaki cancel ho sake
        clearRunnable?.let { clearHandler.removeCallbacks(it) }
        val r = Runnable { clearPattern() }
        clearRunnable = r
        clearHandler.postDelayed(r, 900)
    }

    fun setSuccess() {
        isSuccess = true; invalidate()
        clearRunnable?.let { clearHandler.removeCallbacks(it) }
        val r = Runnable { clearPattern() }
        clearRunnable = r
        clearHandler.postDelayed(r, 400)
    }
}
