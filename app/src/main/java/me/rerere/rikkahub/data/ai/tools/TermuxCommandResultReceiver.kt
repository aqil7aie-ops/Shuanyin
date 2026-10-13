package me.rerere.rikkahub.data.ai.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/** The result returned by Termux RUN_COMMAND in background mode. */
data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

/**
 * Receives the explicit PendingIntent callback sent by Termux.
 *
 * This receiver is intentionally small: the waiting coroutine owns the timeout and removes its
 * entry. A callback that arrives after a timeout is simply ignored.
 */
class TermuxCommandResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(TermuxCommandRunner.EXTRA_REQUEST_ID) ?: return
        val result = intent.getBundleExtra(TermuxCommandRunner.EXTRA_RESULT_BUNDLE) ?: return
        val deferred = pending.remove(requestId) ?: return
        deferred.complete(
            TermuxCommandResult(
                stdout = result.getString("stdout").orEmpty(),
                stderr = result.getString("stderr").orEmpty(),
                exitCode = result.getInt("exitCode", -1),
            ),
        )
    }

    companion object {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

        internal fun register(id: String, deferred: CompletableDeferred<TermuxCommandResult>) {
            pending[id] = deferred
        }

        internal fun remove(id: String) {
            pending.remove(id)
        }
    }
}
