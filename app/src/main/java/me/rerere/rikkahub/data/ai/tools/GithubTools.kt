package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

private val githubHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

private val githubJson = Json { ignoreUnknownKeys = true }

private const val GITHUB_API = "https://api.github.com"
private const val GITHUB_MAX_CHARS = 50_000

internal suspend fun githubApi(
    pat: String,
    path: String,
    method: String = "GET",
    jsonBody: String? = null,
): Result<JsonElement> = withContext(Dispatchers.IO) {
    runCatching {
        val builder = Request.Builder()
            .url("$GITHUB_API$path")
            .addHeader("Authorization", "Bearer $pat")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
        if (method == "POST") {
            builder.post((jsonBody ?: "").toRequestBody("application/json".toMediaType()))
        } else {
            builder.get()
        }
        githubHttpClient.newCall(builder.build()).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                val apiMessage = runCatching {
                    githubJson.parseToJsonElement(body).jsonObject["message"]
                        ?.jsonPrimitiveOrNull?.contentOrNull
                }.getOrNull()
                throw IllegalStateException("GitHub API ${resp.code}: ${apiMessage ?: body.take(200)}")
            }
            githubJson.parseToJsonElement(body)
        }
    }
}

private fun strParam(args: JsonElement, key: String): String? =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun intParam(args: JsonElement, key: String, default: Int, max: Int): Int =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.intOrNull?.coerceIn(1, max) ?: default

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProperty(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

fun createGithubTools(pat: String): List<Tool> = listOf(
    Tool(
        name = "github_list_repos",
        description = "List GitHub repositories of the authenticated user. Use this to discover which repos exist before reading files or issues.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("visibility", buildJsonObject {
                        put("type", "string")
                        put("description", "Filter by visibility: all, public or private (default: all)")
                    })
                    put("per_page", intProperty("Results per page, max 100 (default: 30)"))
                },
            )
        },
        execute = { args ->
            val visibility = strParam(args, "visibility")?.takeIf { it in setOf("all", "public", "private") } ?: "all"
            val perPage = intParam(args, "per_page", 30, 100)
            githubApi(pat, "/user/repos?visibility=$visibility&per_page=$perPage&sort=updated")
                .fold(
                    onSuccess = { el ->
                        buildJsonObject {
                            put("repos", buildJsonArray {
                                el.jsonArray.forEach { repo ->
                                    val o = repo.jsonObject
                                    add(buildJsonObject {
                                        put("name", o["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                        put("full_name", o["full_name"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                        put("private", o["private"]?.jsonPrimitiveOrNull?.contentOrNull == "true")
                                        put("description", o["description"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                        put("updated_at", o["updated_at"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                    })
                                }
                            })
                        }
                    },
                    onFailure = { e ->
                        buildJsonObject { put("error", e.message ?: "request failed") }
                    }
                )
        }
    ),
    Tool(
        name = "github_read_file",
        description = "Read a file's content from a GitHub repository. If path is a directory, lists its entries instead.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", stringProperty("Repository owner (user or org)"))
                    put("repo", stringProperty("Repository name"))
                    put("path", stringProperty("File or directory path inside the repo, e.g. README.md"))
                    put("ref", stringProperty("Branch, tag or commit SHA (optional, default: repo default branch)"))
                },
                required = listOf("owner", "repo", "path"),
            )
        },
        execute = { args ->
            val owner = strParam(args, "owner")
            val repo = strParam(args, "repo")
            val path = strParam(args, "path")
            if (owner.isNullOrBlank() || repo.isNullOrBlank() || path.isNullOrBlank()) {
                return@Tool buildJsonObject { put("error", "Missing owner, repo or path") }
            }
            val ref = strParam(args, "ref")?.let { "?ref=$it" } ?: ""
            githubApi(pat, "/repos/$owner/$repo/contents/$path$ref")
                .fold(
                    onSuccess = { el ->
                        if (el is kotlinx.serialization.json.JsonArray) {
                            buildJsonObject {
                                put("type", "directory")
                                put("entries", buildJsonArray {
                                    el.forEach { entry ->
                                        val o = entry.jsonObject
                                        add(buildJsonObject {
                                            put("name", o["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                            put("type", o["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                        })
                                    }
                                })
                            }
                        } else {
                            val o = el.jsonObject
                            val encoding = o["encoding"]?.jsonPrimitiveOrNull?.contentOrNull
                            val raw = o["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
                            val text = if (encoding == "base64") {
                                runCatching {
                                    String(Base64.getDecoder().decode(raw.filterNot { it.isWhitespace() }))
                                }.getOrNull() ?: raw
                            } else raw
                            val truncated = text.length > GITHUB_MAX_CHARS
                            buildJsonObject {
                                put("type", "file")
                                put("path", o["path"]?.jsonPrimitiveOrNull?.contentOrNull ?: path)
                                put("sha", o["sha"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                put("size", o["size"]?.jsonPrimitiveOrNull?.intOrNull ?: 0)
                                put("truncated", truncated)
                                put("content", if (truncated) text.take(GITHUB_MAX_CHARS) else text)
                            }
                        }
                    },
                    onFailure = { e ->
                        buildJsonObject { put("error", e.message ?: "request failed") }
                    }
                )
        }
    ),
    Tool(
        name = "github_list_issues",
        description = "List issues of a GitHub repository.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", stringProperty("Repository owner (user or org)"))
                    put("repo", stringProperty("Repository name"))
                    put("state", stringProperty("Issue state: open, closed or all (default: open)"))
                    put("per_page", intProperty("Results per page, max 100 (default: 30)"))
                },
                required = listOf("owner", "repo"),
            )
        },
        execute = { args ->
            val owner = strParam(args, "owner")
            val repo = strParam(args, "repo")
            if (owner.isNullOrBlank() || repo.isNullOrBlank()) {
                return@Tool buildJsonObject { put("error", "Missing owner or repo") }
            }
            val state = strParam(args, "state")?.takeIf { it in setOf("open", "closed", "all") } ?: "open"
            val perPage = intParam(args, "per_page", 30, 100)
            githubApi(pat, "/repos/$owner/$repo/issues?state=$state&per_page=$perPage")
                .fold(
                    onSuccess = { el ->
                        buildJsonObject {
                            put("issues", buildJsonArray {
                                el.jsonArray.forEach { issue ->
                                    val o = issue.jsonObject
                                    add(buildJsonObject {
                                        put("number", o["number"]?.jsonPrimitiveOrNull?.intOrNull ?: 0)
                                        put("title", o["title"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                        put("state", o["state"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                        put("author", o.jsonObject["user"]?.jsonObject?.get("login")?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                        put("created_at", o["created_at"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                                    })
                                }
                            })
                        }
                    },
                    onFailure = { e ->
                        buildJsonObject { put("error", e.message ?: "request failed") }
                    }
                )
        }
    ),
    Tool(
        name = "github_create_issue",
        description = "Create a new issue in a GitHub repository. Requires user approval before running.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", stringProperty("Repository owner (user or org)"))
                    put("repo", stringProperty("Repository name"))
                    put("title", stringProperty("Issue title"))
                    put("body", stringProperty("Issue body in Markdown (optional)"))
                },
                required = listOf("owner", "repo", "title"),
            )
        },
        requiresUserApproval = true,
        execute = { args ->
            val owner = strParam(args, "owner")
            val repo = strParam(args, "repo")
            val title = strParam(args, "title")
            if (owner.isNullOrBlank() || repo.isNullOrBlank() || title.isNullOrBlank()) {
                return@Tool buildJsonObject { put("error", "Missing owner, repo or title") }
            }
            val body = strParam(args, "body") ?: ""
            val payload = buildJsonObject {
                put("title", title)
                put("body", body)
            }.toString()
            githubApi(pat, "/repos/$owner/$repo/issues", "POST", payload)
                .fold(
                    onSuccess = { el ->
                        val o = el.jsonObject
                        buildJsonObject {
                            put("number", o["number"]?.jsonPrimitiveOrNull?.intOrNull ?: 0)
                            put("url", o["html_url"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                            put("title", o["title"]?.jsonPrimitiveOrNull?.contentOrNull ?: "")
                        }
                    },
                    onFailure = { e ->
                        buildJsonObject { put("error", e.message ?: "request failed") }
                    }
                )
        }
    ),
)
