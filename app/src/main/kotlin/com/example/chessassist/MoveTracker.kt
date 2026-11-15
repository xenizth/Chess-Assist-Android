package com.example.chessassist

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/**
 * Keeps the game state and decides, from noisy per-frame detections, which legal move was played.
 * The recognizer reports what it sees; this class only ever commits a *legal* move, and only after
 * several consecutive frames agree on it.
 */
class MoveTracker(
    startFen: String? = null,
    assumeReady: Boolean = false,                 // true: skip waiting for the starting position
    private val confirmFrames: Int = CONFIRM_FRAMES,
) {

    val board = Board().also { if (startFen != null) it.loadFromFen(startFen) }

    private val squares = Square.values().filter { it != Square.NONE }

    private var pending: Move? = null
    private var pendingCount = 0
    private var readyCount = 0
    var ready = assumeReady
        private set

    sealed interface Result {
        data object Nothing : Result
        data object BoardConfirmed : Result
        data class MovePlayed(val move: Move, val mover: Side) : Result
    }

    /** How many squares differ between the detection and a position (empty squares count too). */
    private fun mismatches(seen: Map<Square, Piece>, b: Board): Int =
        squares.count { (seen[it] ?: Piece.NONE) != b.getPiece(it) }

    fun update(seen: Map<Square, Piece>): Result {
        if (!ready) {
            // Wait until the board looks like the expected starting position.
            if (mismatches(seen, board) <= SETUP_TOLERANCE) readyCount++ else readyCount = 0
            if (readyCount >= confirmFrames) { ready = true; return Result.BoardConfirmed }
            return Result.Nothing
        }

        val current = mismatches(seen, board)
        if (current <= STILL_TOLERANCE) { pending = null; pendingCount = 0; return Result.Nothing }

        // Score every legal move by how well the resulting position explains the frame.
        var best: Move? = null; var bestScore = Int.MAX_VALUE; var second = Int.MAX_VALUE
        for (m in board.legalMoves()) {
            board.doMove(m)
            val s = mismatches(seen, board)
            board.undoMove()
            if (s < bestScore) { second = bestScore; bestScore = s; best = m }
            else if (s < second) second = s
        }
        if (best == null || bestScore > MOVE_TOLERANCE || bestScore >= second) {
            pending = null; pendingCount = 0
            return Result.Nothing
        }
        if (best == pending) pendingCount++ else { pending = best; pendingCount = 1 }
        if (pendingCount < confirmFrames) return Result.Nothing

        val mover = board.sideToMove
        board.doMove(best)
        pending = null; pendingCount = 0
        return Result.MovePlayed(best, mover)
    }

    companion object {
        const val CONFIRM_FRAMES = 3
        const val SETUP_TOLERANCE = 2
        const val STILL_TOLERANCE = 1   // frame already matches current position closely enough
        const val MOVE_TOLERANCE = 3
    }
}
