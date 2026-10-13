package me.rerere.rikkahub.data.ai.tools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private const val TERMUX_PACKAGE = "com.termux"
private const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr/bin"

private const val MAX_TERMUX_COMMAND_TIMEOUT_SECONDS = 24 * 60 * 60L
private val requestCounter = AtomicInteger()

/** A safe-ish, bounded client for Termux's documented RUN_COMMAND service. */
class TermuxCommandRunner(
    private val context: Context,
    private val defaultTimeoutSeconds: Long,
    private val callTimeoutMinutes: Long,
    private val maxOutputLines: Int,
    private val maxOutputBytes: Int,
) {
    suspend fun run(command: String, ubuntu: Boolean): JsonObject {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return errorJson("invalid_command", "command must not be empty")

        val packageManager = context.packageManager
        val termuxInstalled = runCatching {
            packageManager.getApplicationInfo(TERMUX_PACKAGE, PackageManager.GET_META_DATA)
        }.isSuccess
        if (!termuxInstalled) return errorJson("termux_not_installed", "Termux is not installed")
        if (context.checkPermission(RUN_COMMAND_PERMISSION, android.os.Process.myPid(), android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
            return errorJson("permission_denied", "RUN_COMMAND permission is not granted")
        }

        val requestId = "${context.packageName}-${requestCounter.incrementAndGet()}-${UUID.randomUUID()}"
        val deferred = CompletableDeferred<TermuxCommandResult>()
        TermuxCommandResultReceiver.register(requestId, deferred)
        return try {
            val callback = Intent(context, TermuxCommandResultReceiver::class.java).apply {
                action = "${context.packageName}.TERMUX_RESULT"
                putExtra(EXTRA_REQUEST_ID, requestId)
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCounter.get(),
                callback,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val timeoutSeconds = defaultTimeoutSeconds.coerceIn(1, MAX_TERMUX_COMMAND_TIMEOUT_SECONDS).toInt()
            val commandPath = if (ubuntu) "$TERMUX_PREFIX/proot-distro" else "$TERMUX_PREFIX/timeout"
            val commandArguments = if (ubuntu) {
                arrayListOf(
                    "login", "ubuntu", "--", "bash", "-lc",
                    "timeout ${timeoutSeconds}s bash -lc ${quoteForShell(trimmed)}",
                )
            } else {
                arrayListOf(
                    "-k", "5s", "${timeoutSeconds}s", "$TERMUX_PREFIX/bash", "-lc", trimmed,
                )
            }
            val serviceIntent = Intent().apply {
                component = android.content.ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                action = RUN_COMMAND_ACTION
                putExtra(EXTRA_PATH, commandPath)
                putExtra(EXTRA_ARGUMENTS, commandArguments)
                putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }
            try {
                context.startService(serviceIntent)
            } catch (security: SecurityException) {
                return errorJson("permission_denied", security.message ?: "Termux rejected RUN_COMMAND")
            } catch (missing: RuntimeException) {
                return errorJson("termux_unavailable", missing.message ?: "Termux RUN_COMMAND service is unavailable")
            }

            val result = try {
                withTimeout(callTimeoutMinutes.coerceIn(1, 24 * 60) * 60_000L) { deferred.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                return errorJson("timeout", "Termux command timed out")
            }
            boundedResult(result)
        } finally {
            TermuxCommandResultReceiver.remove(requestId)
        }
    }

    private fun boundedResult(result: TermuxCommandResult): JsonObject {
        val stdout = boundOutput(result.stdout)
        val stderr = boundOutput(result.stderr)
        return buildJsonObject {
            put("stdout", stdout.text)
            put("stderr", stderr.text)
            put("exitCode", result.exitCode)
            if (stdout.truncated) put("stdout_truncated", true)
            if (stderr.truncated) put("stderr_truncated", true)
            if (stdout.truncated || stderr.truncated) put("truncated", true)
        }
    }

    private fun boundOutput(value: String): BoundedOutput {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val byteLimited = if (bytes.size > maxOutputBytes) String(bytes, 0, maxOutputBytes.coerceAtLeast(0), Charsets.UTF_8) else value
        val lines = byteLimited.split('\n')
        val lineLimited = if (lines.size > maxOutputLines) lines.take(maxOutputLines.coerceAtLeast(0)).joinToString("\n") else byteLimited
        return BoundedOutput(lineLimited, bytes.size > maxOutputBytes || lines.size > maxOutputLines)
    }

    private data class BoundedOutput(val text: String, val truncated: Boolean)

    companion object {
        internal const val EXTRA_REQUEST_ID = "me.rerere.rikkahub.TERMUX_REQUEST_ID"
        internal const val EXTRA_RESULT_BUNDLE = "result"
    }
}

fun createTermuxTools(
    context: Context,
    defaultTimeoutSeconds: Long,
    callTimeoutMinutes: Long,
    maxOutputLines: Int,
    maxOutputBytes: Int,
): List<Tool> {
    val runner = TermuxCommandRunner(
        context = context,
        defaultTimeoutSeconds = defaultTimeoutSeconds,
        callTimeoutMinutes = callTimeoutMinutes,
        maxOutputLines = maxOutputLines,
        maxOutputBytes = maxOutputBytes,
    )
    fun tool(name: String, ubuntu: Boolean) = Tool(
        name = name,
        description = if (ubuntu) {
            "Run a user-approved command inside Ubuntu via Termux proot-distro and return bounded stdout, stderr, and exitCode."
        } else {
            "Run a user-approved bash command in Termux and return bounded stdout, stderr, and exitCode."
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject { put("type", "string"); put("description", "Command text") })
                },
                required = listOf("command"),
            )
        },
        requiresUserApproval = true,
        execute = { args ->
            val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
            if (command == null) errorJson("invalid_command", "command is required")
            else runner.run(command, ubuntu)
        },
    )
    return listOf(tool("termux_run_command", false), tool("termux_run_ubuntu", true))
}

private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", code)
    put("message", message)
}

private fun quoteForShell(value: String): String = "'${value.replace("'", "'\\''")}'"
