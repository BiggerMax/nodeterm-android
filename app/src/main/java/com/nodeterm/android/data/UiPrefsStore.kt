package com.nodeterm.android.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local UI preferences that survive re-pairing: which nodes the user swiped away in the Nodes
 * list, which "Needs you" events they dismissed with Clear all, and the custom per-project node
 * order they set by long-press drag. Kept in their OWN prefs file so SessionStore's `clear()`
 * (unpair wipes the "session" prefs) does not reset them.
 */
class UiPrefsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** nodeIds the user swiped away in the Nodes list (hidden on this device only). */
    var dismissedNodes: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED_NODES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DISMISSED_NODES, value).apply()

    /** Inbox event ids dismissed via "Clear all" on the Needs-you tab. */
    var dismissedInbox: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED_INBOX, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DISMISSED_INBOX, value).apply()

    /** projectName → the user's long-press drag order of nodeIds. Empty = default sort. */
    var nodeOrder: Map<String, List<String>>
        get() = prefs.getString(KEY_NODE_ORDER, null)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, List<String>>>(raw) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        set(value) = prefs.edit().putString(KEY_NODE_ORDER, json.encodeToString(value)).apply()

    private companion object {
        const val KEY_DISMISSED_NODES = "dismissedNodes"
        const val KEY_DISMISSED_INBOX = "dismissedInbox"
        const val KEY_NODE_ORDER = "nodeOrder"
    }
}
