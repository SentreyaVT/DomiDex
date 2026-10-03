package com.dominik.cardvault.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firestore.v1.ListenRequest
import com.google.firestore.v1.ListenResponse
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Local interceptor-contract regression only: fake Channel, no socket, Auth, server or Room. */
@RunWith(AndroidJUnit4::class)
class PrivatePullDemoGrpcGateExecutorInstrumentedTest {
    private var gate: PrivatePullDemoGrpcGate? = null

    @Before fun setUp() { gate = PrivatePullDemoGrpcGate() }
    @After fun tearDown() { gate?.close(); gate = null }

    @Test fun cancelAfterStartBeforeFirstRpcUsesOriginalExecutorExactlyOnce() {
        val executor = QueuedExecutor()
        val channel = LocalChannel()
        val call = newCall(channel, executor)
        val listener = RecordingListener(Thread.currentThread())
        call.start(listener, Metadata())
        onOtherThread { call.cancel("cleanup caller", null); call.cancel("duplicate", null) }
        assertTrue(listener.statuses.isEmpty())
        assertEquals(1, executor.count())
        executor.drain()
        assertEquals(listOf(Status.Code.CANCELLED), listener.statuses)
        call.cancel("after delivery", null)
        call.sendMessage(ListenRequest.newBuilder().setDatabase(PrivatePullDemoGrpcGate.DATABASE).build())
        call.halfClose(); call.request(1)
        executor.drain()
        assertEquals(listOf(Status.Code.CANCELLED), listener.statuses)
        assertEquals(0, executor.count())
        assertNeverStarted(channel)
    }

    @Test fun cancelBeforeStartIsRememberedAndScheduledOnceAfterListenerRegistration() {
        val executor = QueuedExecutor()
        val channel = LocalChannel()
        val call = newCall(channel, executor)
        val listener = RecordingListener(Thread.currentThread())
        onOtherThread { call.cancel("before start", null); call.cancel("duplicate", null) }
        assertTrue(listener.statuses.isEmpty())
        assertEquals(0, executor.count())
        call.start(listener, Metadata())
        assertTrue(listener.statuses.isEmpty())
        assertEquals(1, executor.count())
        executor.drain()
        call.cancel("after delivery", null)
        executor.drain()
        assertEquals(listOf(Status.Code.CANCELLED), listener.statuses)
        assertEquals(0, executor.count())
        assertNeverStarted(channel)
    }

    @Test fun foreignDatabaseRejectionUsesOriginalExecutorWithoutStartingDelegate() {
        val executor = QueuedExecutor()
        val channel = LocalChannel()
        val call = newCall(channel, executor)
        val listener = RecordingListener(Thread.currentThread())
        call.start(listener, Metadata())
        onOtherThread {
            call.sendMessage(ListenRequest.newBuilder().setDatabase("projects/not-the-demo/databases/(default)").build())
            call.cancel("must not replace denial", null)
        }
        assertTrue(listener.statuses.isEmpty())
        assertEquals(1, executor.count())
        executor.drain()
        assertEquals(listOf(Status.Code.PERMISSION_DENIED), listener.statuses)
        assertEquals(0, executor.count())
        assertNeverStarted(channel)
    }

    @Test fun startedDelegateOwnsCloseWithoutFabricatedCallbackOrRedispatch() {
        val executor = QueuedExecutor()
        val channel = LocalChannel()
        val call = newCall(channel, executor)
        val listener = RecordingListener(Thread.currentThread())
        call.start(listener, Metadata())
        call.sendMessage(ListenRequest.newBuilder().setDatabase(PrivatePullDemoGrpcGate.DATABASE).build())
        onOtherThread { call.cancel("started delegate", null) }
        assertEquals(1, channel.delegate.starts)
        assertEquals(1, channel.delegate.sends)
        assertEquals(1, channel.delegate.cancels)
        assertTrue(listener.statuses.isEmpty())
        assertEquals(0, executor.count())
        // Fake delegate has already selected the callback thread; do not add a second dispatch.
        checkNotNull(channel.delegate.response).onClose(Status.CANCELLED, Metadata())
        assertEquals(listOf(Status.Code.CANCELLED), listener.statuses)
        assertEquals(0, executor.count())
    }

    @Test fun missingOriginalExecutorFailsBeforeCreatingDelegateCall() {
        val channel = LocalChannel()
        var failure: IllegalStateException? = null
        try { checkNotNull(gate).interceptCall(METHOD, CallOptions.DEFAULT, channel) }
        catch (caught: IllegalStateException) { failure = caught }
        assertNotNull("Missing original executor must not silently choose another thread", failure)
        assertEquals("Firestore call executor is required", failure?.message)
        assertEquals(0, channel.newCalls)
    }

    @Test fun rejectedExecutorNeverFallsBackToCallerThread() {
        val channel = LocalChannel()
        val call = newCall(channel, Executor { throw RejectedExecutionException("closed contract-test executor") })
        val listener = RecordingListener(Thread.currentThread())
        call.start(listener, Metadata())
        var rejected = false
        try { call.cancel("no inline fallback", null) }
        catch (_: RejectedExecutionException) { rejected = true }
        assertTrue("Executor rejection must remain visible", rejected)
        assertTrue(listener.statuses.isEmpty())
        assertNeverStarted(channel)
    }

    private fun newCall(channel: LocalChannel, executor: Executor): ClientCall<ListenRequest, ListenResponse> {
        val originalOptions = CallOptions.DEFAULT.withExecutor(executor)
        val call = checkNotNull(gate).interceptCall(METHOD, originalOptions, channel)
        assertSame("Delegate must retain the exact original CallOptions", originalOptions, channel.options)
        assertSame("Callback executor identity changed", executor, channel.options?.executor)
        assertEquals(1, channel.newCalls)
        return call
    }

    private fun assertNeverStarted(channel: LocalChannel) {
        assertEquals(0, channel.delegate.starts)
        assertEquals(0, channel.delegate.sends)
        assertEquals(0, channel.delegate.cancels)
    }

    private fun onOtherThread(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val caller = Thread({
            try { action() } catch (caught: Throwable) { failure.set(caught) }
            finally { done.countDown() }
        }, "DemoGateCloseCaller")
        caller.start()
        assertTrue("Local caller did not finish", done.await(5, TimeUnit.SECONDS))
        caller.join(5_000)
        assertFalse("Local caller remained alive", caller.isAlive)
        failure.get()?.let { throw AssertionError("Callback ran on wrong caller thread", it) }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { synchronized(tasks) { tasks.addLast(command) } }
        fun count(): Int = synchronized(tasks) { tasks.size }
        fun drain() {
            while (true) {
                val next = synchronized(tasks) { tasks.pollFirst() } ?: return
                next.run()
            }
        }
    }

    private class RecordingListener(private val callbackThread: Thread) : ClientCall.Listener<ListenResponse>() {
        val statuses = mutableListOf<Status.Code>()
        override fun onClose(status: Status, trailers: Metadata) {
            assertSame("Local close ignored original CallOptions.executor", callbackThread, Thread.currentThread())
            statuses.add(status.code)
        }
    }

    private class LocalDelegate : ClientCall<ListenRequest, ListenResponse>() {
        var starts = 0
        var sends = 0
        var cancels = 0
        var response: Listener<ListenResponse>? = null
        override fun start(listener: Listener<ListenResponse>, headers: Metadata) { starts++; response = listener }
        override fun request(numMessages: Int) = Unit
        override fun cancel(message: String?, cause: Throwable?) { cancels++ }
        override fun halfClose() = Unit
        override fun sendMessage(message: ListenRequest) { sends++ }
    }

    private class LocalChannel : Channel() {
        val delegate = LocalDelegate()
        var newCalls = 0
        var options: CallOptions? = null
        override fun authority(): String = "${PrivatePullDemoGrpcGate.HOST}:${PrivatePullDemoGrpcGate.FIRESTORE_PORT}"
        @Suppress("UNCHECKED_CAST")
        override fun <ReqT, RespT> newCall(method: MethodDescriptor<ReqT, RespT>, callOptions: CallOptions): ClientCall<ReqT, RespT> {
            assertSame(METHOD, method)
            newCalls++; options = callOptions
            return delegate as ClientCall<ReqT, RespT>
        }
    }

    private companion object {
        val METHOD: MethodDescriptor<ListenRequest, ListenResponse> =
            MethodDescriptor.newBuilder<ListenRequest, ListenResponse>()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName("google.firestore.v1.Firestore/Listen")
                .setRequestMarshaller(object : MethodDescriptor.Marshaller<ListenRequest> {
                    override fun stream(value: ListenRequest): InputStream = ByteArrayInputStream(value.toByteArray())
                    override fun parse(stream: InputStream): ListenRequest = ListenRequest.parseFrom(stream)
                })
                .setResponseMarshaller(object : MethodDescriptor.Marshaller<ListenResponse> {
                    override fun stream(value: ListenResponse): InputStream = ByteArrayInputStream(value.toByteArray())
                    override fun parse(stream: InputStream): ListenResponse = ListenResponse.parseFrom(stream)
                }).build()
    }
}
