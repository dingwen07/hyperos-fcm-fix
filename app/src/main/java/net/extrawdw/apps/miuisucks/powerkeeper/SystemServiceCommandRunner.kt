package net.extrawdw.apps.miuisucks.powerkeeper

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import rikka.shizuku.SystemServiceHelper

/** Executes Android system-service shell and dump entry points without starting child processes. */
internal class SystemServiceCommandRunner {
    private val invocationExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "system-binder-command").apply { isDaemon = true }
    }

    fun run(
        command: SystemServiceCommand,
        timeoutSeconds: Long,
        maxOutputLength: Int,
    ): Result {
        val binder = resolveService(command.serviceName)
            ?: return Result.failure("Binder service ${command.serviceName} is unavailable")
        val exitCode = AtomicInteger(UNKNOWN_EXIT_CODE)
        val resultReceived = CountDownLatch(1)
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                exitCode.set(resultCode)
                resultReceived.countDown()
            }
        }
        // BinderProxy marshals stdin before Binder.onShellCommand can replace null with /dev/null.
        // Keep a real descriptor open until the remote shell command reports completion.
        val input = runCatching { FileInputStream(NULL_DEVICE) }
            .getOrElse { return Result.failure(it.message ?: it.javaClass.simpleName) }
        return input.use { inputStream ->
            runWithOutput(timeoutSeconds, maxOutputLength) { output ->
                shellCommandMethod.invoke(
                    binder,
                    inputStream.fd,
                    output,
                    output,
                    command.arguments.toTypedArray(),
                    null,
                    receiver,
                )
                if (!resultReceived.await(RESULT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    throw IllegalStateException("Binder shell command returned without a result code")
                }
                exitCode.get()
            }
        }
    }

    fun dump(
        serviceName: String,
        arguments: List<String>,
        timeoutSeconds: Long,
        maxOutputLength: Int,
    ): Result {
        val binder = resolveService(serviceName)
            ?: return Result.failure("Binder service $serviceName is unavailable")
        return runWithOutput(timeoutSeconds, maxOutputLength) { output ->
            binder.dump(output, arguments.toTypedArray())
            0
        }
    }

    fun close() {
        invocationExecutor.shutdownNow()
    }

    private fun resolveService(serviceName: String): IBinder? {
        val token = Binder.clearCallingIdentity()
        return try {
            SystemServiceHelper.getSystemService(serviceName)?.takeIf(IBinder::isBinderAlive)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun runWithOutput(
        timeoutSeconds: Long,
        maxOutputLength: Int,
        invocation: (FileDescriptor) -> Int,
    ): Result {
        require(timeoutSeconds > 0) { "Timeout must be positive" }
        require(maxOutputLength >= 0) { "Maximum output length is negative" }
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }
            .getOrElse { return Result.failure(it.message ?: it.javaClass.simpleName) }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val output = AtomicReference("")
        val readerFailure = AtomicReference<Throwable?>(null)
        val reader = thread(name = "system-binder-output", isDaemon = true) {
            val captured = ByteArrayOutputStream(minOf(maxOutputLength, DEFAULT_BUFFER_SIZE))
            try {
                FileInputStream(readEnd.fileDescriptor).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val remaining = maxOutputLength - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
                    }
                }
            } catch (error: Throwable) {
                readerFailure.set(error)
            } finally {
                output.set(captured.toString(Charsets.UTF_8.name()).trim())
            }
        }
        val future = invocationExecutor.submit<Int> { invocation(writeEnd.fileDescriptor) }
        var completed = true
        var exitCode = UNKNOWN_EXIT_CODE
        var failure: Throwable? = null
        try {
            exitCode = future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            completed = false
            future.cancel(true)
        } catch (error: ExecutionException) {
            failure = unwrap(error.cause ?: error)
        } catch (error: Throwable) {
            failure = unwrap(error)
        } finally {
            runCatching { writeEnd.close() }
            reader.join(READER_JOIN_MILLIS)
            runCatching { readEnd.close() }
        }
        failure = failure ?: readerFailure.get()
        val captured = output.get()
        val failureMessage = failure?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
        return Result(
            completed = completed,
            exitCode = if (failure == null && completed) exitCode else UNKNOWN_EXIT_CODE,
            output = listOf(captured, failureMessage).filter(String::isNotBlank).joinToString("\n"),
        )
    }

    private fun unwrap(error: Throwable): Throwable =
        when (error) {
            is InvocationTargetException -> error.targetException ?: error
            else -> error
        }

    data class Result(
        val completed: Boolean,
        val exitCode: Int,
        val output: String,
    ) {
        val succeeded: Boolean
            get() = completed && exitCode == 0

        val summary: String
            get() = when {
                !completed -> "timed out"
                output.isNotBlank() -> "exit $exitCode: ${output.take(240)}"
                else -> "exit $exitCode"
            }

        companion object {
            fun failure(message: String) = Result(completed = true, exitCode = UNKNOWN_EXIT_CODE, output = message)
        }
    }

    companion object {
        private val shellCommandMethod by lazy {
            IBinder::class.java.getMethod(
                "shellCommand",
                FileDescriptor::class.java,
                FileDescriptor::class.java,
                FileDescriptor::class.java,
                Array<String>::class.java,
                Class.forName("android.os.ShellCallback"),
                ResultReceiver::class.java,
            )
        }
        private const val UNKNOWN_EXIT_CODE = -1
        private const val RESULT_GRACE_MILLIS = 5_000L
        private const val READER_JOIN_MILLIS = 2_000L
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024
        private const val NULL_DEVICE = "/dev/null"
    }
}
