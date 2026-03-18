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

    private var dotRadius = 12f
    private var selectedRadius = 22f
    private var touchRadius = 40f

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
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

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val minDim = min(w, h).toFloat()
        dotRadius = minDim * 0.045f
        selectedRadius = minDim * 0.07f
        touchRadius = minDim * 0.14f

        val cellW = w / GRID_SIZE.toFloat()
        val cellH = h / GRID_SIZE.toFloat()
        for (i in 0 until NODE_COUNT) {
            val col = i % GRID_SIZE
            val row = i / GRID_SIZE
            nodePositions[i] = PointF(cellW * col + cellW / 2f, cellH * row + cellH / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeColor = when { isError -> 0xFFFF1744.toInt(); isSuccess -> 0xFF00E676.toInt(); else -> 0xFF00E5FF.toInt() }
        val activeDim = when { isError -> 0x55FF1744.toInt(); isSuccess -> 0x5500E676.toInt(); else -> 0x5500E5FF.toInt() }

        linePaint.color = activeColor; linePaint.alpha = 180
        for (i in 0 until selectedNodes.size - 1) {
            val from = nodePositions[selectedNodes[i]]; val to = nodePositions[selectedNodes[i + 1]]
            canvas.drawLine(from.x, from.y, to.x, to.y, linePaint)
        }
        if (isDrawing && selectedNodes.isNotEmpty()) {
            linePaint.alpha = 80
            val last = nodePositions[selectedNodes.last()]
            canvas.drawLine(last.x, last.y, currentTouchX, currentTouchY, linePaint)
        }
        for (i in 0 until NODE_COUNT) {
            val pos = nodePositions[i]
            if (selectedNodes.contains(i)) {
                glowPaint.color = activeDim
                canvas.drawCircle(pos.x, pos.y, selectedRadius * 1.8f, glowPaint)
                outerRingPaint.color = activeColor; outerRingPaint.alpha = 150
                canvas.drawCircle(pos.x, pos.y, selectedRadius + 3f, outerRingPaint)
                selectedPaint.color = activeColor
                canvas.drawCircle(pos.x, pos.y, dotRadius * 1.3f, selectedPaint)
            } else {
                outerRingPaint.color = 0x33FFFFFF.toInt()
                canvas.drawCircle(pos.x, pos.y, dotRadius + 6f, outerRingPaint)
                normalPaint.color = 0x88FFFFFF.toInt()
                canvas.drawCircle(pos.x, pos.y, dotRadius, normalPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isError = false; isSuccess = false; selectedNodes.clear(); isDrawing = true
                currentTouchX = event.x; currentTouchY = event.y
                onPatternListener?.onPatternStart()
                checkNodeHit(event.x, event.y); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentTouchX = event.x; currentTouchY = event.y
                checkNodeHit(event.x, event.y); invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                if (selectedNodes.isNotEmpty()) onPatternListener?.onPatternComplete(selectedNodes.toList())
                invalidate(); return true
            }
        }
        return false
    }

    private fun checkNodeHit(x: Float, y: Float) {
        for (i in 0 until NODE_COUNT) {
            if (selectedNodes.contains(i)) continue
            val pos = nodePositions[i]
            val dist = sqrt(((x - pos.x).toDouble().let { it * it } + (y - pos.y).toDouble().let { it * it })).toFloat()
            if (dist <= touchRadius) { selectedNodes.add(i); invalidate() }
        }
    }

    fun clearPattern() { selectedNodes.clear(); isDrawing = false; isError = false; isSuccess = false; invalidate() }
    fun setError() { isError = true; invalidate(); Handler(Looper.getMainLooper()).postDelayed({ clearPattern() }, 800) }
    fun setSuccess() { isSuccess = true; invalidate(); Handler(Looper.getMainLooper()).postDelayed({ clearPattern() }, 400) }
}
