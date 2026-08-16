package com.nodeterm.android.ui

import com.nodeterm.android.core.model.NodeStatus

/**
 * Pure list-ordering logic for the Nodes tab — no Android dependencies, unit-tested in
 * `NodeListOrderTest`. Hides swipe-deleted nodes, applies the user's long-press drag order
 * per project group, and falls back to the default status/title sort.
 */

/**
 * Order the flat node list for display:
 *  - [dismissedNodeIds] are filtered out (local swipe-delete — the host still runs them);
 *  - per project, nodes listed in [nodeOrder] keep that exact order and come first;
 *  - nodes outside the custom order (host added them later) keep the default sort at the tail
 *    of their group;
 *  - projects with no custom order use the default sort wholesale.
 */
internal fun orderNodes(
    nodes: List<NodeRow>,
    status: Map<String, NodeStatus>,
    dismissedNodeIds: Set<String>,
    nodeOrder: Map<String, List<String>>
): List<NodeRow> {
    val visible = nodes.filterNot { it.nodeId in dismissedNodeIds }
    if (nodeOrder.isEmpty()) return defaultSortNodes(visible, status)
    val out = mutableListOf<NodeRow>()
    visible.groupBy { it.projectName }.forEach { (projectName, group) ->
        val order = nodeOrder[projectName]
        if (order.isNullOrEmpty()) {
            out += defaultSortNodes(group, status)
        } else {
            val byId = group.associateBy { it.nodeId }
            out += order.mapNotNull { byId[it] }
            out += defaultSortNodes(group.filterNot { it.nodeId in order }, status)
        }
    }
    return out
}

/** Default node sort: status first (needs-you → working → done → idle), then title. */
internal fun defaultSortNodes(
    nodes: List<NodeRow>,
    status: Map<String, NodeStatus>
): List<NodeRow> =
    nodes.sortedWith(compareBy({ statusRank(it, status) }, { it.title.lowercase() }))

internal fun statusRank(node: NodeRow, status: Map<String, NodeStatus>): Int = when (status[node.nodeId]) {
    NodeStatus.NEEDS_YOU -> 0
    NodeStatus.WORKING -> 1
    NodeStatus.DONE -> 2
    else -> 3
}
