package com.trapix.app.ui.lock

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
    private val dotRadius = 18f
    private val selectedRadius = 28f
    private val lineStrokeWidth = 6f

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
        alpha = 120
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.FILL
    }
    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF1744.toInt()
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        strokeWidth = lineStrokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 180
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 80
    }

    private val nodePositions = Array(NODE_COUNT) { PointF() }
    private val selectedNodes = mutableListOf<Int>()
    private var currentTouchX = 0f
    private var currentTouchY = 0f
    private var isDrawing = false
    private var isError = false
    var onPatternListener: OnPatternListener? = null

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val cellW = w / GRID_SIZE.toFloat()
        val cellH = h / GRID_SIZE.toFloat()
        for (i in 0 until NODE_COUNT) {
            val col = i % GRID_SIZE
            val row = i / GRID_SIZE
            nodePositions[i] = PointF(cellW * col + cellW / 2, cellH * row + cellH / 2)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw connecting lines
        for (i in 0 until selectedNodes.size - 1) {
            val from = nodePositions[selectedNodes[i]]
            val to = nodePositions[selectedNodes[i + 1]]
            linePaint.color = if (isError) 0xFFFF1744.toInt() else 0xFF00E5FF.toInt()
            canvas.drawLine(from.x, from.y, to.x, to.y, linePaint)
        }

        // Draw line to current touch
        if (isDrawing && selectedNodes.isNotEmpty()) {
            val last = nodePositions[selectedNodes.last()]
            linePaint.color = if (isError) 0xFFFF1744.toInt() else 0xFF00E5FF.toInt()
            linePaint.alpha = 100
            canvas.drawLine(last.x, last.y, currentTouchX, currentTouchY, linePaint)
            linePaint.alpha = 180
        }

        // Draw nodes
        for (i in 0 until NODE_COUNT) {
            val pos = nodePositions[i]
            val isSelected = selectedNodes.contains(i)
            if (isSelected) {
                val paint = if (isError) errorPaint else selectedPaint
                outerRingPaint.color = if (isError) 0xFFFF1744.toInt() else 0xFF00E5FF.toInt()
                canvas.drawCircle(pos.x, pos.y, selectedRadius, paint.apply { alpha = 40 })
                canvas.drawCircle(pos.x, pos.y, dotRadius, paint.apply { alpha = 255 })
                canvas.drawCircle(pos.x, pos.y, selectedRadius + 4, outerRingPaint)
            } else {
                canvas.drawCircle(pos.x, pos.y, dotRadius, normalPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isError = false
                selectedNodes.clear()
                isDrawing = true
                onPatternListener?.onPatternStart()
                handleTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouch(event.x, event.y)
                currentTouchX = event.x
                currentTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                if (selectedNodes.size >= 1) {
                    onPatternListener?.onPatternComplete(selectedNodes.toList())
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouch(x: Float, y: Float) {
        for (i in 0 until NODE_COUNT) {
            val pos = nodePositions[i]
            val dist = sqrt(((x - pos.x) * (x - pos.x) + (y - pos.y) * (y - pos.y)).toDouble()).toFloat()
            if (dist <= selectedRadius + 10 && !selectedNodes.contains(i)) {
                selectedNodes.add(i)
                invalidate()
                break
            }
        }
    }

    fun clearPattern() {
        selectedNodes.clear()
        isDrawing = false
        isError = false
        invalidate()
    }

    fun setError() {
        isError = true
        invalidate()
        Handler(Looper.getMainLooper()).postDelayed({
            clearPattern()
        }, 800)
    }

    fun setSuccess() {
        isError = false
        invalidate()
        Handler(Looper.getMainLooper()).postDelayed({
            clearPattern()
        }, 400)
    }
}
