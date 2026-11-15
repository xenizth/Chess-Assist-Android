package com.example.chessassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Stores the OpenRouter key and model name on the phone only (never in the code or repo). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("chess_assist", Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) { prefs.edit().putString("api_key", v.trim()).apply() }
    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { prefs.edit().putString("model", v.trim()).apply() }

    companion object {
        // Free vision models change often. If this one is gone, pick another ":free" vision model on openrouter.ai.
        const val DEFAULT_MODEL = "qwen/qwen2.5-vl-72b-instruct:free"
    }
}

/**
 * Reads the board with a vision model through OpenRouter (OpenAI-compatible API).
 * Asks for the FEN piece-placement field and parses it into a piece map.
 * Throws on any bad reply; the game loop just skips that frame.
 */
class CloudRecognizer(private val apiKey: String, private val model: String) : BoardRecognizer {

    override fun recognize(boardImage: Bitmap, flipped: Boolean): Map<Square, Piece> {
        // Show the model the board from White's side: turn the photo 180° if we sit on Black's side.
        val upright = if (flipped) boardImage.rotate180() else boardImage
        val reply = ask(upright, PROMPT)
        return parsePlacement(reply)
    }

    /** For screenshots: finds a board anywhere on screen. Returns null when no board is visible. */
    fun recognizeScreen(screenshot: Bitmap): Map<Square, Piece>? {
        val reply = ask(screenshot, SCREEN_PROMPT)
        return if (FEN_REGEX.containsMatchIn(reply)) parsePlacement(reply)
        else if (NONE_REGEX.containsMatchIn(reply)) null
        else error("No FEN in reply (…${reply.takeLast(80)})")
    }

    private fun ask(img: Bitmap, prompt: String): String {
        val k = minOf(1f, 1024f / maxOf(img.width, img.height))
        val scaled = Bitmap.createScaledBitmap(img, (img.width * k).toInt(), (img.height * k).toInt(), true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", 4000)
            .put("reasoning", JSONObject().put("effort", "low"))  // ignored by models without reasoning
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val conn = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Title", "Chess Assist")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) error("OpenRouter HTTP $code: ${text.take(200)}")
        val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        fun field(name: String): String = msg.optString(name, "").let { if (it == "null") "" else it }
        // Some "thinking" models put their answer in `reasoning` and leave `content` empty.
        val out = field("content").ifBlank { field("reasoning") }
        if (out.isBlank()) error("Empty reply from $model. It may not support images; try another vision model.")
        return out
    }

    companion object {
        private const val PROMPT =
            "This is a photo of a physical chess board seen from White's side (White at the bottom). " +
            "Give the piece-placement field of the FEN for the position you see, from rank 8 down to rank 1. " +
            "Uppercase = White, lowercase = Black, digits = empty squares. Do not explain. " +
            "Your final line must be exactly: FEN: <placement>"

        private const val SCREEN_PROMPT =
            "This is a screenshot. If it shows a chess board (a chess app, website or video), give the " +
            "piece-placement field of the FEN for the position on that board, from rank 8 down to rank 1, " +
            "using real board coordinates (use the a-h / 1-8 labels if visible; if the board is shown flipped, " +
            "still report real ranks and files). Uppercase = White, lowercase = Black, digits = empty squares. " +
            "Do not explain. Your final line must be exactly: FEN: <placement>. " +
            "If no chess board is visible, your final line must be exactly: FEN: NONE"

        private val NONE_REGEX = Regex("""FEN:\s*NONE""", RegexOption.IGNORE_CASE)

        private val FEN_REGEX = Regex("""([1-8pnbrqkPNBRQK]+/){7}[1-8pnbrqkPNBRQK]+""")

        /** Inverse of [parsePlacement]: piece map to the first FEN field. */
        fun placement(m: Map<Square, Piece>): String =
            (7 downTo 0).joinToString("/") { rank ->
                val sb = StringBuilder()
                var empty = 0
                for (file in 0..7) {
                    val p = m[Square.squareAt(rank * 8 + file)]
                    if (p == null || p == Piece.NONE) { empty++ } else {
                        if (empty > 0) { sb.append(empty); empty = 0 }
                        sb.append(p.fenSymbol)
                    }
                }
                if (empty > 0) sb.append(empty)
                sb.toString()
            }

        /** Parses "rnbqkbnr/pppppppp/8/..." (first FEN field) into squares. Throws if malformed. */
        fun parsePlacement(reply: String): Map<Square, Piece> {
            // Use the LAST match: thinking models may mention other placements while reasoning.
            val match = FEN_REGEX.findAll(reply).lastOrNull()
                ?: error("No FEN in reply (…${reply.takeLast(80)})")
            val ranks = match.value.split("/")
            val out = HashMap<Square, Piece>()
            for ((i, rankText) in ranks.withIndex()) {
                val rank = 7 - i // first block is rank 8
                var file = 0
                for (ch in rankText) {
                    if (ch.isDigit()) { file += ch - '0'; continue }
                    if (file > 7) error("Rank too long: $rankText")
                    out[Square.squareAt(rank * 8 + file)] = pieceFor(ch)
                    file++
                }
                if (file != 8) error("Rank does not add up to 8: $rankText")
            }
            return out
        }

        private fun pieceFor(c: Char): Piece = when (c) {
            'P' -> Piece.WHITE_PAWN; 'N' -> Piece.WHITE_KNIGHT; 'B' -> Piece.WHITE_BISHOP
            'R' -> Piece.WHITE_ROOK; 'Q' -> Piece.WHITE_QUEEN; 'K' -> Piece.WHITE_KING
            'p' -> Piece.BLACK_PAWN; 'n' -> Piece.BLACK_KNIGHT; 'b' -> Piece.BLACK_BISHOP
            'r' -> Piece.BLACK_ROOK; 'q' -> Piece.BLACK_QUEEN; 'k' -> Piece.BLACK_KING
            else -> error("Bad piece $c")
        }
    }
}

private fun Bitmap.rotate180(): Bitmap =
    Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(180f) }, true)
