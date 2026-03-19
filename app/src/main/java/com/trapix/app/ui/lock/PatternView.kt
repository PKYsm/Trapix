package com.trapix.app.ui.lock

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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

    private val GRID_SIZE = 3
    private val NODE_COUNT = GRID_SIZE * GRID_SIZE

    private var cellSize = 0f
    private var dotRadius = 0f
    private var selectedRadius = 0f
    private var touchRadius = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val nodePositions = Array(NODE_COUNT) { PointF() }
    private val selectedNodes = mutableListOf<Int>()
    private var currentTouchX = 0f
    private var currentTouchY = 0f
    private var isDrawing = false
    private var isError = false
    private var isSuccess = false

    var onPatternListener: OnPatternListener? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force square - use the smaller dimension
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (w > 0 && h > 0) min(w, h) else 300.dpToPx()
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        recalculate(w, h)
    }

    private fun recalculate(w: Int, h: Int) {
        val size = min(w, h).toFloat()
        cellSize = size / GRID_SIZE.toFloat()
        dotRadius = cellSize * 0.12f
        selectedRadius = cellSize * 0.18f
        touchRadius = cellSize * 0.4f  // Very large touch area - hard to miss!
        offsetX = (w - size) / 2f
        offsetY = (h - size) / 2f

        for (i in 0 until NODE_COUNT) {
            val col = i % GRID_SIZE
            val row = i / GRID_SIZE
            nodePositions[i] = PointF(
                offsetX + cellSize * col + cellSize / 2f,
                offsetY + cellSize * row + cellSize / 2f
            )
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize == 0f) recalculate(width, height)

        val activeColor = when {
            isError -> 0xFFFF1744.toInt()
            isSuccess -> 0xFF00E676.toInt()
            else -> 0xFF00E5FF.toInt()
        }
        val activeDim = when {
            isError -> 0x44FF1744.toInt()
            isSuccess -> 0x4400E676.toInt()
            else -> 0x4400E5FF.toInt()
        }

        // Draw lines between selected nodes
        if (selectedNodes.size > 1) {
            linePaint.color = activeColor
            linePaint.alpha = 160
            for (i in 0 until selectedNodes.size - 1) {
                val from = nodePositions[selectedNodes[i]]
                val to = nodePositions[selectedNodes[i + 1]]
                canvas.drawLine(from.x, from.y, to.x, to.y, linePaint)
            }
        }

        // Line to current finger position
        if (isDrawing && selectedNodes.isNotEmpty()) {
            linePaint.color = activeColor
            linePaint.alpha = 80
            val last = nodePositions[selectedNodes.last()]
            canvas.drawLine(last.x, last.y, currentTouchX, currentTouchY, linePaint)
        }

        // Draw each node
        for (i in 0 until NODE_COUNT) {
            val pos = nodePositions[i]
            val isSelected = selectedNodes.contains(i)

            if (isSelected) {
                // Glow
                glowPaint.color = activeDim
                canvas.drawCircle(pos.x, pos.y, selectedRadius * 2f, glowPaint)
                // Ring
                outerRingPaint.color = activeColor
                outerRingPaint.alpha = 200
                canvas.drawCircle(pos.x, pos.y, selectedRadius, outerRingPaint)
                // Center dot
                selectedPaint.color = activeColor
                canvas.drawCircle(pos.x, pos.y, dotRadius, selectedPaint)
            } else {
                // Outer ring
                outerRingPaint.color = 0x44FFFFFF.toInt()
                canvas.drawCircle(pos.x, pos.y, dotRadius + 8f, outerRingPaint)
                // Center dot
                normalPaint.color = 0xAAFFFFFF.toInt()
                canvas.drawCircle(pos.x, pos.y, dotRadius, normalPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isError = false
                isSuccess = false
                selectedNodes.clear()
                isDrawing = true
                currentTouchX = event.x
                currentTouchY = event.y
                onPatternListener?.onPatternStart()
                checkNodeHit(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentTouchX = event.x
                currentTouchY = event.y
                checkNodeHit(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                if (selectedNodes.isNotEmpty()) {
                    onPatternListener?.onPatternComplete(selectedNodes.toList())
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
            val dx = x - pos.x
            val dy = y - pos.y
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist <= touchRadius) {
                selectedNodes.add(i)
                invalidate()
            }
        }
    }

    fun clearPattern() {
        selectedNodes.clear()
        isDrawing = false
        isError = false
        isSuccess = false
        invalidate()
    }

    fun setError() {
        isError = true
        invalidate()
        Handler(Looper.getMainLooper()).postDelayed({ clearPattern() }, 900)
    }

    fun setSuccess() {
        isSuccess = true
        invalidate()
        Handler(Looper.getMainLooper()).postDelayed({ clearPattern() }, 400)
    }
}
