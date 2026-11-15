package com.example.chessassist

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class UiState(
    val status: String = "Pick your colour, line the board up in the square, then press Start.",
    val running: Boolean = false,
    val suggestion: String? = null,          // spoken-style text, e.g. "Knight to f3"
    val suggestionUci: String? = null,       // e.g. "g1f3", for the arrow
    val fen: String = "",
    val moves: List<String> = emptyList(),
    val playingWhite: Boolean = true,
)

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var recognizer: BoardRecognizer? = null
    private var engine: UciEngine? = null
    private var tracker: MoveTracker? = null
    private val speaker = Speaker(app)
    private val busy = AtomicBoolean(false)

    fun setColor(white: Boolean) { if (!_state.value.running) _state.value = _state.value.copy(playingWhite = white) }

    fun start() {
        if (_state.value.running) return
        _state.value = _state.value.copy(status = "Loading model and engine…", running = true, suggestion = null, suggestionUci = null, moves = emptyList())
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    recognizer = TfliteBoardRecognizer(getApplication())
                    engine = UciEngine(getApplication()).also { it.start() }
                }
                tracker = MoveTracker()
                _state.value = _state.value.copy(status = "Looking for the starting position…", fen = tracker!!.board.fen)
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, status = "Couldn't start: ${e.message}")
            }
        }
    }

    fun stop() {
        engine?.close(); engine = null
        tracker = null
        _state.value = _state.value.copy(running = false, status = "Stopped.")
    }

    /** Called from the camera analyzer (~1 per second) with a square crop of the board. */
    fun onFrame(boardCrop: Bitmap) {
        val rec = recognizer ?: return
        val trk = tracker ?: return
        if (!_state.value.running || !busy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val seen = rec.recognize(boardCrop, flipped = !_state.value.playingWhite)
                handle(trk, trk.update(seen))
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "Error: ${e.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun handle(trk: MoveTracker, result: MoveTracker.Result) {
        val me = if (_state.value.playingWhite) Side.WHITE else Side.BLACK
        when (result) {
            MoveTracker.Result.Nothing -> Unit
            MoveTracker.Result.BoardConfirmed -> {
                val c = if (me == Side.WHITE) "white" else "black"
                speaker.say("Ready. You're $c.")
                _state.value = _state.value.copy(status = "Ready. You're $c.")
                if (trk.board.sideToMove == me) suggest(trk)
            }
            is MoveTracker.Result.MovePlayed -> {
                val san = result.move.toString()
                _state.value = _state.value.copy(
                    moves = _state.value.moves + san,
                    fen = trk.board.fen,
                    suggestion = null, suggestionUci = null,
                    status = if (result.mover == me) "You played $san." else "Opponent played $san.",
                )
                when {
                    trk.board.isMated -> finish("Checkmate.")
                    trk.board.isDraw -> finish("Draw.")
                    trk.board.sideToMove == me -> suggest(trk)
                }
            }
        }
    }

    private suspend fun suggest(trk: MoveTracker) {
        val uci = engine?.bestMove(trk.board.fen) ?: return
        val move = trk.board.legalMoves().firstOrNull { it.toString().equals(uci, ignoreCase = true) } ?: return
        val text = MovePhrase.describe(trk.board, move)
        speaker.say(text)
        _state.value = _state.value.copy(suggestion = text, suggestionUci = uci, status = "Your move.")
    }

    private fun finish(msg: String) {
        speaker.say(msg)
        _state.value = _state.value.copy(status = msg, running = false)
    }

    override fun onCleared() {
        engine?.close(); speaker.shutdown()
    }
}
