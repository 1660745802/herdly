package dev.herdwatch.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * One machine, two routes. The fallback exists so a phone at home can use the LAN address and
 * still work outside, without keeping two host entries and two copies of the password.
 */
class HostEndpointTest {
    private fun config(
        host: String = "192.168.31.181",
        port: Int = 22,
        fallbackHost: String = "",
        fallbackPort: Int = 0,
    ) = HostConfig(
        id = "h_1", label = "build", host = host, port = port, user = "me",
        fallbackHost = fallbackHost, fallbackPort = fallbackPort,
    )

    @Test
    fun `a host without a fallback has exactly one address`() {
        val endpoints = config().endpoints
        assertEquals(1, endpoints.size)
        assertEquals(HostEndpoint("192.168.31.181", 22), endpoints.single())
        assertFalse(config().hasFallback)
    }

    @Test
    fun `fallback port zero reuses the primary port`() {
        val endpoints = config(port = 2222, fallbackHost = "example.com").endpoints
        assertEquals(
            listOf(HostEndpoint("192.168.31.181", 2222), HostEndpoint("example.com", 2222)),
            endpoints,
        )
    }

    @Test
    fun `fallback port overrides the primary port`() {
        val endpoints = config(port = 2222, fallbackHost = "example.com", fallbackPort = 2200).endpoints
        assertEquals(
            listOf(HostEndpoint("192.168.31.181", 2222), HostEndpoint("example.com", 2200)),
            endpoints,
        )
    }

    @Test
    fun `blank fallback host with a port is still no fallback`() {
        // The port field only means something next to an address; a leftover port must not
        // silently become a second "host:port" with an empty host.
        val endpoints = config(fallbackHost = "   ", fallbackPort = 2200).endpoints
        assertEquals(1, endpoints.size)
        assertFalse(config(fallbackHost = "   ").hasFallback)
    }

    @Test
    fun `the same address typed twice is dialed once`() {
        // Two identical routes fail for the same reason; dialing both only doubles the wait before
        // the user is told the machine is unreachable.
        assertEquals(1, config(fallbackHost = "192.168.31.181").endpoints.size)
        assertEquals(
            1,
            config(port = 2222, fallbackHost = " 192.168.31.181 ", fallbackPort = 2222).endpoints.size,
        )
        assertEquals(
            2,
            config(port = 2222, fallbackHost = "192.168.31.181", fallbackPort = 2200).endpoints.size,
        )
    }

    @Test
    fun `address text drops the default port`() {
        assertEquals("host", HostEndpoint("host", 22).toString())
        assertEquals("host:2222", HostEndpoint("host", 2222).toString())
        assertNotEquals(HostEndpoint("host", 22), HostEndpoint("host", 2222))
    }
}
