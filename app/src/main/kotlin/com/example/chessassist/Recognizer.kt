package com.example.chessassist

import android.content.Context
import android.graphics.Bitmap
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Turns a square, board-aligned image into "which piece is on which square". */
interface BoardRecognizer {
    /**
     * @param boardImage square crop of the board (the part inside the on-screen guide)
     * @param flipped true when the board is viewed from black's side (rotate 180°)
     */
    fun recognize(boardImage: Bitmap, flipped: Boolean): Map<Square, Piece>
}

/**
 * On-device recognizer for a YOLOv8-style TFLite piece detector.
 *
 * You must supply `app/src/main/assets/chess_pieces.tflite`, trained on 12 classes in the order
 * of [CLASSES]. Expected input [1, S, S, 3] float32 in 0..1; expected output either
 * [1, 4+12, N] (YOLOv8 default) or [1, N, 4+12], boxes as normalized-or-pixel cx, cy, w, h.
 */
class TfliteBoardRecognizer(context: Context, modelAsset: String = "chess_pieces.tflite") : BoardRecognizer {

    private val interpreter: Interpreter
    private val inputSize: Int

    init {
        val fd = context.assets.openFd(modelAsset)
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(buf, Interpreter.Options().setNumThreads(4))
        inputSize = interpreter.getInputTensor(0).shape()[1]
    }

    override fun recognize(boardImage: Bitmap, flipped: Boolean): Map<Square, Piece> {
        val scaled = Bitmap.createScaledBitmap(boardImage, inputSize, inputSize, true)
        val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val px = IntArray(inputSize * inputSize)
        scaled.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in px) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()

        val shape = interpreter.getOutputTensor(0).shape() // [1, a, b]
        val channelsFirst = shape[1] == 4 + CLASSES.size
        val n = if (channelsFirst) shape[2] else shape[1]
        val c = 4 + CLASSES.size
        val flat = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        interpreter.run(input, flat)

        fun v(i: Int, k: Int): Float = if (channelsFirst) flat[0][k][i] else flat[0][i][k]

        data class Det(val cx: Float, val baseY: Float, val cls: Int, val score: Float)
        val dets = ArrayList<Det>()
        for (i in 0 until n) {
            var best = -1; var bestScore = 0f
            for (k in 0 until CLASSES.size) {
                val s = v(i, 4 + k)
                if (s > bestScore) { bestScore = s; best = k }
            }
            if (best < 0 || bestScore < CONF_THRESHOLD) continue
            var cx = v(i, 0); var cy = v(i, 1); val h = v(i, 3)
            // Some exports give pixels, some normalized coordinates.
            if (cx > 1.5f || cy > 1.5f) { cx /= inputSize; cy /= inputSize; }
            val hh = if (v(i, 3) > 1.5f) h / inputSize else h
            // A piece stands on the square at the *bottom* of its box, not at its center.
            dets += Det(cx, cy + hh * BASE_OFFSET, best, bestScore)
        }

        val perSquare = HashMap<Square, Det>()
        for (d in dets) {
            var col = (d.cx * 8).toInt().coerceIn(0, 7)
            var row = (d.baseY * 8).toInt().coerceIn(0, 7) // 0 = top of the image
            if (flipped) { col = 7 - col; row = 7 - row }
            val file = col                  // a..h left to right (white's view)
            val rank = 7 - row              // rank 1 at the bottom
            val sq = Square.squareAt(rank * 8 + file)
            val cur = perSquare[sq]
            if (cur == null || d.score > cur.score) perSquare[sq] = d
        }
        return perSquare.mapValues { CLASSES[it.value.cls] }
    }

    companion object {
        const val CONF_THRESHOLD = 0.45f
        const val BASE_OFFSET = 0.30f // fraction of box height below center that counts as "the base"

        /** Class order the model must be trained with. */
        val CLASSES: List<Piece> = listOf(
            Piece.WHITE_PAWN, Piece.WHITE_KNIGHT, Piece.WHITE_BISHOP,
            Piece.WHITE_ROOK, Piece.WHITE_QUEEN, Piece.WHITE_KING,
            Piece.BLACK_PAWN, Piece.BLACK_KNIGHT, Piece.BLACK_BISHOP,
            Piece.BLACK_ROOK, Piece.BLACK_QUEEN, Piece.BLACK_KING,
        )
    }
}
