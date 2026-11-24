package dev.minios.tgwsproxy.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpstreamSetupTest {
    @Test fun deadlineClosesAStuckStepAndReportsATimeout() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { socket ->
                listener.accept().use {
                    val started = System.nanoTime()
                    try {
                        // The peer never answers: the read would block forever without the deadline.
                        SetupDeadline.bounded(200, close = { socket.close() }) { socket.getInputStream().read() }
                        fail("expected a timeout")
                    } catch (e: SocketTimeoutException) {
                        assertTrue(socket.isClosed)
                    }
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
                }
            }
        }
    }

    @Test fun deadlineLeavesAFastStepAlone() {
        var closed = false
        assertEquals(42, SetupDeadline.bounded(5_000, close = { closed = true }) { 42 })
        Thread.sleep(50)
        assertFalse(closed)
        // Errors of the step itself pass through unchanged.
        val error = IOException("refused")
        try {
            SetupDeadline.bounded(5_000, close = { closed = true }) { throw error }
        } catch (e: IOException) {
            assertSame(error, e)
        }
        assertFalse(closed)
    }

    @Test fun cancelledGuardClosesItsSocketsAndRefusesNewOnes() {
        val guard = UpstreamSetupGuard()
        val first = Socket()
        guard.track(first)
        guard.cancel()
        assertTrue(first.isClosed)
        assertTrue(guard.cancelled)
        val late = Socket()
        try {
            guard.track(late)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(late.isClosed)
    }

    @Test fun finishedGuardLeavesSocketsToTheConnection() {
        val guard = UpstreamSetupGuard()
        val socket = Socket()
        guard.track(socket)
        guard.finish()
        guard.cancel()
        assertFalse(socket.isClosed)
        assertFalse(guard.cancelled)
        socket.close()
    }

    @Test fun guardFollowsTheSetupCoroutineToBlockingCodeAndChildren() = runBlocking {
        val guard = UpstreamSetupGuard()
        assertNull(UpstreamSetupGuard.current())
        withContext(Dispatchers.IO + guard.asContextElement()) {
            assertSame(guard, UpstreamSetupGuard.current())
            val child = async(Dispatchers.IO) { UpstreamSetupGuard.current() }
            assertSame(guard, child.await())
            UpstreamSetupGuard.checkActive()
            guard.cancel()
            try {
                UpstreamSetupGuard.checkActive(IOException("Socket closed"))
                fail("expected cancellation")
            } catch (e: CancellationException) {
                assertTrue(e.cause is IOException)
            }
        }
        assertNull(UpstreamSetupGuard.current())
    }

    @Test fun cancellingTheGuardUnblocksADialInProgress() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { listener ->
            val guard = UpstreamSetupGuard()
            val socket = Socket(loopback, listener.localPort)
            guard.track(socket)
            listener.accept().use {
                val unblocked = CountDownLatch(1)
                Thread {
                    try { socket.getInputStream().read() } catch (_: IOException) {}
                    unblocked.countDown()
                }.start()
                Thread.sleep(100)
                guard.cancel()
                assertTrue(unblocked.await(5, TimeUnit.SECONDS))
            }
        }
    }
}
