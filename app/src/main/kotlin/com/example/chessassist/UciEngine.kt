package com.example.chessassist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Talks UCI to a Stockfish binary shipped as `jniLibs/arm64-v8a/libstockfish.so`.
 * Android extracts it into nativeLibraryDir, which is a location apps may execute from.
 */
class UciEngine(private val context: Context) : AutoCloseable {

    private var proc: Process? = null
    private var out: OutputStreamWriter? = null
    private var inp: BufferedReader? = null
    private val lock = Mutex()

    suspend fun start() = withContext(Dispatchers.IO) {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        require(bin.exists()) { "libstockfish.so missing: put an arm64 Stockfish build in app/src/main/jniLibs/arm64-v8a/" }
        val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
        proc = p
        out = OutputStreamWriter(p.outputStream)
        inp = BufferedReader(InputStreamReader(p.inputStream))
        send("uci"); waitFor("uciok")
        send("isready"); waitFor("readyok")
    }

    /** Best move in UCI notation ("e2e4", "e7e8q"), or null if the game is over. */
    suspend fun bestMove(fen: String, moveTimeMs: Int = 1000): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            send("position fen $fen")
            send("go movetime $moveTimeMs")
            val line = waitFor("bestmove")
            val mv = line.split(" ").getOrNull(1)
            if (mv == null || mv == "(none)" || mv == "0000") null else mv
        }
    }

    private fun send(cmd: String) { out!!.write(cmd + "\n"); out!!.flush() }

    private fun waitFor(prefix: String): String {
        while (true) {
            val line = inp!!.readLine() ?: error("Stockfish exited")
            if (line.startsWith(prefix)) return line
        }
    }

    override fun close() {
        try { send("quit") } catch (_: Exception) {}
        proc?.destroy()
    }
}
