package me.rerere.rikkahub.data.ai.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.util.UUID

// ---------------------------------------------------------------------------
// Termux RUN_COMMAND integration.
//
// Core implementation ported from RikkaHub Agent Pure
// (wuyhong715/rikkahub-agent-pure, TermuxTool.kt / TermuxSessionTool.kt),
// adapted to FLIT's Tool surface (execute returns JsonElement).
//
// Battle-tested details (do NOT "simplify" these away):
//  1. Termux fires the result PendingIntent TWICE: an immediate start-ACK
//     (err=-1, exitCode=0, empty stdout — byte-identical to a successful empty
//     run) and the real completion. A per-call marker printed after the command
//     exits distinguishes them (Pure issue #83).
//  2. err=-1 (Activity.RESULT_OK) is Termux's success sentinel — NOT 0.
//  3. Results arrive via BroadcastReceiver with FLAG_UPDATE_CURRENT|FLAG_MUTABLE.
//     FLAG_ONE_SHOT consumes the ACK and loses the real result.
//  4. Some Termux variants put result keys flat on the intent extras instead of
//     nested under "result" — both shapes are supported.
//  5. Sessions are tmux-backed (real pty); tmux auto-installs on first use.
//
// Permission model (Shuanyin two-level, see spec 6.1):
//   termuxFullAuth ("تفويض كامل") = true  -> tools run WITHOUT approval.
//   termuxFullAuth = false                 -> every call shows an approval card.
// A per-assistant toggle (LocalToolOption.TermuxConnector) gates visibility,
// and the integration master switch (termuxEnabled) gates registration.
// ---------------------------------------------------------------------------

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"

private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val EXTRA_RESULT_BUNDLE = "result"
private const val RESULT_KEY_STDOUT = "stdout"
private const val RESULT_KEY_STDERR = "stderr"
private const val RESULT_KEY_EXIT_CODE = "exitCode"
private const val RESULT_KEY_ERR = "err"
private const val RESULT_KEY_ERRMSG = "errmsg"

private const val TERMUX_MAX_OUTPUT_CHARS = 30_000
private const val TMUX_OP_TIMEOUT_MS = 8_000L
private const val TMUX_INSTALL_TIMEOUT_MS = 180_000L
private const val MAX_SESSIONS = 8
private const val SESSION_TTL_MS = 6L * 60 * 60 * 1000
private const val SESSION_DEFAULT_TIMEOUT_S = 20
private const val SESSION_MAX_TIMEOUT_S = 600

/** Termux installation + integration state probe. */
internal object TermuxIntegration {
    enum class State { NOT_INSTALLED, NO_PERMISSION, READY }

    fun state(ctx: Context): State {
        val installed = try {
            ctx.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
        } catch (_: Throwable) { false }
        if (!installed) return State.NOT_INSTALLED
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, "com.termux.permission.RUN_COMMAND"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return State.NO_PERMISSION
        return State.READY
    }

    /** End-to-end smoke test: proves manifest perm + runtime perm + allow-external-apps. */
    suspend fun verify(ctx: Context, timeoutMs: Long = 15_000L): VerifyResult {
        when (state(ctx)) {
            State.NOT_INSTALLED -> return VerifyResult.NotInstalled
            State.NO_PERMISSION -> return VerifyResult.NoPermission
            State.READY -> Unit
        }
        val result = runCommandCapture(
            ctx = ctx,
            executable = "$TERMUX_BIN/echo",
            arguments = arrayOf("SHUANYIN_OK"),
            workingDir = TERMUX_HOME,
            timeoutMs = timeoutMs,
        )
        return when (result) {
            is CaptureResult.Success ->
                if (result.stdout.contains("SHUANYIN_OK")) VerifyResult.Ok
                else VerifyResult.UnexpectedOutput(result.stdout)
            is CaptureResult.Timeout -> VerifyResult.AllowExternalAppsMissing
            is CaptureResult.Denied -> VerifyResult.NoPermission
            is CaptureResult.OtherError -> VerifyResult.OtherError(result.message)
        }
    }

    sealed interface VerifyResult {
        data object NotInstalled : VerifyResult
        data object NoPermission : VerifyResult
        data object AllowExternalAppsMissing : VerifyResult
        data object Ok : VerifyResult
        data class UnexpectedOutput(val stdout: String) : VerifyResult
        data class OtherError(val message: String) : VerifyResult
    }
}

internal sealed interface CaptureResult {
    data class Success(val stdout: String, val stderr: String, val exitCode: Int) : CaptureResult
    data object Timeout : CaptureResult
    data object Denied : CaptureResult
    data class OtherError(val message: String) : CaptureResult
}

/**
 * Wrap [executable]/[arguments] so the real command runs through `bash -c` and prints a
 * per-call [marker] right after it exits, whatever its exit status. Arguments stay literal
 * argv entries, never interpolated into the script text.
 */
internal fun buildMarkerWrappedArgv(
    executable: String,
    arguments: Array<String>,
    marker: String,
): Pair<String, Array<String>> {
    val script = "\"\$0\" \"\$@\"; ec=\$?; printf '\\n%s' '$marker'; exit \$ec"
    return "$TERMUX_BIN/bash" to arrayOf("-c", script, executable, *arguments)
}

/**
 * Whether a Termux result bundle is the real completion rather than the early start-ACK
 * (err=-1, exitCode=0, empty output). Terminal when err != -1, exitCode != 0, or the
 * marker is present at the end of stdout.
 */
internal fun isTerminalResult(err: Int, exitCode: Int, stdout: String, marker: String): Boolean {
    if (err != -1 || exitCode != 0) return true
    return stdout.trimEnd().endsWith(marker)
}

/** Remove the trailing "\n" + marker appended by the wrapper script. */
internal fun stripTerminationMarker(stdout: String, marker: String): String {
    val trimmed = stdout.trimEnd()
    val suffix = "\n$marker"
    return if (trimmed.endsWith(suffix)) trimmed.removeSuffix(suffix) else stdout
}

/**
 * Dispatch a Termux command and suspend until it completes (or times out).
 * Registers a one-shot BroadcastReceiver, hands Termux a PendingIntent for it,
 * and waits on a CompletableDeferred. Always background mode: the result-bundle
 * delivery path only fires for background commands.
 */
internal suspend fun runCommandCapture(
    ctx: Context,
    executable: String,
    arguments: Array<String>,
    workingDir: String,
    timeoutMs: Long,
): CaptureResult = withContext(Dispatchers.IO) {
    val resultDeferred = CompletableDeferred<Bundle>()
    val resultAction = "${ctx.packageName}.TERMUX_RESULT_${UUID.randomUUID()}"
    val marker = UUID.randomUUID().toString()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            if (bundle == null && intent.extras == null) return // empty start-ACK, keep waiting
            val effective = bundle ?: intent.extras ?: Bundle()
            if (!isTerminalResult(
                    err = effective.getInt(RESULT_KEY_ERR, -1),
                    exitCode = effective.getInt(RESULT_KEY_EXIT_CODE, -1),
                    stdout = effective.getString(RESULT_KEY_STDOUT).orEmpty(),
                    marker = marker,
                )
            ) return // non-terminal ACK, keep waiting
            if (resultDeferred.isActive) resultDeferred.complete(effective)
        }
    }
    val filter = IntentFilter(resultAction)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        ctx.registerReceiver(receiver, filter)
    }

    val pi = try {
        val resultIntent = Intent(resultAction).setPackage(ctx.packageName)
        PendingIntent.getBroadcast(
            ctx,
            resultAction.hashCode(),
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    } catch (t: Throwable) {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        return@withContext CaptureResult.OtherError("PendingIntent creation failed: ${t.message}")
    }

    val (wrappedExecutable, wrappedArguments) = buildMarkerWrappedArgv(executable, arguments, marker)
    val intent = Intent().apply {
        setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
        action = TERMUX_RUN_COMMAND_ACTION
        putExtra("com.termux.RUN_COMMAND_PATH", wrappedExecutable)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", wrappedArguments)
        putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        putExtra(EXTRA_PENDING_INTENT, pi)
    }

    try {
        ctx.startService(intent)
        val bundle = withTimeoutOrNull(timeoutMs) { resultDeferred.await() }
        if (bundle == null) {
            CaptureResult.Timeout
        } else {
            // err = -1 (Activity.RESULT_OK) is Termux's success sentinel.
            val errCode = bundle.getInt(RESULT_KEY_ERR, -1)
            if (errCode != -1) {
                val errMsg = bundle.getString(RESULT_KEY_ERRMSG).orEmpty()
                if (errMsg.contains("PermissionDenied", ignoreCase = true) ||
                    errMsg.contains("not allowed", ignoreCase = true)
                ) {
                    CaptureResult.Denied
                } else {
                    CaptureResult.OtherError("err=$errCode: $errMsg")
                }
            } else {
                CaptureResult.Success(
                    stdout = stripTerminationMarker(bundle.getString(RESULT_KEY_STDOUT).orEmpty(), marker),
                    stderr = bundle.getString(RESULT_KEY_STDERR).orEmpty(),
                    exitCode = bundle.getInt(RESULT_KEY_EXIT_CODE, -1),
                )
            }
        }
    } catch (t: SecurityException) {
        CaptureResult.Denied
    } catch (t: Throwable) {
        CaptureResult.OtherError(t.message ?: t::class.java.simpleName)
    } finally {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        try { pi.cancel() } catch (_: Throwable) {}
    }
}

// ---------------------------------------------------------------------------
// tmux-backed sessions (real pty). tmux auto-installs on first use.
// ---------------------------------------------------------------------------

internal object TmuxOps {
    fun sessionName(userName: String?): String {
        val suffix = userName?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.take(24)
        val id = UUID.randomUUID().toString().take(8)
        return if (suffix.isNullOrBlank()) "shuanyin_$id" else "shuanyin_${suffix}_$id"
    }

    fun startArgv(session: String, cols: Int = 200, rows: Int = 50): Array<String> =
        arrayOf("new-session", "-d", "-s", session, "-x", cols.toString(), "-y", rows.toString())

    fun sendTextArgv(session: String, text: String): Array<String> =
        arrayOf("send-keys", "-t", session, "-l", "--", text)

    fun sendKeysArgv(session: String, keys: List<String>): Array<String> =
        (listOf("send-keys", "-t", session) + keys).toTypedArray()

    fun enterArgv(session: String): Array<String> =
        arrayOf("send-keys", "-t", session, "Enter")

    fun capturePaneArgv(session: String, lines: Int): Array<String> =
        arrayOf("capture-pane", "-t", session, "-p", "-S", "-${lines.coerceAtLeast(0)}")

    fun killArgv(session: String): Array<String> =
        arrayOf("kill-session", "-t", session)

    fun listArgv(): Array<String> =
        arrayOf("list-sessions", "-F", "#{session_name}\t#{session_created}\t#{session_activity}")
}

internal data class TmuxSessionInfo(val name: String, val created: Long, val lastActivity: Long)

internal fun parseSessions(stdout: String, prefix: String = "shuanyin_"): List<TmuxSessionInfo> =
    stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !parts[0].startsWith(prefix)) return@mapNotNull null
            TmuxSessionInfo(
                name = parts[0],
                created = parts[1].toLongOrNull() ?: 0L,
                lastActivity = parts[2].toLongOrNull() ?: 0L,
            )
        }.toList()

internal fun isSessionNotFound(stderr: String): Boolean {
    val s = stderr.lowercase()
    return s.contains("can't find session") ||
        s.contains("no server running") ||
        s.contains("session not found") ||
        s.contains("no current session")
}

private suspend fun tmux(
    context: Context,
    argv: Array<String>,
    timeoutMs: Long = TMUX_OP_TIMEOUT_MS,
): CaptureResult = runCommandCapture(context, "$TERMUX_BIN/tmux", argv, TERMUX_HOME, timeoutMs)

/** Ensure tmux exists; auto-install on first use. Null = ok, else an error key. */
private suspend fun ensureTmux(context: Context): String? {
    val check = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    if (check is CaptureResult.Success && check.stdout.isNotBlank()) return null
    val install = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", "pkg install -y tmux"), TERMUX_HOME, TMUX_INSTALL_TIMEOUT_MS)
    if (install is CaptureResult.Denied) return "termux_permission_denied"
    if (install is CaptureResult.Timeout) return "tmux_installing"
    val recheck = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    return if (recheck is CaptureResult.Success && recheck.stdout.isNotBlank()) null else "tmux_install_failed"
}

// ---------------------------------------------------------------------------
// Tool factories
// ---------------------------------------------------------------------------

private fun strParam(args: JsonElement, key: String): String? =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun intParam(args: JsonElement, key: String, default: Int, max: Int): Int =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.intOrNull?.coerceIn(1, max) ?: default

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProp(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun boolProp(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun errorJson(error: String, recovery: String? = null) = buildJsonObject {
    put("error", error)
    if (recovery != null) put("recovery", recovery)
}

/** Pre-flight shared by every Termux tool. Null = proceed. */
private fun preflight(context: Context): JsonElement? = when (TermuxIntegration.state(context)) {
    TermuxIntegration.State.NOT_INSTALLED -> errorJson(
        "termux_not_installed",
        "Install Termux from the official GitHub releases (not Play Store / F-Droid, those are unmaintained), then retry."
    )
    TermuxIntegration.State.NO_PERMISSION -> errorJson(
        "termux_permission_not_granted",
        "Grant the Termux RUN_COMMAND permission to Shuanyin in Android Settings, and set allow-external-apps=true in ~/.termux/termux.properties inside Termux (then force-stop and reopen Termux)."
    )
    TermuxIntegration.State.READY -> null
}

private fun captureToJson(res: CaptureResult, timeoutMs: Long): JsonElement = when (res) {
    is CaptureResult.Success -> buildJsonObject {
        put("success", true)
        put("exit_code", res.exitCode)
        val out = res.stdout
        put("stdout", if (out.length > TERMUX_MAX_OUTPUT_CHARS) out.take(TERMUX_MAX_OUTPUT_CHARS) + "\n…[truncated]" else out)
        if (res.stderr.isNotBlank()) {
            val err = res.stderr
            put("stderr", if (err.length > TERMUX_MAX_OUTPUT_CHARS) err.take(TERMUX_MAX_OUTPUT_CHARS) + "\n…[truncated]" else err)
        }
        if (res.exitCode != 0) put("note", "Non-zero exit code; check stderr.")
    }
    is CaptureResult.Timeout -> errorJson(
        "timeout",
        "Command did not return within ${timeoutMs / 1000}s. If Termux gave no result at all, allow-external-apps=true is probably missing in ~/.termux/termux.properties (force-stop Termux after editing)."
    )
    is CaptureResult.Denied -> errorJson(
        "termux_permission_denied",
        "In Termux run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties — then force-stop Termux and reopen it."
    )
    is CaptureResult.OtherError -> errorJson("termux_run_failed", res.message)
}

private fun truncateScreen(s: String): String {
    val trimmed = s.trimEnd('\n', ' ', '\t')
    return if (trimmed.length > TERMUX_MAX_OUTPUT_CHARS) {
        trimmed.takeLast(TERMUX_MAX_OUTPUT_CHARS) + "\n…[older scrollback truncated]"
    } else trimmed
}

/**
 * Create the Termux agent tools.
 *
 * @param fullAuth the "تفويض الكامل" (full authorization) switch of the Termux scope:
 *                 true  -> tools run WITHOUT user approval,
 *                 false -> every call shows an approval card first.
 * @param ubuntuEnabled granular permission button for Ubuntu (proot-distro) access.
 *                 false -> termux_run_ubuntu is not registered at all (blocked,
 *                 even with fullAuth on), per the permission truth table.
 */
fun createTermuxTools(
    context: Context,
    fullAuth: Boolean,
    ubuntuEnabled: Boolean = true,
    defaultTimeoutSec: Int = 60,
): List<Tool> {
    val appContext = context.applicationContext
    val needsApproval = !fullAuth

    fun resolveTimeoutMs(args: JsonElement): Long {
        val raw = args.jsonObject["timeout_seconds"]?.jsonPrimitiveOrNull?.intOrNull
        val secs = when {
            raw == null || raw == 0 -> defaultTimeoutSec
            else -> raw.coerceIn(1, 600)
        }
        return secs.toLong() * 1000
    }

    return buildList {
        add(Tool(
            name = "termux_run_command",
            description = "Run a shell command in Termux on this Android device. Default mode runs in background and " +
                "returns stdout/stderr/exit_code so you can reason about the output. Pass interactive=true to open a " +
                "visible Termux session instead (no output returned). Termux home: /data/data/com.termux/files/home.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("command", stringProp("Shell command, e.g. 'pkg list-installed | head'. Mutually exclusive with executable+arguments."))
                        put("executable", stringProp("Absolute executable path. Pairs with arguments[]."))
                        put("arguments", buildJsonObject {
                            put("type", "array")
                            put("description", "Argument list for executable mode.")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("working_dir", stringProp("Working directory (optional, default: Termux home)."))
                        put("interactive", boolProp("Open a visible Termux session instead of capturing output (default false)."))
                        put("timeout_seconds", intProp("Capture timeout in seconds; 0 = user default (optional)."))
                    },
                )
            },
            requiresUserApproval = needsApproval,
            execute = { args ->
                preflight(appContext)?.let { return@Tool it }
                val rawCommand = strParam(args, "command")
                val executable = strParam(args, "executable")
                if (rawCommand.isNullOrBlank() && executable.isNullOrBlank()) {
                    return@Tool errorJson("missing_command", "Provide 'command' or 'executable'+'arguments'.")
                }
                if (!rawCommand.isNullOrBlank() && !executable.isNullOrBlank()) {
                    return@Tool errorJson("bad_args", "'command' and 'executable' are mutually exclusive.")
                }
                val workingDir = strParam(args, "working_dir") ?: TERMUX_HOME
                val interactive = args.jsonObject["interactive"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false
                val timeoutMs = resolveTimeoutMs(args)

                val (resolvedExe, resolvedArgs) = if (rawCommand != null) {
                    "$TERMUX_BIN/bash" to arrayOf("-c", rawCommand)
                } else {
                    val argv = args.jsonObject["arguments"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }?.toTypedArray() ?: emptyArray()
                    executable!! to argv
                }

                if (interactive) {
                    val intent = Intent().apply {
                        setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                        action = TERMUX_RUN_COMMAND_ACTION
                        putExtra("com.termux.RUN_COMMAND_PATH", resolvedExe)
                        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", resolvedArgs)
                        putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
                        putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                        putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                    }
                    return@Tool try {
                        appContext.startService(intent)
                        buildJsonObject {
                            put("success", true)
                            put("mode", "interactive")
                            put("note", "Opened a visible Termux session. Output is NOT captured.")
                        }
                    } catch (t: SecurityException) {
                        errorJson("termux_permission_denied")
                    } catch (t: Exception) {
                        errorJson("dispatch_failed", t.message ?: t.javaClass.simpleName)
                    }
                }

                captureToJson(
                    runCommandCapture(appContext, resolvedExe, resolvedArgs, workingDir, timeoutMs),
                    timeoutMs,
                )
            }
        ))
        if (ubuntuEnabled) {
            add(Tool(
                name = "termux_run_ubuntu",
                description = "Run a shell command inside the Ubuntu proot-distro on this device " +
                    "(proot-distro login ubuntu -- ...) and return stdout/stderr/exit_code. Use for full-Linux work: " +
                    "apt, python, node, servers, and repairing agents that live in Ubuntu (e.g. Hermes).",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("command", stringProp("Shell command to run inside Ubuntu (required)."))
                            put("timeout_seconds", intProp("Timeout in seconds; 0 = user default (optional)."))
                        },
                        required = listOf("command"),
                    )
                },
                requiresUserApproval = needsApproval,
                execute = { args ->
                    preflight(appContext)?.let { return@Tool it }
                    val command = strParam(args, "command")
                        ?: return@Tool errorJson("missing_command", "Provide 'command'.")
                    val timeoutMs = resolveTimeoutMs(args)
                    // Verified form from proot-distro README: `proot-distro login ubuntu -- /bin/cmd args`
                    captureToJson(
                        runCommandCapture(
                            ctx = appContext,
                            executable = "$TERMUX_BIN/proot-distro",
                            arguments = arrayOf("login", "ubuntu", "--", "/bin/sh", "-c", command),
                            workingDir = TERMUX_HOME,
                            timeoutMs = timeoutMs,
                        ),
                        timeoutMs,
                    )
                }
            ))
        }
        add(Tool(
            name = "termux_session_start",
            description = "Open a persistent, interactive Termux terminal session (tmux-backed, real pty). Use for ssh, " +
                "password prompts, REPLs, or stateful shells. Returns session_id; drive it with termux_session_send / " +
                "termux_session_read. tmux auto-installs on first use.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", stringProp("Optional friendly label for the session."))
                        put("command", stringProp("Optional initial command, e.g. 'ssh myhost'."))
                    },
                )
            },
            requiresUserApproval = needsApproval,
            execute = { args ->
                preflight(appContext)?.let { return@Tool it }
                when (val err = ensureTmux(appContext)) {
                    "tmux_installing" -> return@Tool errorJson(err, "tmux is still installing. Wait a moment and retry.")
                    "termux_permission_denied", "tmux_install_failed" ->
                        return@Tool errorJson(err ?: "tmux_install_failed", "Open Termux and run 'pkg install tmux', then retry.")
                }
                val live = (tmux(appContext, TmuxOps.listArgv()) as? CaptureResult.Success)
                    ?.let { parseSessions(it.stdout) } ?: emptyList()
                val nowSecs = System.currentTimeMillis() / 1000
                val stale = live.filter { it.lastActivity in 1 until (nowSecs - SESSION_TTL_MS / 1000) }
                var current = live
                if (stale.isNotEmpty()) {
                    val reaped = stale.filter { s ->
                        val killed = tmux(appContext, TmuxOps.killArgv(s.name))
                        killed is CaptureResult.Success ||
                            (killed is CaptureResult.OtherError && isSessionNotFound(killed.message))
                    }
                    current = live - reaped.toSet()
                }
                if (current.size >= MAX_SESSIONS) {
                    return@Tool errorJson("too_many_sessions", "Kill one with termux_session_kill first. Live: ${current.joinToString { it.name }}")
                }
                val name = TmuxOps.sessionName(strParam(args, "name"))
                val started = tmux(appContext, TmuxOps.startArgv(name))
                if (started !is CaptureResult.Success) {
                    return@Tool errorJson("session_start_failed", "tmux new-session failed.")
                }
                val initial = strParam(args, "command")
                if (!initial.isNullOrBlank()) {
                    tmux(appContext, TmuxOps.sendTextArgv(name, initial))
                    tmux(appContext, TmuxOps.enterArgv(name))
                }
                val screen = (tmux(appContext, TmuxOps.capturePaneArgv(name, 200)) as? CaptureResult.Success)
                    ?.stdout?.let { truncateScreen(it) } ?: ""
                buildJsonObject {
                    put("success", true)
                    put("session_id", name)
                    put("screen", screen)
                }
            }
        ))
        add(Tool(
            name = "termux_session_send",
            description = "Type into a session and read back the screen. enter=false types without newline (answering prompts). " +
                "keys sends tmux key names (['C-c']). wait_for returns as soon as text appears (e.g. 'password:').",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", stringProp("Session id from termux_session_start (required)."))
                        put("input", stringProp("Text to type. Optional if keys is given."))
                        put("enter", boolProp("Press Enter after input (default true)."))
                        put("keys", buildJsonObject {
                            put("type", "array")
                            put("description", "tmux key names, e.g. ['C-c'].")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("wait_for", stringProp("Return as soon as this substring/regex appears."))
                        put("timeout_seconds", intProp("Wait timeout; default $SESSION_DEFAULT_TIMEOUT_S, max $SESSION_MAX_TIMEOUT_S."))
                    },
                    required = listOf("session_id"),
                )
            },
            requiresUserApproval = needsApproval,
            execute = { args ->
                preflight(appContext)?.let { return@Tool it }
                val session = strParam(args, "session_id")
                    ?: return@Tool errorJson("missing_session_id", "Pass session_id from termux_session_start.")
                val text = strParam(args, "input")
                val enter = args.jsonObject["enter"]?.jsonPrimitiveOrNull?.booleanOrNull ?: true
                val keys = args.jsonObject["keys"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull } ?: emptyList()
                val waitFor = strParam(args, "wait_for")
                val timeoutMs = intParam(args, "timeout_seconds", SESSION_DEFAULT_TIMEOUT_S, SESSION_MAX_TIMEOUT_S).toLong() * 1000

                if (!text.isNullOrEmpty()) {
                    val sent = tmux(appContext, TmuxOps.sendTextArgv(session, text))
                    if (sent is CaptureResult.OtherError && isSessionNotFound(sent.message)) {
                        return@Tool errorJson("session_not_found", "Session '$session' is gone. Start a new one with termux_session_start.")
                    }
                }
                if (keys.isNotEmpty()) {
                    val sentKeys = tmux(appContext, TmuxOps.sendKeysArgv(session, keys))
                    if (sentKeys is CaptureResult.OtherError && isSessionNotFound(sentKeys.message)) {
                        return@Tool errorJson("session_not_found", "Session '$session' is gone. Start a new one with termux_session_start.")
                    }
                }
                if (enter) {
                    val sentEnter = tmux(appContext, TmuxOps.enterArgv(session))
                    if (sentEnter is CaptureResult.OtherError && isSessionNotFound(sentEnter.message)) {
                        return@Tool errorJson("session_not_found", "Session '$session' is gone. Start a new one with termux_session_start.")
                    }
                }
                // Poll the pane until wait_for matches, the screen settles, or timeout.
                val start = android.os.SystemClock.elapsedRealtime()
                var last = ""
                var matched = false
                while (true) {
                    val cap = tmux(appContext, TmuxOps.capturePaneArgv(session, 200))
                    if (cap is CaptureResult.OtherError && isSessionNotFound(cap.message)) {
                        return@Tool errorJson("session_not_found", "Session '$session' is gone.")
                    }
                    val screen = (cap as? CaptureResult.Success)?.stdout ?: ""
                    last = screen
                    if (!waitFor.isNullOrEmpty()) {
                        val rx = runCatching { Regex(waitFor) }.getOrNull()
                        val hit = if (rx != null) rx.containsMatchIn(screen) else screen.contains(waitFor)
                        if (hit) { matched = true; break }
                    } else {
                        break
                    }
                    if (android.os.SystemClock.elapsedRealtime() - start >= timeoutMs) break
                    kotlinx.coroutines.delay(300)
                }
                buildJsonObject {
                    put("success", true)
                    put("screen", truncateScreen(last))
                    put("matched_wait_for", matched)
                }
            }
        ))
        add(Tool(
            name = "termux_session_read",
            description = "Re-read a session's screen without sending input (check on a long-running command). " +
                "wait_for + timeout_seconds optionally wait for expected text.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", stringProp("Session id (required)."))
                        put("wait_for", stringProp("Optional substring/regex to wait for."))
                        put("timeout_seconds", intProp("Used only with wait_for."))
                        put("lines", intProp("Scrollback lines (optional)."))
                    },
                    required = listOf("session_id"),
                )
            },
            requiresUserApproval = needsApproval,
            execute = { args ->
                preflight(appContext)?.let { return@Tool it }
                val session = strParam(args, "session_id")
                    ?: return@Tool errorJson("missing_session_id", "Pass session_id.")
                val waitFor = strParam(args, "wait_for")
                val lines = intParam(args, "lines", 200, 1000)
                val timeoutMs = intParam(args, "timeout_seconds", SESSION_DEFAULT_TIMEOUT_S, SESSION_MAX_TIMEOUT_S).toLong() * 1000
                val start = android.os.SystemClock.elapsedRealtime()
                var last = ""
                var matched = false
                while (true) {
                    val cap = tmux(appContext, TmuxOps.capturePaneArgv(session, lines))
                    if (cap is CaptureResult.OtherError && isSessionNotFound(cap.message)) {
                        return@Tool errorJson("session_not_found", "Session '$session' is gone.")
                    }
                    last = (cap as? CaptureResult.Success)?.stdout ?: ""
                    if (waitFor.isNullOrEmpty()) break
                    val rx = runCatching { Regex(waitFor) }.getOrNull()
                    val hit = if (rx != null) rx.containsMatchIn(last) else last.contains(waitFor)
                    if (hit) { matched = true; break }
                    if (android.os.SystemClock.elapsedRealtime() - start >= timeoutMs) break
                    kotlinx.coroutines.delay(300)
                }
                buildJsonObject {
                    put("success", true)
                    put("screen", truncateScreen(last))
                    put("matched_wait_for", matched)
                }
            }
        ))
        add(Tool(
            name = "termux_session_kill",
            description = "End a Termux session opened by termux_session_start.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("session_id", stringProp("Session id to kill (required)."))
                    },
                    required = listOf("session_id"),
                )
            },
            requiresUserApproval = needsApproval,
            execute = { args ->
                preflight(appContext)?.let { return@Tool it }
                val session = strParam(args, "session_id")
                    ?: return@Tool errorJson("missing_session_id", "Pass session_id.")
                tmux(appContext, TmuxOps.killArgv(session))
                buildJsonObject {
                    put("success", true)
                    put("killed", session)
                }
            }
        ))
        add(Tool(
            name = "termux_session_list",
            description = "List live Termux sessions opened by the agent.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            requiresUserApproval = needsApproval,
            execute = {
                preflight(appContext)?.let { return@Tool it }
                val list = (tmux(appContext, TmuxOps.listArgv()) as? CaptureResult.Success)
                    ?.let { parseSessions(it.stdout) } ?: emptyList()
                buildJsonObject {
                    put("success", true)
                    put("sessions", buildJsonArray {
                        list.forEach { s ->
                            add(buildJsonObject {
                                put("session_id", s.name)
                                put("created", s.created)
                                put("last_activity", s.lastActivity)
                            })
                        }
                    })
                }
            }
        ))
    }
}
