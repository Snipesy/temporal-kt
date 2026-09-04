package com.surrealdev.temporal.activity.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.heartbeat
import com.surrealdev.temporal.common.exceptions.ActivityCancelledException
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.NoOpCodec
import com.surrealdev.temporal.testing.createStubApplication
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.SimpleAttributeScope
import coresdk.activity_task.ActivityTaskOuterClass
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Heartbeats must never reach the Core bridge once the worker is shutting the activity down:
 * after finalization the native worker is gone, and a heartbeat there used to abort the JVM.
 */
class ActivityContextImplHeartbeatTest {
    private val bridgeCalls = AtomicInteger(0)

    private suspend fun context(): ActivityContextImpl =
        ActivityContextImpl(
            start =
                ActivityTaskOuterClass.Start
                    .newBuilder()
                    .setActivityType("loop")
                    .setActivityId("a1")
                    .build(),
            taskToken = ByteString.copyFromUtf8("token"),
            taskQueue = "q",
            serializer = CompositePayloadSerializer.default(),
            codec = NoOpCodec,
            heartbeatFn = { _, _ -> bridgeCalls.incrementAndGet() },
            parentScope = SimpleAttributeScope(Attributes(concurrent = false), createStubApplication()),
            parentCoroutineContext = coroutineContext,
        )

    @Test
    fun `heartbeat reaches the bridge while the activity is live`() =
        runTest {
            val ctx = context()
            ctx.heartbeat("progress")
            assertEquals(1, bridgeCalls.get())
        }

    @Test
    fun `heartbeat after worker shutdown throws WorkerShutdown and never calls the bridge`() =
        runTest {
            val ctx = context()
            ctx.markCancelled(ActivityCancelledException.WorkerShutdown())

            assertFailsWith<ActivityCancelledException.WorkerShutdown> { ctx.heartbeat("progress") }
            assertEquals(0, bridgeCalls.get(), "no bridge call may happen once the worker is shutting down")
        }
}
