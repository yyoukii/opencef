package com.opencef.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EventBusTest {

    @Test
    fun `listener receives event`() {
        val bus = EventBus()
        var received: String? = null

        bus.on("login") { payload -> received = payload }
        bus.onMessage("login", "hello")

        assertEquals("hello", received)
    }

    @Test
    fun `multiple listeners receive event`() {
        val bus = EventBus()
        var firstReceived: String? = null
        var secondReceived: String? = null

        bus.on("login") { payload -> firstReceived = payload }
        bus.on("login") { payload -> secondReceived = payload }
        bus.onMessage("login", "hello")

        assertEquals("hello", firstReceived)
        assertEquals("hello", secondReceived)
    }

    @Test
    fun `off removes listener`() {
        val bus = EventBus()
        var callCount = 0
        val listener: (String) -> Unit = { callCount++ }

        bus.on("login", listener)
        bus.onMessage("login", "hello")
        bus.off("login", listener)
        bus.onMessage("login", "hello")

        assertEquals(1, callCount)
    }

    @Test
    fun `unknown event does not crash`() {
        val bus = EventBus()

        bus.onMessage("unknown", "hello")
    }

    @Test
    fun `clear removes all listeners across all events`() {
        val bus = EventBus()
        var loginCalls = 0
        var loadedCalls = 0

        bus.on("login") { loginCalls++ }
        bus.on("loaded") { loadedCalls++ }
        bus.clear()
        bus.onMessage("login", "hello")
        bus.onMessage("loaded", "hello")

        assertEquals(0, loginCalls)
        assertEquals(0, loadedCalls)
    }

    @Test
    fun `listener registered after clear still works`() {
        val bus = EventBus()
        var calls = 0

        bus.on("login") { calls++ }
        bus.clear()
        bus.on("login") { calls++ }
        bus.onMessage("login", "hello")

        assertEquals(1, calls)
    }

    @Test
    fun `concurrent access does not corrupt internal state`() {
        val bus = EventBus()
        val threadCount = 16
        val iterationsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                startLatch.await()
                val listener: (String) -> Unit = {}
                repeat(iterationsPerThread) {
                    bus.on("event", listener)
                    bus.onMessage("event", "payload")
                    bus.off("event", listener)
                }
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        val finishedInTime = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(finishedInTime)

        var finalCallCount = 0
        bus.on("final-check") { finalCallCount++ }
        bus.onMessage("final-check", "payload")
        assertEquals(1, finalCallCount)
    }
}
