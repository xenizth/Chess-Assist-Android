package com.example.chessassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Screen mode: captures this phone's screen every few seconds, asks the vision model whether a chess
 * board is on it, and (for the colour you chose) speaks the best move after the opponent's move.
 * Runs as a foreground service so it keeps working while a chess app or video is in front.
 */
class ScreenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var engine: UciEngine? = null
    private lateinit var speaker: Speaker

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY

        // Must be in the foreground (with the mediaProjection type) before using the projection.
        startForegroundCompat("Starting screen mode…")

        val settings = Settings(this)
        if (settings.apiKey.isBlank()) {
            update("Set your OpenRouter key in the app first.")
            stopSelf(); return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val code = intent.getIntExtra("code", 0)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val me = if (intent.getBooleanExtra("white", true)) Side.WHITE else Side.BLACK

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "chess-assist", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )

        val recognizer = CloudRecognizer(settings.apiKey, settings.model)
        job = scope.launch { loop(recognizer, me, w, h) }
        return START_NOT_STICKY
    }

    private suspend fun loop(recognizer: CloudRecognizer, me: Side, w: Int, h: Int) {
        try {
            engine = UciEngine(this).also { it.start() }
        } catch (e: Exception) {
            update("Engine error: ${e.message}"); stopSelf(); return
        }

        var lastBmp: Bitmap? = null
        var lastSig: IntArray? = null
        var haveReading = false
        var lastSeen: Map<Square, Piece>? = null   // null with haveReading = "no board on screen"
        var candidate: Map<Square, Piece>? = null
        var tracker: MoveTracker? = null
        update("Watching the screen for a chess board…")

        while (scope.isActive) {
            delay(INTERVAL_MS)
            try {
                val fresh = grab(w, h)
                val bmp = fresh ?: lastBmp ?: continue
                if (fresh != null) lastBmp = fresh

                val sig = signature(bmp)
                val unchanged = lastSig != null && sigDiff(sig, lastSig!!) < 3
                lastSig = sig

                // Unchanged screen: reuse the last reading instead of paying for another API call.
                val seen: Map<Square, Piece>? =
                    if (unchanged && haveReading) lastSeen
                    else recognizer.recognizeScreen(bmp).also { lastSeen = it; haveReading = true }

                if (seen == null) {
                    if (tracker != null || candidate != null) update("No chess board on screen.")
                    tracker = null; candidate = null
                    continue
                }

                val trk = tracker
                if (trk == null) {
                    // Need two matching readings before trusting a new position.
                    if (candidate != null && candidate == seen) {
                        val t = MoveTracker(fenFor(seen, me), assumeReady = true, confirmFrames = 2)
                        tracker = t
                        suggest(t)
                    } else {
                        candidate = seen
                    }
                    continue
                }

                when (val res = trk.update(seen)) {
                    MoveTracker.Result.Nothing, MoveTracker.Result.BoardConfirmed -> Unit
                    is MoveTracker.Result.MovePlayed -> {
                        if (trk.board.isMated) { say("Checkmate."); update("Checkmate.") }
                        else if (trk.board.isDraw) { say("Draw."); update("Draw.") }
                        else if (res.mover != me) suggest(trk)
                        else update("You played ${res.move}. Waiting for your opponent.")
                    }
                }
            } catch (e: Exception) {
                update("Error: ${e.message}")
            }
        }
    }

    private suspend fun suggest(trk: MoveTracker) {
        val uci = engine?.bestMove(trk.board.fen) ?: return
        val move = trk.board.legalMoves().firstOrNull { it.toString().equals(uci, ignoreCase = true) } ?: return
        val text = MovePhrase.describe(trk.board, move)
        say(text)
        update("Your move: $text")
    }

    private fun say(text: String) = speaker.say(text)

    /** Latest frame, or null if the screen has not changed since the last grab. */
    private fun grab(w: Int, h: Int): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * w
            val wide = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(plane.buffer)
            return if (wide.width != w) Bitmap.createBitmap(wide, 0, 0, w, h) else wide
        } finally {
            img.close()
        }
    }

    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        val px = IntArray(24 * 24)
        s.getPixels(px, 0, 24, 0, 0, 24, 24)
        return IntArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
        }
    }

    private fun sigDiff(a: IntArray, b: IntArray): Int {
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    /** Builds a full FEN from a piece map; the side to move is the user's colour for the first position. */
    private fun fenFor(m: Map<Square, Piece>, sideToMove: Side): String {
        val placement = CloudRecognizer.placement(m)
        var castle = ""
        if (m[Square.E1] == Piece.WHITE_KING && m[Square.H1] == Piece.WHITE_ROOK) castle += "K"
        if (m[Square.E1] == Piece.WHITE_KING && m[Square.A1] == Piece.WHITE_ROOK) castle += "Q"
        if (m[Square.E8] == Piece.BLACK_KING && m[Square.H8] == Piece.BLACK_ROOK) castle += "k"
        if (m[Square.E8] == Piece.BLACK_KING && m[Square.A8] == Piece.BLACK_ROOK) castle += "q"
        if (castle.isEmpty()) castle = "-"
        val stm = if (sideToMove == Side.WHITE) "w" else "b"
        return "$placement $stm $castle - 0 1"
    }

    // ---- notification plumbing ----

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chess Assist", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stop,
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chess Assist: screen mode")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun update(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        display?.release()
        reader?.close()
        projection?.stop()
        engine?.close()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.chessassist.STOP"
        private const val CHANNEL = "chess_assist"
        private const val NOTIF_ID = 42
        private const val INTERVAL_MS = 3000L
    }
}
