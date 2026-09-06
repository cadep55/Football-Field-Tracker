package com.footballwidget.widget

import android.graphics.*
import com.footballwidget.data.GameState
import com.footballwidget.data.GameStatus

object FieldRenderer {

    private const val FIELD_WIDTH = 800f
    private const val FIELD_HEIGHT = 300f

    fun renderField(state: GameState?): Bitmap {
        val bitmap = Bitmap.createBitmap(FIELD_WIDTH.toInt(), FIELD_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (state == null) {
            drawEmptyField(canvas)
            return bitmap
        }

        drawTurf(canvas)
        drawEndZones(canvas, state)
        drawYardLines(canvas)
        drawYardNumbers(canvas)
        drawHashMarks(canvas)

        when (state.status) {
            GameStatus.LIVE -> {
                drawLineOfScrimmage(canvas, state)
                drawFirstDownLine(canvas, state)
                drawBall(canvas, state)
            }
            GameStatus.PRE -> drawOverlayText(canvas, state.clock)
            GameStatus.POST -> drawOverlayText(canvas, "FINAL  ${state.awayTeam.abbreviation} ${state.awayScore}  -  ${state.homeTeam.abbreviation} ${state.homeScore}")
        }

        return bitmap
    }

    private fun drawEmptyField(canvas: Canvas) {
        drawTurf(canvas)
        drawYardLines(canvas)
        drawYardNumbers(canvas)
        drawHashMarks(canvas)
        drawOverlayText(canvas, "No game - check back on game day")
    }

    private fun drawTurf(canvas: Canvas) {
        val darkGreen = Color.parseColor("#1B5E20")
        val lightGreen = Color.parseColor("#2E7D32")
        val paint = Paint()
        val stripeWidth = FIELD_WIDTH / 20f

        for (i in 0 until 20) {
            paint.color = if (i % 2 == 0) darkGreen else lightGreen
            canvas.drawRect(
                i * stripeWidth, 0f,
                (i + 1) * stripeWidth, FIELD_HEIGHT,
                paint
            )
        }
    }

    private fun drawEndZones(canvas: Canvas, state: GameState) {
        val endZoneWidth = FIELD_WIDTH * 0.08f
        val paint = Paint()

        // Left endzone = away team
        val awayColor = getTeamColor(state.awayTeam.abbreviation, state.awayTeam.color)
        paint.color = awayColor
        canvas.drawRect(0f, 0f, endZoneWidth, FIELD_HEIGHT, paint)

        // Right endzone = home team
        val homeColor = getTeamColor(state.homeTeam.abbreviation, state.homeTeam.color)
        paint.color = homeColor
        canvas.drawRect(FIELD_WIDTH - endZoneWidth, 0f, FIELD_WIDTH, FIELD_HEIGHT, paint)

        // Endzone text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            alpha = 200
        }

        // Away text (left, rotated -90)
        canvas.save()
        canvas.rotate(-90f, endZoneWidth / 2, FIELD_HEIGHT / 2)
        canvas.drawText(state.awayTeam.abbreviation, endZoneWidth / 2, FIELD_HEIGHT / 2 + 8f, textPaint)
        canvas.restore()

        // Home text (right, rotated 90)
        canvas.save()
        canvas.rotate(90f, FIELD_WIDTH - endZoneWidth / 2, FIELD_HEIGHT / 2)
        canvas.drawText(state.homeTeam.abbreviation, FIELD_WIDTH - endZoneWidth / 2, FIELD_HEIGHT / 2 + 8f, textPaint)
        canvas.restore()
    }

    private fun drawYardLines(canvas: Canvas) {
        val paint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 1.5f
            alpha = 160
        }

        val endZoneWidth = FIELD_WIDTH * 0.08f
        val playableWidth = FIELD_WIDTH - 2 * endZoneWidth

        // Lines every 5 yards (0, 5, 10, ..., 100)
        for (i in 0..20) {
            val x = endZoneWidth + (i / 20f) * playableWidth
            paint.strokeWidth = if (i % 2 == 0) 1.5f else 0.8f
            paint.alpha = if (i % 2 == 0) 160 else 100
            canvas.drawLine(x, 0f, x, FIELD_HEIGHT, paint)
        }
    }

    private fun drawYardNumbers(canvas: Canvas) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            alpha = 120
        }

        val endZoneWidth = FIELD_WIDTH * 0.08f
        val playableWidth = FIELD_WIDTH - 2 * endZoneWidth
        val yardNumbers = intArrayOf(10, 20, 30, 40, 50, 40, 30, 20, 10)

        for ((idx, num) in yardNumbers.withIndex()) {
            val yardPos = (idx + 1) * 10
            val x = endZoneWidth + (yardPos / 100f) * playableWidth
            canvas.drawText(num.toString(), x, 20f, textPaint)
            canvas.drawText(num.toString(), x, FIELD_HEIGHT - 6f, textPaint)
        }
    }

    private fun drawHashMarks(canvas: Canvas) {
        val paint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 0.8f
            alpha = 80
        }

        val endZoneWidth = FIELD_WIDTH * 0.08f
        val playableWidth = FIELD_WIDTH - 2 * endZoneWidth
        val topHash = FIELD_HEIGHT * 0.33f
        val bottomHash = FIELD_HEIGHT * 0.67f

        for (i in 0..100) {
            if (i % 5 == 0) continue
            val x = endZoneWidth + (i / 100f) * playableWidth
            canvas.drawLine(x, topHash - 3f, x, topHash + 3f, paint)
            canvas.drawLine(x, bottomHash - 3f, x, bottomHash + 3f, paint)
        }
    }

    private fun drawLineOfScrimmage(canvas: Canvas, state: GameState) {
        if (state.yardLine == null || state.possession == null) return

        val ballPct = getBallPositionPct(state)
        val endZoneWidth = FIELD_WIDTH * 0.08f
        val playableWidth = FIELD_WIDTH - 2 * endZoneWidth
        val x = endZoneWidth + (ballPct / 100f) * playableWidth

        val paint = Paint().apply {
            color = Color.parseColor("#2196F3")
            strokeWidth = 3f
            alpha = 200
        }
        canvas.drawLine(x, 0f, x, FIELD_HEIGHT, paint)
    }

    private fun drawFirstDownLine(canvas: Canvas, state: GameState) {
        if (state.yardLine == null || state.possession == null) return
        if (state.down == null || state.distance == null) return
        if (state.down > 3) return

        val ballPct = getBallPositionPct(state)
        val isHomePoss = state.possession == state.homeTeam.abbreviation

        // First down line goes toward the opponent's endzone
        val firstDownPct = if (isHomePoss) {
            ballPct - state.distance
        } else {
            ballPct + state.distance
        }

        val endZoneWidth = FIELD_WIDTH * 0.08f
        val playableWidth = FIELD_WIDTH - 2 * endZoneWidth
        val x = endZoneWidth + (firstDownPct.coerceIn(0f, 100f) / 100f) * playableWidth

        val paint = Paint().apply {
            color = Color.parseColor("#FFC107")
            strokeWidth = 3f
            alpha = 200
        }
        canvas.drawLine(x, 0f, x, FIELD_HEIGHT, paint)
    }

    private fun drawBall(canvas: Canvas, state: GameState) {
        if (state.yardLine == null || state.possession == null) return

        val ballPct = getBallPositionPct(state)
        val endZoneWidth = FIELD_WIDTH * 0.08f
        val playableWidth = FIELD_WIDTH - 2 * endZoneWidth
        val cx = endZoneWidth + (ballPct / 100f) * playableWidth
        val cy = FIELD_HEIGHT / 2f

        val ballW = 16f
        val ballH = 10f

        // Glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, ballW * 2,
                Color.argb(100, 255, 200, 50),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawOval(cx - ballW * 2, cy - ballW * 2, cx + ballW * 2, cy + ballW * 2, glowPaint)

        // Ball body
        val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - 2f, cy - 2f, ballW,
                Color.parseColor("#8D6E4A"),
                Color.parseColor("#5D3A1A"),
                Shader.TileMode.CLAMP
            )
        }
        val ballRect = RectF(cx - ballW, cy - ballH, cx + ballW, cy + ballH)
        canvas.drawOval(ballRect, ballPaint)

        // Ball outline
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#3E2108")
            strokeWidth = 1f
        }
        canvas.drawOval(ballRect, outlinePaint)

        // Laces
        val lacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        // Center seam
        canvas.drawLine(cx - ballW * 0.4f, cy, cx + ballW * 0.4f, cy, lacePaint)
        // Cross laces
        val laceSpacing = ballW * 0.15f
        for (i in -2..2) {
            val lx = cx + i * laceSpacing
            canvas.drawLine(lx, cy - 2.5f, lx, cy + 2.5f, lacePaint)
        }
    }

    private fun drawOverlayText(canvas: Canvas, text: String) {
        // Semi-transparent backdrop
        val bgPaint = Paint().apply {
            color = Color.argb(140, 0, 0, 0)
        }
        canvas.drawRect(0f, FIELD_HEIGHT * 0.35f, FIELD_WIDTH, FIELD_HEIGHT * 0.65f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, FIELD_WIDTH / 2f, FIELD_HEIGHT / 2f + 7f, textPaint)
    }

    private fun getBallPositionPct(state: GameState): Float {
        val yardLine = state.yardLine ?: return 50f
        val possession = state.possession ?: return 50f

        return if (possession == state.homeTeam.abbreviation) {
            100f - yardLine
        } else {
            yardLine.toFloat()
        }
    }

    private fun getTeamColor(abbreviation: String, espnColor: String): Int {
        val hex = TEAM_COLORS[abbreviation.uppercase()] ?: espnColor
        return try {
            Color.parseColor("#$hex")
        } catch (_: Exception) {
            Color.parseColor("#37474F")
        }
    }

    private val TEAM_COLORS = mapOf(
        // NFL
        "ARI" to "97233F", "ATL" to "A71930", "BAL" to "241773",
        "BUF" to "00338D", "CAR" to "0085CA", "CHI" to "0B162A",
        "CIN" to "FB4F14", "CLE" to "311D00", "DAL" to "041E42",
        "DEN" to "FB4F14", "DET" to "0076B6", "GB" to "203731",
        "HOU" to "03202F", "IND" to "002C5F", "JAX" to "006778",
        "KC" to "E31837", "LAC" to "0080C6", "LAR" to "003594",
        "LV" to "000000", "MIA" to "008E97", "MIN" to "4F2683",
        "NE" to "002244", "NO" to "D3BC8D", "NYG" to "0B2265",
        "NYJ" to "125740", "PHI" to "004C54", "PIT" to "FFB612",
        "SEA" to "002244", "SF" to "AA0000", "TB" to "D50A0A",
        "TEN" to "0C2340", "WAS" to "773141",
        // Major college programs
        "BAMA" to "9E1B32", "ALA" to "9E1B32", "OSU" to "BB0000",
        "OHIO" to "BB0000", "MICH" to "00274C", "UGA" to "BA0C2F",
        "GA" to "BA0C2F", "LSU" to "461D7C", "CLEM" to "F56600",
        "ND" to "0C2340", "TEX" to "BF5700", "USC" to "990000",
        "OU" to "841617", "OKLA" to "841617", "PSU" to "041E42",
        "PENN" to "041E42", "ORE" to "154733", "ORST" to "DC4405",
        "WASH" to "4B2E83", "FSU" to "782F40", "TENN" to "FF8200",
        "AUB" to "0C2340", "FLA" to "0021A5", "WISC" to "C5050C",
        "WIS" to "C5050C", "IOWA" to "FFCD00", "MSU" to "18453B",
        "NCST" to "CC0000", "VT" to "660000", "UTAH" to "CC0000",
        "UCLA" to "2D68C4", "COLO" to "CFB87C", "ASU" to "8C1D40",
        "ARK" to "9D2235", "MISS" to "CE1126", "UK" to "0033A0",
        "KSU" to "512888", "TCU" to "4D1979", "BAY" to "003015",
        "NCAAF" to "37474F"
    )
}
