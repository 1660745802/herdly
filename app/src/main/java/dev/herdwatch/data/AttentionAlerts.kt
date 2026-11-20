package dev.herdwatch.data

import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus

/**
 * Which state changes deserve a notification.
 *
 * The rule is the official Herdr client's, and it is deliberately asymmetric: being blocked always
 * matters, finishing only matters if the person was not already looking at that agent. Alerting on
 * every finish turns a phone into a slot machine; alerting on none makes a background connection
 * pointless.
 *
 * Only transitions we actually observed count. The first snapshot after a (re)connect is seeded
 * silently — otherwise every reconnect would announce five agents that were blocked all along.
 */
internal class AttentionAlerts {
    data class Alert(
        val agentKey: String,
        val needsYou: Boolean,
        val name: String,
        val hostLabel: String,
        val paneId: String,
    )

    private var previous: Map<String, AgentStatus> = emptyMap()
    private var seeded = false

    fun transitions(
        agents: List<Agent>,
        watching: (String) -> Boolean = { false },
        foreground: Boolean = false,
    ): List<Alert> {
        val observed = agents.associate { it.key to it.status }
        if (!seeded) {
            seeded = true
            previous = observed
            return emptyList()
        }
        val alerts = ArrayList<Alert>()
        for (agent in agents) {
            // An agent we have no history for is either brand new or came back with a reconnect.
            // Saying "needs you" for it would be a guess dressed up as news.
            val was = previous[agent.key] ?: continue
            if (was == agent.status) continue
            val needsYou = agent.status == AgentStatus.WAITING || agent.status == AgentStatus.UNKNOWN
            val blockedNow = needsYou && was != AgentStatus.WAITING && was != AgentStatus.UNKNOWN
            val finishedNow = was == AgentStatus.WORKING && agent.status == AgentStatus.REVIEW
            if (blockedNow || (finishedNow && !(foreground && watching(agent.key)))) {
                alerts += Alert(
                    agentKey = agent.key,
                    needsYou = blockedNow,
                    name = agent.name,
                    hostLabel = agent.hostLabel,
                    paneId = agent.paneId,
                )
            }
        }
        previous = observed
        return alerts
    }

    /** Forget history, e.g. when the last host is removed. */
    fun reset() {
        previous = emptyMap()
        seeded = false
    }
}
