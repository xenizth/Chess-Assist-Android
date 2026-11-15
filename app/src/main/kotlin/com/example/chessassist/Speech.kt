package com.example.chessassist

import android.content.Context
import android.speech.tts.TextToSpeech
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.move.Move
import java.util.Locale

/** Builds the sentence for a move, e.g. "Knight to f3", "Bishop takes e5", "Castle kingside". */
object MovePhrase {

    private fun name(t: PieceType?): String = when (t) {
        PieceType.PAWN -> "Pawn"; PieceType.KNIGHT -> "Knight"; PieceType.BISHOP -> "Bishop"
        PieceType.ROOK -> "Rook"; PieceType.QUEEN -> "Queen"; PieceType.KING -> "King"
        else -> "Piece"
    }

    /** TTS reads a lone "a" as "uh", so spell the a-file as "ay". */
    private fun sq(s: String): String = if (s.startsWith("a")) "ay" + s.substring(1) else s

    /** Call with the board *before* the move is played. */
    fun describe(board: Board, move: Move): String {
        val piece = board.getPiece(move.from)
        val type = piece.pieceType
        val from = move.from.value().lowercase()
        val to = move.to.value().lowercase()

        val text: String
        if (type == PieceType.KING && kotlin.math.abs(from[0] - to[0]) == 2) {
            text = if (to[0] > from[0]) "Castle kingside" else "Castle queenside"
        } else {
            val capture = board.getPiece(move.to) != Piece.NONE ||
                (type == PieceType.PAWN && from[0] != to[0]) // en passant
            val promo = if (move.promotion != Piece.NONE)
                ", promote to ${name(move.promotion.pieceType).lowercase()}" else ""
            text = "${name(type)} ${if (capture) "takes" else "to"} ${sq(to)}$promo"
        }

        board.doMove(move)
        val suffix = when {
            board.isMated -> ", checkmate"
            board.isKingAttacked -> ", check"
            else -> ""
        }
        board.undoMove()
        return text + suffix
    }
}

class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) { tts.language = Locale.US; ready = true }
    }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "move")
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
