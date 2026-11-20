package dev.herdwatch.ui

import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus

private val inboxStatusOrder = listOf(
    AgentStatus.WAITING,
    AgentStatus.UNKNOWN,
    AgentStatus.REVIEW,
    AgentStatus.WORKING,
    AgentStatus.IDLE,
)
internal val attentionStatuses = setOf(AgentStatus.WAITING, AgentStatus.UNKNOWN, AgentStatus.REVIEW)

/** Where a status sits in the queue, so any list can name the most urgent thing it holds. */
internal fun urgencyOf(status: AgentStatus): Int =
    inboxStatusOrder.indexOf(status).let { if (it < 0) inboxStatusOrder.size else it }

/** One definition shared by the header summary and the ordering of the agent section. */
internal fun isAttention(agent: Agent): Boolean = agent.status in attentionStatuses

/**
 * `done` means "finished while you were looking elsewhere". Once the result has been on screen it
 * is idle — the same rule the official client applies with its `herdr.seen` label. Without this a
 * finished agent claimed attention forever, and outranked agents that were actually working.
 */
internal fun Agent.markSeen(marks: Map<String, Long>): Agent {
    if (status != AgentStatus.REVIEW) return this
    val seen = marks[key] ?: return this
    if (completionSeq <= 0L || seen < completionSeq) return this
    return copy(status = AgentStatus.IDLE)
}

internal fun List<Agent>.markSeen(marks: Map<String, Long>): List<Agent> =
    if (marks.isEmpty()) this else map { it.markSeen(marks) }

private val inboxOrder = compareBy<Agent> { inboxStatusOrder.indexOf(it.status) }
    .thenBy { if (it.status == AgentStatus.WAITING) it.sinceMillis else -it.stateChangeSeq }
    .thenBy { it.name.lowercase() }

/** The visible agent section and the detail traversal must describe the same queue. */
internal fun inboxQueue(agents: List<Agent>, query: String): List<Agent> =
    agents.filter { agent ->
        val matchesQuery = query.isBlank() || listOf(
            agent.name,
            agent.repo,
            agent.hostLabel,
            agent.kind,
            agent.paneId,
            agent.workspaceLabel.orEmpty(),
            agent.tabLabel.orEmpty(),
            agent.cwd.orEmpty(),
        ).any { it.contains(query, ignoreCase = true) }
        matchesQuery
    }.sortedWith(inboxOrder)
