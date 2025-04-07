package com.example.doodlejump

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val holder: SurfaceHolder = getHolder()
    private var gameThread: Thread? = null
    private var isRunning = false
    private var isGameOver = false
    private var isPaused = false
    private var pauseStartTime = 0L
    private var resumeCountdown = 0
    private var pausedBackground: Bitmap? = null
    private lateinit var pauseButtonRect: RectF

    // Graphics
    private val paint = Paint().apply { isAntiAlias = true }
    private lateinit var playerBitmap: Bitmap
    private lateinit var backgroundBitmap: Bitmap
    private lateinit var platformBitmap: Bitmap
    private var backgroundRect = Rect()
    private val platformHighlightPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    // Audio
    private lateinit var soundPool: SoundPool
    private var jumpSoundId = 0
    private var loseSoundId = 0
    private lateinit var backgroundMusic: MediaPlayer
    private var isMusicPrepared = false

    // Player
    private var playerX = 0f
    private var playerY = 0f
    private val playerWidth = 150f
    private val playerHeight = 190f
    private var playerVelocityY = 0f
    private var playerVelocityX = 0f
    private var gravity = 0.5f
    private val baseGravity = 0.5f
    private val jumpForce = -22f
    private val moveAcceleration = 0.2f
    private val maxMoveSpeed = 12f
    private val friction = 0.9f

    // Platforms
    private val platforms = mutableListOf<Platform>()
    private val platformWidth = 170f
    private val platformHeight = 43f
    private var minPlatformDistance = 100f
    private var maxPlatformDistance = 200f
    private var specialPlatformChance = 0.1f
    private var lastPlatformId = -1
    private var canJumpAgain = true

    // Game progression
    private var score = 0
    private var cameraY = 0f
    private var difficultyFactor = 1.0f
    private val difficultyIncreaseInterval = 500
    private var nextDifficultyIncrease = difficultyIncreaseInterval

    init {
        holder.addCallback(this)
        isFocusable = true
        loadGraphics()
        initSounds()
    }

    private fun loadGraphics() {
        playerBitmap = BitmapFactory.decodeResource(resources, R.drawable.kenguru)
            .let { Bitmap.createScaledBitmap(it, playerWidth.toInt(), playerHeight.toInt(), true) }

        backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.fon)
        platformBitmap = BitmapFactory.decodeResource(resources, R.drawable.obstacle)
            .let { Bitmap.createScaledBitmap(it, platformWidth.toInt(), platformHeight.toInt(), true) }
    }

    private fun initSounds() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()

        jumpSoundId = soundPool.load(context, R.raw.jump, 1)
        loseSoundId = soundPool.load(context, R.raw.lose, 1)

        backgroundMusic = MediaPlayer.create(context, R.raw.fon)
        backgroundMusic.isLooping = true
        backgroundMusic.setVolume(0.5f, 0.5f)
        isMusicPrepared = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundRect.set(0, 0, w, h)
        resetGame()
    }

    private fun resetGame() {
        playerX = width / 2f - playerWidth / 2
        playerY = height * 0.75f
        playerVelocityY = 0f
        playerVelocityX = 0f
        score = 0
        isGameOver = false
        isPaused = false
        cameraY = 0f
        platforms.clear()
        lastPlatformId = -1
        difficultyFactor = 1.0f
        nextDifficultyIncrease = difficultyIncreaseInterval
        gravity = baseGravity
        canJumpAgain = true
        pausedBackground = null

        var currentY = height.toFloat()
        repeat(15) {
            addPlatform(currentY)
            currentY -= Random.nextFloat() * (maxPlatformDistance - minPlatformDistance) + minPlatformDistance
        }

        if (isMusicPrepared) {
            backgroundMusic.seekTo(0)
            backgroundMusic.start()
        }
    }

    private fun addPlatform(y: Float) {
        val maxX = width - platformWidth
        val x = Random.nextFloat() * maxX
        val isSpecial = Random.nextFloat() < specialPlatformChance

        platforms.add(
            Platform(
                id = platforms.size,
                x = x,
                y = y,
                width = platformWidth,
                height = platformHeight,
                isSpecial = isSpecial
            )
        )
    }

    private fun increaseDifficulty() {
        difficultyFactor += 0.1f
        gravity = baseGravity * difficultyFactor
        minPlatformDistance = (100f / difficultyFactor).coerceAtLeast(70f)
        maxPlatformDistance = (200f / difficultyFactor).coerceAtLeast(150f)
        specialPlatformChance = (0.1f * difficultyFactor).coerceAtMost(0.3f)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        gameThread = Thread(this).apply { start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        gameThread?.join()
        releaseSounds()
    }

    private fun releaseSounds() {
        soundPool.release()
        if (isMusicPrepared) {
            backgroundMusic.stop()
            backgroundMusic.release()
            isMusicPrepared = false
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun run() {
        while (isRunning) {
            if (!holder.surface.isValid) continue

            if (!isGameOver && !isPaused) {
                update()
            }
            draw()

            try {
                Thread.sleep(16)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    private fun update() {
        if (isPaused) return

        playerVelocityX *= friction
        playerX += playerVelocityX
        playerY += playerVelocityY
        playerVelocityY += gravity

        if (playerX < -playerWidth) {
            playerX = width.toFloat()
        } else if (playerX > width) {
            playerX = -playerWidth
        }

        val isPlayerAboveMidScreen = playerY < cameraY + height / 2

        for (platform in platforms) {
            if (isPlayerOnPlatform(platform)) {
                if (platform.id != lastPlatformId || (platform.id == lastPlatformId && isPlayerAboveMidScreen && canJumpAgain)) {
                    when {
                        platform.isSpecial -> {
                            playerVelocityY = jumpForce * 1.5f
                            if (platform.id != lastPlatformId) {
                                score += 15
                            }
                            soundPool.play(jumpSoundId, 1.0f, 1.0f, 0, 0, 1.5f)
                        }
                        else -> {
                            playerVelocityY = jumpForce
                            if (platform.id != lastPlatformId) {
                                score += 5
                            }
                            soundPool.play(jumpSoundId, 1.0f, 1.0f, 0, 0, 1.0f)
                        }
                    }
                    lastPlatformId = platform.id
                    canJumpAgain = false
                }
                break
            }
        }

        if (playerY < cameraY + height / 3) {
            canJumpAgain = true
        }

        if (playerY > cameraY + height) {
            isGameOver = true
            soundPool.play(loseSoundId, 1.0f, 1.0f, 0, 0, 1.0f)
            if (isMusicPrepared) {
                backgroundMusic.pause()
            }
            showGameOver()
            return
        }

        val targetCameraY = playerY - height * 0.6f
        if (targetCameraY < cameraY) {
            cameraY = targetCameraY
        }

        if (score >= nextDifficultyIncrease) {
            increaseDifficulty()
            nextDifficultyIncrease += difficultyIncreaseInterval
        }

        platforms.removeIf { it.y > cameraY + height + 100 }

        val highestPlatformY = platforms.minByOrNull { it.y }?.y ?: cameraY
        if (highestPlatformY > cameraY - 100) {
            var currentY = highestPlatformY
            while (currentY > cameraY - 1000) {
                val distance = Random.nextFloat() * (maxPlatformDistance - minPlatformDistance) + minPlatformDistance
                currentY -= distance
                addPlatform(currentY)
            }
        }
    }

    private fun isPlayerOnPlatform(platform: Platform): Boolean {
        return playerVelocityY > 0 &&
                playerY + playerHeight > platform.y &&
                playerY + playerHeight < platform.y + platformHeight + 10 &&
                playerX + playerWidth > platform.x &&
                playerX < platform.x + platform.width
    }

    private fun draw() {
        val canvas = holder.lockCanvas() ?: return

        try {
            if (isPaused && pausedBackground != null) {
                canvas.drawBitmap(pausedBackground!!, 0f, 0f, paint)
            } else {
                canvas.drawBitmap(backgroundBitmap, null, backgroundRect, paint)

                if (isPaused && pausedBackground == null) {
                    pausedBackground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val tempCanvas = Canvas(pausedBackground!!)
                    tempCanvas.drawBitmap(backgroundBitmap, null, backgroundRect, paint)

                    tempCanvas.save()
                    tempCanvas.translate(0f, -cameraY)
                    for (platform in platforms) {
                        if (platform.y > cameraY - platformHeight && platform.y < cameraY + height + platformHeight) {
                            tempCanvas.drawBitmap(platformBitmap, platform.x, platform.y, paint)
                            if (platform.isSpecial) {
                                val highlightRect = RectF(
                                    platform.x - 5,
                                    platform.y - 5,
                                    platform.x + platform.width + 5,
                                    platform.y + platform.height + 5
                                )
                                tempCanvas.drawRoundRect(highlightRect, 10f, 10f, platformHighlightPaint)
                            }
                        }
                    }
                    tempCanvas.drawBitmap(playerBitmap, playerX, playerY, paint)
                    tempCanvas.restore()
                }
            }

            if (!isPaused) {
                canvas.save()
                canvas.translate(0f, -cameraY)

                for (platform in platforms) {
                    if (platform.y > cameraY - platformHeight && platform.y < cameraY + height + platformHeight) {
                        canvas.drawBitmap(platformBitmap, platform.x, platform.y, paint)

                        if (platform.isSpecial) {
                            val highlightRect = RectF(
                                platform.x - 5,
                                platform.y - 5,
                                platform.x + platform.width + 5,
                                platform.y + platform.height + 5
                            )
                            canvas.drawRoundRect(highlightRect, 10f, 10f, platformHighlightPaint)
                        }
                    }
                }

                canvas.drawBitmap(playerBitmap, playerX, playerY, paint)
                canvas.restore()
            }

            // Кнопка паузы (увеличенный размер)
            pauseButtonRect = RectF(30f, 30f, 150f, 150f)
            paint.color = Color.argb(150, 100, 100, 100)
            canvas.drawRoundRect(pauseButtonRect, 20f, 20f, paint)
            paint.color = Color.WHITE
            paint.textSize = 80f
            canvas.drawText("II", pauseButtonRect.left + 45f, pauseButtonRect.top + 100f, paint)

            // Счёт под кнопкой паузы
            paint.color = Color.BLACK
            paint.textSize = 50f
            canvas.drawText("Очки: $score", 30f, 200f, paint)

            if (isGameOver) {
                paint.color = Color.RED
                paint.textSize = 100f
                val text = "ИГРА ОКОНЧЕНА"
                val textWidth = paint.measureText(text)
                canvas.drawText(text, width / 2f - textWidth / 2, height / 2f, paint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun showPauseMenu() {
        (context as MainActivity).runOnUiThread {
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 50, 50, 50)
                setBackgroundColor(Color.argb(220, 40, 40, 40))

                val title = TextView(context).apply {
                    text = "ПАУЗА"
                    setTextColor(Color.YELLOW)
                    textSize = 28f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, 30)
                }
                addView(title)

                val scoreText = TextView(context).apply {
                    text = "Ваш счет: $score"
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                }
                addView(scoreText)
            }

            AlertDialog.Builder(context)
                .setView(layout)
                .setPositiveButton("Вернуться") { _, _ ->
                    resumeAfterDelay()
                }
                .setNegativeButton("В меню") { _, _ ->
                    (context as MainActivity).finish()
                }
                .setCancelable(false)
                .create()
                .also { dialog ->
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    dialog.show()

                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.argb(100, 0, 150, 0))
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.argb(100, 150, 0, 0))
                    }
                }
        }
    }

    private fun resumeAfterDelay() {
        isPaused = true
        pauseStartTime = System.currentTimeMillis()

        // Просто 2-секундная задержка без обратного отсчёта
        postDelayed({
            isPaused = false
            pausedBackground = null
            if (isMusicPrepared) {
                backgroundMusic.start()
            }
        }, 2000)
    }

    private fun showGameOver() {
        (context as MainActivity).runOnUiThread {
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 50, 50, 50)
                setBackgroundColor(Color.argb(220, 40, 40, 40))

                val title = TextView(context).apply {
                    text = "ИГРА ОКОНЧЕНА"
                    setTextColor(Color.RED)
                    textSize = 28f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, 30)
                }
                addView(title)

                val scoreText = TextView(context).apply {
                    text = "Счёт: $score"
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                }
                addView(scoreText)
            }

            AlertDialog.Builder(context)
                .setView(layout)
                .setPositiveButton("Заново") { _, _ ->
                    resetGame()
                    if (isMusicPrepared) {
                        backgroundMusic.start()
                    }
                }
                .setNegativeButton("В меню") { _, _ ->
                    (context as MainActivity).finish()
                }
                .setCancelable(false)
                .create()
                .also { dialog ->
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    dialog.show()

                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.argb(100, 0, 150, 0))
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.argb(100, 150, 0, 0))
                    }
                }
        }
    }

    private var lastTouchX = 0f
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isGameOver) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (pauseButtonRect.contains(event.x, event.y)) {
                    if (!isPaused) {
                        isPaused = true
                        if (isMusicPrepared) {
                            backgroundMusic.pause()
                        }
                        showPauseMenu()
                    }
                    return true
                }
                lastTouchX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isPaused) {
                    val dx = event.x - lastTouchX
                    playerVelocityX += dx * moveAcceleration
                    playerVelocityX = playerVelocityX.coerceIn(-maxMoveSpeed, maxMoveSpeed)
                    lastTouchX = event.x
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private data class Platform(
        val id: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val isSpecial: Boolean
    )
}