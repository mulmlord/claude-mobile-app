package dev.mulm.claudemobile

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
private inline fun <T> JSONArray?.objs(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

data class GhRepo(
    val repo: String, val description: String, val private: Boolean, val archived: Boolean, val pushed: String,
    val prs: Int, val issues: Int, val ci: String?, val local: String?,
) {
    val owner get() = repo.substringBefore('/')
    val name get() = repo.substringAfter('/')
}

data class GhPr(
    val number: Int, val title: String, val author: String, val head: String, val base: String, val draft: Boolean,
    val review: String, val updated: String, val state: String, val checks: String?, val add: Int, val del: Int,
)

data class GhIssue(
    val number: Int, val title: String, val author: String, val labels: List<String>, val comments: Int,
    val updated: String, val state: String,
)

data class GhRun(
    val id: Long, val title: String, val workflow: String, val branch: String, val status: String,
    val conclusion: String, val created: String, val event: String, val url: String,
)

data class GhCheck(val name: String, val state: String, val workflow: String)
data class GhFile(val path: String, val add: Int, val del: Int, val lines: List<String>)
data class GhComment(val kind: String, val author: String, val body: String, val at: String, val state: String)

data class GhPrDetail(
    val number: Int, val title: String, val body: String, val author: String, val state: String, val draft: Boolean,
    val mergeable: String, val mergeState: String, val review: String, val head: String, val base: String,
    val url: String, val created: String, val add: Int, val del: Int, val changedFiles: Int,
    val checks: List<GhCheck>, val checksState: String?, val files: List<GhFile>, val diffError: String,
    val conversation: List<GhComment>, val local: String?,
)

data class GhIssueDetail(
    val number: Int, val title: String, val body: String, val author: String, val state: String,
    val labels: List<String>, val url: String, val created: String, val conversation: List<GhComment>, val local: String?,
)

data class GhCommit(val hash: String, val subject: String, val time: String, val author: String)
data class GhChange(val status: String, val path: String)

data class GhLocal(
    val cloned: Boolean, val path: String, val cwd: String, val branch: String, val upstream: String,
    val ahead: Int, val behind: Int, val changes: List<GhChange>, val changeCount: Int,
    val commits: List<GhCommit>, val suggested: String,
)

private fun comments(a: JSONArray?) = a.objs { GhComment(it.str("kind"), it.str("author"), it.str("body"), it.str("at"), it.str("state")) }

/** GitHub through the bridge: the laptop's `gh` does the work, the phone holds no token. */
object GitHub {
    private val bridge get() = App.instance.bridge
    private fun p(repo: String) = "/api/gh/$repo"

    suspend fun repos(fresh: Boolean = false): Pair<String, List<GhRepo>> {
        val o = bridge.api("/api/gh/repos" + if (fresh) "?fresh=1" else "")
        return o.str("login") to o.optJSONArray("repos").objs {
            GhRepo(it.str("repo"), it.str("description"), it.optBoolean("private"), it.optBoolean("archived"),
                it.str("pushed"), it.optInt("prs"), it.optInt("issues"), it.strOrNull("ci"), it.strOrNull("local"))
        }
    }

    suspend fun prs(repo: String): List<GhPr> = bridge.api(p(repo) + "/prs").optJSONArray("prs").objs {
        GhPr(it.optInt("number"), it.str("title"), it.str("author"), it.str("head"), it.str("base"),
            it.optBoolean("draft"), it.str("review"), it.str("updated"), it.str("state"), it.strOrNull("checks"),
            it.optInt("add"), it.optInt("del"))
    }

    suspend fun pr(repo: String, n: Int): GhPrDetail {
        val o = bridge.api(p(repo) + "/prs/$n")
        return GhPrDetail(
            o.optInt("number"), o.str("title"), o.str("body"), o.str("author"), o.str("state"), o.optBoolean("draft"),
            o.str("mergeable"), o.str("mergeState"), o.str("review"), o.str("head"), o.str("base"), o.str("url"),
            o.str("created"), o.optInt("add"), o.optInt("del"), o.optInt("changedFiles"),
            o.optJSONArray("checks").objs { GhCheck(it.str("name"), it.str("state"), it.str("workflow")) },
            o.strOrNull("checksState"),
            o.optJSONArray("files").objs { GhFile(it.str("path"), it.optInt("add"), it.optInt("del"), it.optJSONArray("lines").strings()) },
            o.str("diffError"), comments(o.optJSONArray("conversation")), o.strOrNull("local"),
        )
    }

    suspend fun merge(repo: String, n: Int, method: String, deleteBranch: Boolean) =
        bridge.api(p(repo) + "/prs/$n/merge", JSONObject().put("method", method).put("delete_branch", deleteBranch))

    suspend fun prComment(repo: String, n: Int, body: String) = bridge.api(p(repo) + "/prs/$n/comment", JSONObject().put("body", body))

    suspend fun review(repo: String, n: Int, event: String, body: String) =
        bridge.api(p(repo) + "/prs/$n/review", JSONObject().put("event", event).put("body", body))

    suspend fun prState(repo: String, n: Int, action: String) = bridge.api(p(repo) + "/prs/$n/state", JSONObject().put("action", action))

    suspend fun issues(repo: String): List<GhIssue> = bridge.api(p(repo) + "/issues").optJSONArray("issues").objs {
        GhIssue(it.optInt("number"), it.str("title"), it.str("author"), it.optJSONArray("labels").strings(),
            it.optInt("comments"), it.str("updated"), it.str("state"))
    }

    suspend fun issue(repo: String, n: Int): GhIssueDetail {
        val o = bridge.api(p(repo) + "/issues/$n")
        return GhIssueDetail(o.optInt("number"), o.str("title"), o.str("body"), o.str("author"), o.str("state"),
            o.optJSONArray("labels").strings(), o.str("url"), o.str("created"), comments(o.optJSONArray("conversation")),
            o.strOrNull("local"))
    }

    /** Returns the new issue's number. */
    suspend fun createIssue(repo: String, title: String, body: String): Int? {
        val o = bridge.api(p(repo) + "/issues", JSONObject().put("title", title).put("body", body))
        return if (o.isNull("number")) null else o.optInt("number")
    }

    suspend fun issueComment(repo: String, n: Int, body: String) = bridge.api(p(repo) + "/issues/$n/comment", JSONObject().put("body", body))

    suspend fun issueState(repo: String, n: Int, action: String) = bridge.api(p(repo) + "/issues/$n/state", JSONObject().put("action", action))

    suspend fun runs(repo: String): List<GhRun> = bridge.api(p(repo) + "/runs").optJSONArray("runs").objs {
        GhRun(it.optLong("id"), it.str("title"), it.str("workflow"), it.str("branch"), it.str("status"),
            it.str("conclusion"), it.str("created"), it.str("event"), it.str("url"))
    }

    suspend fun rerun(repo: String, id: Long) = bridge.api(p(repo) + "/runs/$id/rerun", JSONObject())

    suspend fun runLog(repo: String, id: Long): String = bridge.api(p(repo) + "/runs/$id/log").str("log")

    suspend fun local(repo: String): GhLocal {
        val o = bridge.api(p(repo) + "/local")
        return GhLocal(
            o.optBoolean("cloned"), o.str("path"), o.str("cwd"), o.str("branch"), o.str("upstream"),
            o.optInt("ahead"), o.optInt("behind"),
            o.optJSONArray("changes").objs { GhChange(it.str("status"), it.str("path")) }, o.optInt("changeCount"),
            o.optJSONArray("commits").objs { GhCommit(it.str("hash"), it.str("subject"), it.str("when"), it.str("author")) },
            o.str("suggested"),
        )
    }

    /** pull | push | pr | clone; returns gh/git's message. */
    suspend fun localAction(repo: String, action: String): String =
        bridge.api(p(repo) + "/local/$action", JSONObject()).str("message")
}
