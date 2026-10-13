package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.io.File
import java.nio.charset.StandardCharsets

private const val STORAGE_DIR_NAME = "Shuanyin"
private const val MAX_CONTENT_CHARS = 50_000
private const val MAX_LIST_ENTRIES = 1_000
private const val MAX_PATH_CHARS = 4_096

/** Normalizes and validates a path relative to the shared Shuanyin directory. */
fun normalizeStorageRelativePath(path: String?): String? {
    val normalized = path?.trim()?.replace('\\', '/') ?: return null
    if (normalized == "" || normalized == ".") return ""
    if (normalized.length > MAX_PATH_CHARS || normalized.startsWith('/') ||
        normalized.matches(Regex("^[A-Za-z]:/.*"))
    ) return null

    val parts = normalized.split('/')
    if (parts.any { it.isEmpty() || it == ".." || it == "." }) return null
    return parts.joinToString("/")
}

private fun stringParam(args: JsonElement, key: String): String? =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.contentOrNull

private fun errorResult(message: String) = buildJsonObject {
    put("error", message)
}

private fun storageRoot(context: Context): File =
    File(Environment.getExternalStorageDirectory(), STORAGE_DIR_NAME)

private fun permissionError(): IllegalStateException =
    IllegalStateException("Storage permission is required: grant All files access to Shuanyin")

private fun ensureRoot(context: Context): File {
    if (!Environment.isExternalStorageManager()) throw permissionError()
    val root = storageRoot(context)
    if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
        throw IllegalStateException("Unable to create shared storage directory: ${root.path}")
    }
    if (!root.isDirectory) throw IllegalStateException("Shared storage path is not a directory")
    return root.canonicalFile
}

private fun safeFile(root: File, relativePath: String): File {
    val candidate = if (relativePath.isEmpty()) root else File(root, relativePath)
    val canonical = candidate.canonicalFile
    val rootPath = root.canonicalPath
    val candidatePath = canonical.canonicalPath
    if (candidatePath != rootPath && !candidatePath.startsWith(rootPath + File.separator)) {
        throw IllegalStateException("Path escapes the Shuanyin storage directory")
    }
    return canonical
}

private fun readLimited(file: File): Pair<String, Boolean> {
    file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
        val chars = CharArray(MAX_CONTENT_CHARS + 1)
        var count = 0
        while (count < chars.size) {
            val read = reader.read(chars, count, chars.size - count)
            if (read < 0) break
            count += read
        }
        return String(chars, 0, minOf(count, MAX_CONTENT_CHARS)) to (count > MAX_CONTENT_CHARS)
    }
}

fun createStorageTools(context: Context): List<Tool> = listOf(
    Tool(
        name = "storage_list_files",
        description = "List files and directories inside the shared Shuanyin storage folder.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Relative directory path inside Shuanyin; empty or . lists the root")
                    })
                },
            )
        },
        execute = { args ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val relative = normalizeStorageRelativePath(stringParam(args, "path") ?: "")
                        ?: return@runCatching errorResult("Invalid relative path")
                    val root = ensureRoot(context)
                    val directory = safeFile(root, relative)
                    if (!directory.isDirectory) return@runCatching errorResult("Path is not a directory")
                    val entries = directory.listFiles()?.mapNotNull { entry ->
                        val safeEntry = runCatching { safeFile(root, entry.relativeTo(root).path) }.getOrNull()
                            ?: return@mapNotNull null
                        buildJsonObject {
                            put("name", entry.name)
                            put("type", if (safeEntry.isDirectory) "directory" else "file")
                            put("size", if (safeEntry.isFile) safeEntry.length() else 0L)
                        }
                    }?.sortedBy { it["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: "" }
                        ?: emptyList()
                    val truncated = entries.size > MAX_LIST_ENTRIES
                    buildJsonObject {
                        put("path", relative)
                        put("truncated", truncated)
                        put("entries", buildJsonArray {
                            entries.take(MAX_LIST_ENTRIES).forEach { add(it) }
                        })
                    }
                }.getOrElse { errorResult(it.message ?: "Unable to list storage") }
            }
        },
    ),
    Tool(
        name = "storage_read_file",
        description = "Read a UTF-8 text file inside the shared Shuanyin storage folder.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Relative file path inside Shuanyin")
                    })
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val relative = normalizeStorageRelativePath(stringParam(args, "path"))
                        ?: return@runCatching errorResult("Invalid relative path")
                    if (relative.isEmpty()) return@runCatching errorResult("A file path is required")
                    val file = safeFile(ensureRoot(context), relative)
                    if (!file.isFile) return@runCatching errorResult("File not found")
                    val (content, truncated) = readLimited(file)
                    buildJsonObject {
                        put("path", relative)
                        put("truncated", truncated)
                        put("content", content)
                    }
                }.getOrElse { errorResult(it.message ?: "Unable to read storage file") }
            }
        },
    ),
    Tool(
        name = "storage_write_file",
        description = "Write a UTF-8 text file inside the shared Shuanyin storage folder. Requires user approval.",
        requiresUserApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Relative file path inside Shuanyin")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "UTF-8 text content, maximum $MAX_CONTENT_CHARS characters")
                    })
                },
                required = listOf("path", "content"),
            )
        },
        execute = { args ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val relative = normalizeStorageRelativePath(stringParam(args, "path"))
                        ?: return@runCatching errorResult("Invalid relative path")
                    val content = stringParam(args, "content")
                        ?: return@runCatching errorResult("File content is required")
                    if (relative.isEmpty()) return@runCatching errorResult("A file path is required")
                    if (content.length > MAX_CONTENT_CHARS) {
                        return@runCatching errorResult("Content exceeds the $MAX_CONTENT_CHARS character limit")
                    }
                    val root = ensureRoot(context)
                    val file = safeFile(root, relative)
                    val parent = file.parentFile
                        ?: return@runCatching errorResult("Invalid parent directory")
                    if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                        return@runCatching errorResult("Unable to create parent directory")
                    }
                    if (!parent.isDirectory) return@runCatching errorResult("Parent path is not a directory")
                    safeFile(root, relative).writeText(content, StandardCharsets.UTF_8)
                    buildJsonObject {
                        put("path", relative)
                        put("bytes_written", content.toByteArray(StandardCharsets.UTF_8).size)
                    }
                }.getOrElse { errorResult(it.message ?: "Unable to write storage file") }
            }
        },
    ),
)
