package com.nodeterm.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

// -----------------------------------------------------------------------------------------------
// Pairing offer (`nodeterm://pair?code=…`) — mirrors src/main/remote/pairing.ts
// -----------------------------------------------------------------------------------------------

data class PairingOffer(
    val relayEndpoint: String,
    val pairingToken: String,
    val hostPublicKeyB64: String
)

object PairingCodec {
    private const val SCHEME_PREFIX = "nodeterm://pair?code="

    /** Decode a full `nodeterm://pair?code=…` URL or a bare base64url code. Null on any bad input. */
    fun decodeOffer(code: String): PairingOffer? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        val raw = extractCode(trimmed) ?: return null
        return try {
            val jsonText = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
            val obj = Json.parseToJsonElement(jsonText).jsonObject
            val offer = PairingOffer(
                relayEndpoint = obj["relayEndpoint"]?.jsonPrimitive?.contentOrNull ?: return null,
                pairingToken = obj["pairingToken"]?.jsonPrimitive?.contentOrNull ?: return null,
                hostPublicKeyB64 = obj["hostPublicKeyB64"]?.jsonPrimitive?.contentOrNull ?: return null
            )
            if (offer.pairingToken.isEmpty() || offer.hostPublicKeyB64.isEmpty()) return null
            if (!isAllowedRelayEndpoint(offer.relayEndpoint)) return null
            offer
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCode(input: String): String? {
        if (input.contains("://")) {
            val uri = try {
                URI(input)
            } catch (_: Exception) {
                return null
            }
            if (uri.scheme != "nodeterm" || uri.host != "pair") return null
            if (uri.path != "" && uri.path != "/") return null
            val query = uri.rawQuery ?: return null
            val pair = query.split("&").firstOrNull { it.startsWith("code=") } ?: return null
            return URLDecoder.decode(pair.substringAfter("="), Charsets.UTF_8.name())
        }
        return input
    }

    /**
     * R5: the client connects to relayEndpoint verbatim, so an attacker-crafted offer must not be
     * able to point it at a plaintext (or non-WebSocket) endpoint. TLS required; plaintext `ws://`
     * is allowed ONLY to loopback (local relay in dev / e2e tests).
     */
    private fun isAllowedRelayEndpoint(endpoint: String): Boolean {
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            return false
        }
        return when (uri.scheme) {
            "wss" -> true
            "ws" -> uri.host == "127.0.0.1" || uri.host == "localhost" || uri.host == "::1"
            else -> false
        }
    }
}

// -----------------------------------------------------------------------------------------------
// Workspace / canvas — the host's `projects.list` workspace section + `canvas:state` notify.
// Field names must match the host byte-for-byte; tolerant of unknown keys and absent fields.
// -----------------------------------------------------------------------------------------------

// -----------------------------------------------------------------------------------------------
// JSON decode helpers for the shapes the host serves over the wire (tolerant of unknown keys).
// -----------------------------------------------------------------------------------------------

@Serializable
data class CanvasState(val nodes: List<CanvasNode> = emptyList())

object JsonModels {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** Parse the workspace section of the projects blob (or a full workspace.json). */
    fun workspace(text: String): Workspace? = try {
        json.decodeFromString(Workspace.serializer(), text)
    } catch (_: Exception) {
        null
    }

    /** Parse the agent-status.json section of the projects blob. */
    fun mirror(text: String): MirrorFile? = try {
        json.decodeFromString(MirrorFile.serializer(), text)
    } catch (_: Exception) {
        null
    }

    /** Parse a `canvas:state` notification body ({nodes: [...]}). */
    fun canvasState(text: String): CanvasState? = try {
        json.decodeFromString(CanvasState.serializer(), text)
    } catch (_: Exception) {
        null
    }
}

@Serializable
data class Workspace(
    val version: Int = 2,
    val activeProjectId: String = "",
    val projects: List<Project> = emptyList()
)

@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val color: String = "",
    val cwd: String? = null,
    val viewport: Viewport = Viewport(),
    val nodes: List<CanvasNode> = emptyList(),
    /** Trello-style kanban task board — shared via `.nodeterm/project.json` like nodes. */
    val kanban: KanbanProject? = null
)

// -----------------------------------------------------------------------------------------------
// Kanban task board — mirrors the desktop's `Project.kanban` (shared via project.json).
// The board stores only each session card's column assignment; per-card metadata (assignees /
// priority / labels / due date) rides in `meta`. Absent/malformed boards degrade to the default.
// -----------------------------------------------------------------------------------------------

/** One kanban column. Order in `columns` = display order. */
@Serializable
data class KanbanColumn(
    val id: String = "",
    val title: String = "",
    val color: String = ""
)

/** Assignment of one session node to a board column; a session without one sits in Ungrouped. */
@Serializable
data class KanbanAssignment(
    val nodeId: String = "",
    val columnId: String = ""
)

/** A board-level (Notion-style) label; cards reference these by id in `meta[].labels`. */
@Serializable
data class KanbanLabel(
    val id: String = "",
    val name: String = "",
    val color: String = "default"
)

/** Who produced a board-log entry / is assigned to a card (presence identity). */
@Serializable
data class BoardLogAuthor(
    val name: String = "",
    val color: String = ""
)

/** Trello-style per-card metadata (absent fields = no metadata). */
@Serializable
data class KanbanCardMeta(
    val nodeId: String = "",
    val assignees: List<BoardLogAuthor> = emptyList(),
    /** Due timestamp (ms). Absent = no due date. */
    val dueAt: Long? = null,
    /** low | medium | high | urgent. Absent = no priority. */
    val priority: String? = null,
    /** Ids of the board labels applied to this card (`labels`). */
    val labels: List<String> = emptyList()
)

/** Per-project kanban board. Absent = the project has never been edited; readers show a default. */
@Serializable
data class KanbanProject(
    val columns: List<KanbanColumn> = emptyList(),
    val assignments: List<KanbanAssignment> = emptyList(),
    val meta: List<KanbanCardMeta> = emptyList(),
    val labels: List<KanbanLabel> = emptyList()
)


@Serializable
data class Viewport(val x: Double = 0.0, val y: Double = 0.0, val zoom: Double = 1.0)

@Serializable
data class CanvasNode(
    val id: String = "",
    val kind: String = "terminal",
    val position: Position = Position(),
    val size: Size = Size(),
    val title: String = "",
    val color: String = "",
    val group: String? = null,
    val tags: List<String> = emptyList(),
    val cwd: String? = null,
    val agentId: String? = null,
    val parentId: String? = null
)

@Serializable
data class Position(val x: Double = 0.0, val y: Double = 0.0)

@Serializable
data class Size(val width: Double = 0.0, val height: Double = 0.0)

// -----------------------------------------------------------------------------------------------
// Agent status mirror — the host's <userData>/agent-status.json section of the projects blob.
// Mirrors MirrorFile in src/core/agent-status-mirror.ts (the fields the phone renders).
// -----------------------------------------------------------------------------------------------

/** Render-side bucket for a node's badge; mirror states collapse like the reference (see below). */
enum class NodeStatus(val badge: String) {
    WORKING("RUNNING"),
    NEEDS_YOU("NEEDS YOU"),
    DONE("DONE"),
    IDLE("IDLE");

    companion object {
        /** waiting/blocked collapse to needs-you, exactly like agent-status-mirror's live-update mapping. */
        fun fromMirrorState(state: String?): NodeStatus = when (state) {
            "working" -> WORKING
            "waiting", "blocked" -> NEEDS_YOU
            "done" -> DONE
            else -> IDLE
        }
    }
}

@Serializable
data class MirrorFile(
    val v: Int = 1,
    val updatedAt: Long = 0,
    val nodes: Map<String, MirrorNode> = emptyMap(),
    val settings: JsonElement? = null,
    val usage: JsonElement? = null,
    /** This host's Server-Edition install metadata (host-local, dropped from SSH slices). */
    val server: JsonElement? = null,
    /**
     * Raw inbox block (host: agent-status-mirror.ts). CURRENT hosts publish the object shape
     * `{events: [...], nodes: {nodeId: InboxNodeNow}}`; OLD hosts published a bare event array
     * `[...]`. Kept as a raw [JsonElement] and decoded lazily via [inboxEvents] / [nodeNow] so a
     * SINGLE model tolerates both wire shapes — a shape mismatch must never null the whole mirror
     * (which would silently drop every status badge).
     */
    val inbox: JsonElement? = null
) {
    /** The inbox event feed, whichever wire shape the host wrote. */
    fun inboxEvents(): List<InboxEvent> {
        val events = when (val el = inbox) {
            is JsonArray -> el
            is JsonObject -> el["events"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return runCatching {
            JsonModels.json.decodeFromJsonElement(ListSerializer(InboxEvent.serializer()), events)
        }.getOrDefault(emptyList())
    }

    /**
     * Per-node "now" map (activity / context meter). Empty when the host sent a bare array.
     * Decoded per-entry so one malformed node (e.g. a float contextPercent) never drops every
     * other node's meter.
     */
    fun nodeNow(): Map<String, InboxNodeNow> {
        val obj = inbox as? JsonObject ?: return emptyMap()
        val nodes = obj["nodes"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, InboxNodeNow>()
        for ((id, v) in nodes) {
            runCatching {
                JsonModels.json.decodeFromJsonElement(InboxNodeNow.serializer(), v)
            }.getOrNull()?.let { out[id] = it }
        }
        return out
    }
}

@Serializable
data class MirrorNode(
    /** working | waiting | blocked | done; absent = idle/unknown. */
    val state: String? = null,
    val agentId: String? = null,
    val sessionId: String? = null,
    /** The agent's own session name (`/rename`), published by the session-name sweep. */
    val name: String? = null,
    val updatedAt: Long = 0
)

@Serializable
data class InboxEvent(
    val id: String = "",
    val ts: Long = 0,
    val nodeId: String = "",
    val agentId: String? = null,
    val sessionId: String? = null,
    /** approval | question | done */
    val kind: String = "done",
    /** First line, ≤120 chars. */
    val title: String = "",
    /** ≤240 chars — lastMessage snippet. */
    val detail: String? = null,
    val interrupted: Boolean? = null,
    /** approval only: the deterministic hook-reply ticket for a held permission hook. */
    val pendingId: String? = null,
    /** question only: the AskUserQuestion choices (≤4 × ≤60) for option buttons. */
    val options: List<String>? = null,
    val multiSelect: Boolean? = null
)

/**
 * Per-node "what it's doing right now" + context-window fill — mirrors InboxNodeNow in the host's
 * agent-status-mirror.ts (`inbox.nodes[nodeId]`). This is the phone's context meter source: the
 * desktop shows a per-node context meter, and `contextPercent` is exactly that number (0–100).
 */
@Serializable
data class InboxNodeNow(
    /** ≤80 chars — "Editing foo.ts", "Running npm test", "Reading bar.ts". */
    val activity: String? = null,
    /** Raw tool name the activity came from. */
    val tool: String? = null,
    /** Context-window fill 0–100 (from context-tail), when known. */
    val contextPercent: Int? = null,
    /** First line of the user prompt that opened the CURRENT turn ("You: …"). */
    val prompt: String? = null,
    val updatedAt: Long = 0
)

// -----------------------------------------------------------------------------------------------
// The `projects.list` marker-delimited blob — mirrors listProjectsOutput() in src/main/index.ts.
//   `${workspace}\n--NT-PROJECTS-SPLIT--\n${sessions}\n--NT-STATUS-SPLIT--\n${agent-status}`
// -----------------------------------------------------------------------------------------------

object ProjectsBlob {
    const val PROJECTS_MARK = "--NT-PROJECTS-SPLIT--"
    const val STATUS_MARK = "--NT-STATUS-SPLIT--"

    data class Parsed(
        val workspaceJson: String,
        val tmuxSessions: List<String>,
        val statusJson: String
    )

    fun parse(output: String): Parsed {
        val parts = output.split("\n$PROJECTS_MARK\n")
        val workspaceJson = parts.getOrElse(0) { "" }
        val rest = parts.getOrElse(1) { "" }
        val parts2 = rest.split("\n$STATUS_MARK\n")
        val sessions = parts2.getOrElse(0) { "" }.lines().filter { it.isNotBlank() }
        val statusJson = parts2.getOrElse(1) { "" }
        return Parsed(workspaceJson, sessions, statusJson)
    }
}
