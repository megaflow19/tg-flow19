package dev.minios.tgwsproxy.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sockets dialed for one Telegram connection while its upstream is being set up. Dialing is
 * blocking I/O that coroutine cancellation does not interrupt, so when Telegram gives up on the
 * connection (see [ClientSetupInput]) [cancel] closes these sockets and every attempt ends at once.
 *
 * Reached from blocking code through a thread-local that follows the setup coroutine (and its
 * children) across threads.
 */
internal class UpstreamSetupGuard {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    var cancelled = false
        private set

    /** Registers a socket of this setup; closes it right away if the setup is already over. */
    fun track(socket: Socket) {
        sockets.add(socket)
        if (cancelled) {
            runCatching { socket.close() }
            throw cancellation()
        }
    }

    @Volatile
    private var finished = false

    /** Setup is over: the sockets now belong to the connection, [cancel] leaves them alone. */
    fun finish() {
        finished = true
    }

    fun cancel() {
        if (finished) return
        cancelled = true
        sockets.forEach { runCatching { it.close() } }
    }

    fun asContextElement() = local.asContextElement(this)

    companion object {
        private val local = ThreadLocal<UpstreamSetupGuard?>()

        fun current(): UpstreamSetupGuard? = local.get()

        /** Throws when the current setup was cancelled: its failures must not count anywhere. */
        fun checkActive(cause: Throwable? = null) {
            if (current()?.cancelled == true) throw cancellation(cause)
        }

        fun cancellation(cause: Throwable? = null) =
            CancellationException("Local client disconnected during upstream setup").apply {
                if (cause != null) initCause(cause)
            }
    }
}

/**
 * A deadline for a whole blocking step (connect, handshakes, first write). Socket timeouts only
 * bound each single read; a server that answers slowly, or a send that never drains, can hold a
 * setup much longer. When the deadline passes, [close] is called and the step fails with
 * [SocketTimeoutException], as a timeout would.
 *
 * After Flowseal v1.12.0 / crim50n v2.1.2: direct WebSocket setup and the first relay write have
 * deadlines; streaming after that is unchanged.
 */
internal object SetupDeadline {
    private const val RUNNING = 0
    private const val DONE = 1
    private const val FIRED = 2

    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "setup-deadline").apply { isDaemon = true }
    }

    fun <T> bounded(timeoutMs: Long, close: () -> Unit, block: () -> T): T {
        val state = AtomicInteger(RUNNING)
        val task = timer.schedule({
            if (state.compareAndSet(RUNNING, FIRED)) runCatching(close)
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val result = block()
            if (state.compareAndSet(RUNNING, DONE)) return result
            throw SocketTimeoutException("Deadline of $timeoutMs ms passed")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (state.compareAndSet(RUNNING, DONE)) throw e
            if (e is SocketTimeoutException) throw e
            throw SocketTimeoutException("Deadline of $timeoutMs ms passed").apply { initCause(e) }
        } finally {
            task.cancel(false)
        }
    }
}
