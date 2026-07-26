package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.os.UserManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Rotating file logger used by background work and the Shizuku UserService. */
object AppLog {
    private const val TAG_PREFIX = "PowerKeeperFix"
    private const val MAX_FILES = 20
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_VIEW_BYTES = 200_000

    private val lineTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    @Volatile
    private var logThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "powerkeeper-app-log").apply {
            isDaemon = true
            logThread = this
        }
    }

    private var logsDirectory: File? = null
    private var currentFile: File? = null
    private var shizukuWritableDirectory = false

    /** Queues filesystem initialization without delaying Application.onCreate. */
    fun init(context: Context) {
        val applicationContext = context.applicationContext
        executor.execute {
            if (logsDirectory != null) return@execute
            val externalDirectory = if (applicationContext.getSystemService(UserManager::class.java).isSystemUser) {
                applicationContext.getExternalFilesDir(null)?.let { File(it, "logs") }
            } else {
                null
            }
            logsDirectory = externalDirectory
                ?.takeIf(::prepareWritableDirectory)
                ?: File(applicationContext.noBackupFilesDir, "logs").apply { mkdirs() }
            shizukuWritableDirectory = logsDirectory == externalDirectory
            startSessionLocked("process-start")
        }
    }

    fun i(tag: String, message: String) = log('I', tag, message)
    fun w(tag: String, message: String) = log('W', tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e("$TAG_PREFIX/$tag", message)
        } else {
            Log.e("$TAG_PREFIX/$tag", message, throwable)
        }
        executor.execute {
            val detail = throwable?.let { "\n${Log.getStackTraceString(it)}" }.orEmpty()
            writeLocked("${lineTimeFormat.format(Date())} E/APP/$tag: $message$detail")
        }
    }

    private fun log(level: Char, tag: String, message: String) {
        when (level) {
            'E' -> Log.e("$TAG_PREFIX/$tag", message)
            'W' -> Log.w("$TAG_PREFIX/$tag", message)
            else -> Log.i("$TAG_PREFIX/$tag", message)
        }
        executor.execute {
            writeLocked("${lineTimeFormat.format(Date())} $level/APP/$tag: $message")
        }
    }

    /** Returns an owner-user external-storage path that Shizuku's shell domain can append to. */
    fun shizukuLogPath(): String? = runCatching {
        onLogThread {
            if (!shizukuWritableDirectory) return@onLogThread null
            val file = currentFile ?: return@onLogThread null
            if (file.length() >= MAX_FILE_BYTES) startSessionLocked("rollover")
            currentFile?.absolutePath
        }
    }.getOrNull()

    fun sessionFiles(): List<File> = runCatching {
        onLogThread {
            logsDirectory
                ?.listFiles { file -> file.name.startsWith("session-") && file.extension == "log" }
                ?.sortedByDescending(File::getName)
                ?: emptyList()
        }
    }.getOrDefault(emptyList())

    fun readSessionFile(file: File): String = runCatching {
        onLogThread {
            val safeFile = resolveSessionFileLocked(file) ?: return@onLogThread "(invalid log file)"
            runCatching {
                val bytes = safeFile.readBytes()
                bytes.takeLast(MAX_VIEW_BYTES).toByteArray().toString(Charsets.UTF_8)
            }.getOrElse { "(could not read ${safeFile.name}: ${it.message})" }
        }
    }.getOrElse { "(could not read ${file.name}: ${it.message})" }

    /** Clears all prior logs, creates a new session, and returns the number removed. */
    fun clearSessionFiles(): Int = runCatching {
        onLogThread {
            val files = logsDirectory
                ?.listFiles { file -> file.name.startsWith("session-") && file.extension == "log" }
                .orEmpty()
            val deleted = files.count { runCatching { it.delete() }.getOrDefault(false) }
            currentFile = null
            startSessionLocked("logs-cleared")
            deleted
        }
    }.getOrDefault(0)

    private fun <T> onLogThread(block: () -> T): T {
        if (Thread.currentThread() === logThread) return block()
        return executor.submit<T> { block() }.get()
    }

    private fun startSessionLocked(reason: String) {
        val directory = logsDirectory ?: return
        currentFile = File(directory, "session-${fileTimeFormat.format(Date())}.log")
        writeLocked("=== session start: $reason ===")
        pruneLocked(directory)
    }

    private fun writeLocked(line: String) {
        var file = currentFile ?: return
        if (file.length() >= MAX_FILE_BYTES) {
            startSessionLocked("rollover")
            file = currentFile ?: return
        }
        runCatching { file.appendText(line + "\n") }
            .onFailure { Log.w(TAG_PREFIX, "Could not append app log", it) }
    }

    private fun pruneLocked(directory: File) {
        val files = directory
            .listFiles { file -> file.name.startsWith("session-") && file.extension == "log" }
            ?.sortedBy(File::getName)
            ?: return
        if (files.size > MAX_FILES) {
            files.take(files.size - MAX_FILES).forEach { runCatching { it.delete() } }
        }
    }

    private fun prepareWritableDirectory(directory: File): Boolean = runCatching {
        if (!directory.isDirectory && !directory.mkdirs()) return@runCatching false
        val probe = File(directory, ".app-write-probe-${android.os.Process.myPid()}")
        probe.writeText("")
        probe.delete()
    }.getOrDefault(false)

    private fun resolveSessionFileLocked(file: File): File? {
        val directory = logsDirectory?.canonicalFile ?: return null
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (candidate.parentFile?.canonicalFile != directory) return null
        if (!candidate.name.startsWith("session-") || candidate.extension != "log") return null
        return candidate
    }
}
