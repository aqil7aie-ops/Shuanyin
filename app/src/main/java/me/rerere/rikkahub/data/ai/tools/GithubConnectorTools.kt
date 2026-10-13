package me.rerere.rikkahub.data.ai.tools

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

private const val GITHUB_CONNECTOR_MAX_PAGE = 100

private fun githubConnectorString(args: JsonElement, key: String): String? =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun githubConnectorInt(args: JsonElement, key: String, max: Int): Int =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.intOrNull?.coerceIn(1, max) ?: 30

private fun githubConnectorBoolean(args: JsonElement, key: String, default: Boolean): Boolean =
    args.jsonObject[key]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() ?: default

private fun githubRepoPart(value: String?): String? =
    value?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }

private fun githubStringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun githubError(message: String) = buildJsonObject { put("error", message) }

/** Returns a bounded list of repository names for the GitHub settings connection test. */
suspend fun listGithubRepositories(pat: String): Result<List<String>> =
    githubApi(pat, "/user/repos?visibility=all&per_page=30&sort=updated").map { element ->
        element.jsonArray.mapNotNull { repository ->
            repository.jsonObject["full_name"]?.jsonPrimitiveOrNull?.contentOrNull
                ?.takeIf(String::isNotBlank)
        }
    }

/** Implements exactly the v2 GitHub additions: create repository/PR, list PRs, branches and commits. */
fun createGithubWorkflowTools(pat: String): List<Tool> = listOf(
    Tool(
        name = "github_create_repo",
        description = "Create a GitHub repository. This writes to GitHub and requires explicit user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", githubStringProperty("Repository name"))
                    put("description", githubStringProperty("Repository description (optional)"))
                    put("private", buildJsonObject { put("type", "boolean"); put("description", "Whether the repository is private (default true)") })
                    put("auto_init", buildJsonObject { put("type", "boolean"); put("description", "Initialize with a README (default false)") })
                },
                required = listOf("name"),
            )
        },
        requiresUserApproval = true,
        execute = { args ->
            val name = githubConnectorString(args, "name")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }
                ?: return@Tool githubError("Missing or invalid repository name")
            val description = githubConnectorString(args, "description").orEmpty().take(350)
            val isPrivate = githubConnectorBoolean(args, "private", true)
            val autoInit = githubConnectorBoolean(args, "auto_init", false)
            val payload = buildJsonObject {
                put("name", name)
                put("description", description)
                put("private", isPrivate)
                put("auto_init", autoInit)
            }.toString()
            githubApi(pat, "/user/repos", "POST", payload).fold(
                onSuccess = { response ->
                    val item = response.jsonObject
                    buildJsonObject {
                        put("name", item["full_name"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                        put("private", item["private"]?.jsonPrimitiveOrNull?.contentOrNull == "true")
                        put("url", item["html_url"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                    }
                },
                onFailure = { githubError(it.message ?: "GitHub request failed") },
            )
        },
    ),
    Tool(
        name = "github_list_prs",
        description = "List pull requests in a GitHub repository. This is read-only.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", githubStringProperty("Repository owner"))
                    put("repo", githubStringProperty("Repository name"))
                    put("state", githubStringProperty("open, closed or all (default open)"))
                    put("per_page", buildJsonObject { put("type", "integer"); put("description", "Maximum 100 results") })
                },
                required = listOf("owner", "repo"),
            )
        },
        execute = { args ->
            val owner = githubRepoPart(githubConnectorString(args, "owner"))
            val repo = githubRepoPart(githubConnectorString(args, "repo"))
            if (owner == null || repo == null) return@Tool githubError("Missing or invalid owner/repo")
            val state = githubConnectorString(args, "state")?.takeIf { it in setOf("open", "closed", "all") } ?: "open"
            val perPage = githubConnectorInt(args, "per_page", GITHUB_CONNECTOR_MAX_PAGE)
            githubApi(pat, "/repos/$owner/$repo/pulls?state=$state&per_page=$perPage").fold(
                onSuccess = { response ->
                    buildJsonObject {
                        put("pull_requests", buildJsonArray {
                            response.jsonArray.forEach { pull ->
                                val item = pull.jsonObject
                                add(buildJsonObject {
                                    put("number", item["number"]?.jsonPrimitiveOrNull?.intOrNull ?: 0)
                                    put("title", item["title"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("state", item["state"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("url", item["html_url"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("head", item["head"]?.jsonObject?.get("ref")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("base", item["base"]?.jsonObject?.get("ref")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                })
                            }
                        })
                    }
                },
                onFailure = { githubError(it.message ?: "GitHub request failed") },
            )
        },
    ),
    Tool(
        name = "github_create_pr",
        description = "Create a pull request. This writes to GitHub and requires explicit user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", githubStringProperty("Repository owner"))
                    put("repo", githubStringProperty("Repository name"))
                    put("title", githubStringProperty("Pull request title"))
                    put("head", githubStringProperty("Source branch, optionally owner:branch"))
                    put("base", githubStringProperty("Target branch"))
                    put("body", githubStringProperty("Description in Markdown (optional)"))
                },
                required = listOf("owner", "repo", "title", "head", "base"),
            )
        },
        requiresUserApproval = true,
        execute = { args ->
            val owner = githubRepoPart(githubConnectorString(args, "owner"))
            val repo = githubRepoPart(githubConnectorString(args, "repo"))
            val title = githubConnectorString(args, "title")
            val head = githubConnectorString(args, "head")
            val base = githubConnectorString(args, "base")
            if (owner == null || repo == null || title == null || head == null || base == null) {
                return@Tool githubError("Missing or invalid pull request fields")
            }
            val body = githubConnectorString(args, "body").orEmpty()
            val payload = buildJsonObject {
                put("title", title)
                put("head", head)
                put("base", base)
                put("body", body)
            }.toString()
            githubApi(pat, "/repos/$owner/$repo/pulls", "POST", payload).fold(
                onSuccess = { response ->
                    val item = response.jsonObject
                    buildJsonObject {
                        put("number", item["number"]?.jsonPrimitiveOrNull?.intOrNull ?: 0)
                        put("url", item["html_url"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                        put("title", item["title"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                    }
                },
                onFailure = { githubError(it.message ?: "GitHub request failed") },
            )
        },
    ),
    Tool(
        name = "github_list_branches",
        description = "List branches in a GitHub repository. This is read-only.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", githubStringProperty("Repository owner"))
                    put("repo", githubStringProperty("Repository name"))
                    put("per_page", buildJsonObject { put("type", "integer"); put("description", "Maximum 100 results") })
                },
                required = listOf("owner", "repo"),
            )
        },
        execute = { args ->
            val owner = githubRepoPart(githubConnectorString(args, "owner"))
            val repo = githubRepoPart(githubConnectorString(args, "repo"))
            if (owner == null || repo == null) return@Tool githubError("Missing or invalid owner/repo")
            val perPage = githubConnectorInt(args, "per_page", GITHUB_CONNECTOR_MAX_PAGE)
            githubApi(pat, "/repos/$owner/$repo/branches?per_page=$perPage").fold(
                onSuccess = { response ->
                    buildJsonObject {
                        put("branches", buildJsonArray {
                            response.jsonArray.forEach { branch ->
                                val item = branch.jsonObject
                                add(buildJsonObject {
                                    put("name", item["name"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("protected", item["protected"]?.jsonPrimitiveOrNull?.contentOrNull == "true")
                                    put("sha", item["commit"]?.jsonObject?.get("sha")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                })
                            }
                        })
                    }
                },
                onFailure = { githubError(it.message ?: "GitHub request failed") },
            )
        },
    ),
    Tool(
        name = "github_list_commits",
        description = "List recent commits in a GitHub repository. This is read-only.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", githubStringProperty("Repository owner"))
                    put("repo", githubStringProperty("Repository name"))
                    put("per_page", buildJsonObject { put("type", "integer"); put("description", "Maximum 100 results") })
                },
                required = listOf("owner", "repo"),
            )
        },
        execute = { args ->
            val owner = githubRepoPart(githubConnectorString(args, "owner"))
            val repo = githubRepoPart(githubConnectorString(args, "repo"))
            if (owner == null || repo == null) return@Tool githubError("Missing or invalid owner/repo")
            val perPage = githubConnectorInt(args, "per_page", GITHUB_CONNECTOR_MAX_PAGE)
            githubApi(pat, "/repos/$owner/$repo/commits?per_page=$perPage").fold(
                onSuccess = { response ->
                    buildJsonObject {
                        put("commits", buildJsonArray {
                            response.jsonArray.forEach { commit ->
                                val item = commit.jsonObject
                                val details = item["commit"]?.jsonObject
                                add(buildJsonObject {
                                    put("sha", item["sha"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("message", details?.get("message")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("author", details?.get("author")?.jsonObject?.get("name")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                    put("url", item["html_url"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty())
                                })
                            }
                        })
                    }
                },
                onFailure = { githubError(it.message ?: "GitHub request failed") },
            )
        },
    ),
)
