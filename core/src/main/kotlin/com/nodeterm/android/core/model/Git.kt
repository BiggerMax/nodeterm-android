package com.nodeterm.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

// -----------------------------------------------------------------------------------------------
// Git — the host's `git.status` / `git.diff` RPC responses (src/core/git-service.ts,
// src/shared/types.ts GitStatus / GitFileChange). Only the read-only surface the phone renders.
// -----------------------------------------------------------------------------------------------

/** One entry of `GitStatus.staged` / `GitStatus.changes`. */
@Serializable
data class GitFileChange(
    val path: String = "",
    /** Single-letter status: M (modified), A (added), D (deleted), R (renamed), U (untracked). */
    val status: String = "M",
    val added: Int = 0,
    val deleted: Int = 0
) {
    val isUntracked: Boolean get() = status == "U"
}

/** The host's `git.status(cwd)` response body. */
@Serializable
data class GitStatus(
    val hasRepo: Boolean = false,
    val repoName: String = "",
    val branch: String = "",
    val branches: List<String> = emptyList(),
    val remoteBranches: List<String> = emptyList(),
    val ahead: Int = 0,
    val behind: Int = 0,
    val hasRemote: Boolean = false,
    val hasOrigin: Boolean = false,
    val hasUpstream: Boolean = false,
    val ghAvailable: Boolean = false,
    val ghAuthed: Boolean = false,
    val staged: List<GitFileChange> = emptyList(),
    val changes: List<GitFileChange> = emptyList()
) {
    /** Dirty files = staged + unstaged (untracked appear in changes with status U). */
    val dirtyCount: Int get() = staged.size + changes.size
}

/** Tolerant `git.status` response parsers (never throw; degrade to empty/null on bad input). */
object GitModels {
    /** Reuses the project-wide tolerant Json config from Models.kt. */
    private val json get() = JsonModels.json

    /** Parse a `git.status` response body string. Null on malformed JSON. */
    fun gitStatus(text: String): GitStatus? = try {
        json.decodeFromString(GitStatus.serializer(), text)
    } catch (_: Exception) {
        null
    }

    /** Parse a `git.status` response body already decoded to a JsonObject. */
    fun gitStatus(obj: JsonElement): GitStatus? = try {
        val o = obj.jsonObject
        GitStatus(
            hasRepo = boolOf(o, "hasRepo"),
            repoName = strOf(o, "repoName"),
            branch = strOf(o, "branch"),
            branches = strListOf(o, "branches"),
            remoteBranches = strListOf(o, "remoteBranches"),
            ahead = intOf(o, "ahead"),
            behind = intOf(o, "behind"),
            hasRemote = boolOf(o, "hasRemote"),
            hasOrigin = boolOf(o, "hasOrigin"),
            hasUpstream = boolOf(o, "hasUpstream"),
            ghAvailable = boolOf(o, "ghAvailable"),
            ghAuthed = boolOf(o, "ghAuthed"),
            staged = changeListOf(o["staged"]),
            changes = changeListOf(o["changes"])
        )
    } catch (_: Exception) {
        null
    }

    private fun strOf(o: kotlinx.serialization.json.JsonObject, key: String): String =
        o[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun boolOf(o: kotlinx.serialization.json.JsonObject, key: String): Boolean =
        o[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun intOf(o: kotlinx.serialization.json.JsonObject, key: String): Int =
        o[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    private fun strListOf(o: kotlinx.serialization.json.JsonObject, key: String): List<String> =
        o[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun changeListOf(el: JsonElement?): List<GitFileChange> =
        el?.jsonArray?.mapNotNull { item ->
            val o = item.jsonObject
            val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            GitFileChange(
                path = path,
                status = o["status"]?.jsonPrimitive?.contentOrNull ?: "M",
                added = o["added"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                deleted = o["deleted"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            )
        } ?: emptyList()
}
