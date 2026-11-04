package app.claudroid.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-memory + on-disk log ring used by the engine bootstrap screen.
 * The engine runs child processes, so plain logcat would lose the interesting part.
 */
object Logx {
    private const val MAX_LINES = 600
    private const val TAG = "Claudroid"
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun i(msg: String, ctx: Context? = null) {
        Log.i(TAG, msg)
        append(LineType.Info, msg, ctx)
    }

    @Synchronized
    fun e(msg: String, ctx: Context? = null) {
        Log.e(TAG, msg)
        append(LineType.Error, msg, ctx)
    }

    private fun append(type: LineType, msg: String, ctx: Context?) {
        val prefix = when (type) {
            LineType.Info -> "· "
            LineType.Error -> "✕ "
        }
        val line = "${stamp.format(Date())} $prefix$msg"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        if (ctx != null) {
            runCatching {
                ctx.prepareLogFile().appendText(line + "\n")
            }
        }
    }

    private fun Context.prepareLogFile(): File = Paths.engineLog(this).apply {
        if (length() > 512 * 1024) writeText("") // crude rotation
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private enum class LineType { Info, Error }
}
