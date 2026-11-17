package dev.herdwatch

import dev.herdwatch.model.AgentStatus
import dev.herdwatch.ssh.BridgeCodec
import dev.herdwatch.ssh.HerdrRpcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCodecTest {

    @Test
    fun `parses snapshot agents and workspace labels`() {
        val line = """
            {"id":"7","result":{"type":"session_snapshot","snapshot":{"version":"0.9.5","protocol":22,
            "workspaces":[{"workspace_id":"w1","label":"herdr-mobile"}],
            "agents":[
              {"pane_id":"w1:p1","name":"fix/login","display_agent":"claude","agent_status":"blocked",
               "state_change_seq":4412,"completion_seq":3,"cwd":"/repo/herdr-mobile","workspace_id":"w1","focused":true},
              {"pane_id":"w1:p2","terminal_title":"zsh","agent_status":"idle","state_change_seq":2,"cwd":"/repo/gateway"}
            ]}}}
        """.trimIndent()

        val result = BridgeCodec.resultOf(line, "7")
        val snapshot = BridgeCodec.parseSnapshot(result, "h1", "build-01")
        assertEquals(2, snapshot.agents.size)
        assertEquals(22, snapshot.protocol)
        val first = snapshot.agents.first()
        assertEquals("fix/login", first.name)
        assertEquals("claude", first.kind)
        assertEquals(AgentStatus.WAITING, first.status)
        assertEquals("herdr-mobile", first.workspaceLabel)
        assertEquals("herdr-mobile", first.repo)
        assertEquals(4412L, first.stateChangeSeq)
        val second = snapshot.agents[1]
        assertEquals(AgentStatus.IDLE, second.status)
        assertEquals("w1:p2", second.paneId)
    }

    @Test
    fun `parses workspace tab pane structure`() {
        val line = """{"id":"8","result":{"type":"session_snapshot","snapshot":{"version":"0.9.5","protocol":22,
          "workspaces":[{"workspace_id":"w1","number":1,"label":"herdr-mobile","focused":true,"pane_count":2,"tab_count":1,"active_tab_id":"w1:t1","agent_status":"blocked"}],
          "tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"main","focused":true,"pane_count":2,"agent_status":"blocked"}],
          "panes":[
            {"pane_id":"w1:p1","terminal_id":"t1","workspace_id":"w1","tab_id":"w1:t1","title":"claude","display_agent":"claude","agent_status":"blocked","revision":41,"cwd":"/repo/a","focused":true},
            {"pane_id":"w1:p2","terminal_id":"t2","workspace_id":"w1","tab_id":"w1:t1","title":"zsh","agent_status":"unknown","revision":3,"cwd":"/repo/b","restore_error":"resume failed"}
          ],"agents":[]}}}"""
        val snapshot = BridgeCodec.parseSnapshot(BridgeCodec.resultOf(line, "8"), "h1", "build-01")
        assertEquals(1, snapshot.structure.workspaces.size)
        assertEquals("herdr-mobile", snapshot.structure.workspaces.first().label)
        assertEquals(2, snapshot.structure.panes.size)
        val shell = snapshot.structure.panes[1]
        assertEquals(AgentStatus.UNKNOWN, shell.status)
        assertEquals("resume failed", shell.restoreError)
        assertEquals("/repo/b", shell.cwd)
    }

    @Test
    fun `parses pane read result`() {
        val line = """{"id":"1","result":{"type":"pane_read","read":{"pane_id":"w1:p1","text":"hello\n[ y / n ]","revision":12,"truncated":true}}}"""
        val pane = BridgeCodec.parsePaneRead(BridgeCodec.resultOf(line, "1"))
        assertTrue(pane.text.contains("hello"))
        assertEquals(12L, pane.revision)
        assertTrue(pane.truncated)
    }

    @Test
    fun `surfaces rpc errors`() {
        val line = """{"id":"1","error":{"code":"invalid_params","message":"missing field pane_id"}}"""
        val e = assertThrows(HerdrRpcException::class.java) { BridgeCodec.resultOf(line, "1") }
        assertTrue(e.message!!.contains("missing field pane_id"))
        assertEquals("invalid_params", e.code)
    }
}
