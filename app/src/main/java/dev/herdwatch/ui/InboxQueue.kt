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

/** One definition shared by the header summary, the attention filter, and the duty card. */
internal fun isAttention(agent: Agent): Boolean = agent.status in attentionStatuses
private val inboxOrder = compareBy<Agent> { inboxStatusOrder.indexOf(it.status) }
    .thenBy { if (it.status == AgentStatus.WAITING) it.sinceMillis else -it.stateChangeSeq }
    .thenBy { it.name.lowercase() }

/** The visible inbox and detail traversal must describe the same queue. */
internal fun inboxQueue(agents: List<Agent>, filter: InboxFilter, query: String): List<Agent> =
    agents.filter { agent ->
        val matchesFilter = query.isNotBlank() || filter == InboxFilter.ALL || isAttention(agent)
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
        matchesFilter && matchesQuery
    }.sortedWith(inboxOrder)
