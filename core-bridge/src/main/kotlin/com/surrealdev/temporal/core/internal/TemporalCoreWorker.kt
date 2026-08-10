package com.surrealdev.temporal.core.internal

import com.surrealdev.temporal.core.CorePollerBehavior
import com.surrealdev.temporal.core.SlotSupplier
import com.surrealdev.temporal.core.SlotSupplierBridgeEntry
import com.surrealdev.temporal.core.TemporalCoreException
import com.surrealdev.temporal.core.WorkerConfig
import com.surrealdev.temporal.core.WorkerDeploymentOptions
import io.temporal.sdkbridge.TemporalCoreByteArrayRef
import io.temporal.sdkbridge.TemporalCoreByteArrayRefArray
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierAvailableSlotsCallback
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierCallbacksImpl
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierCallbacksStruct
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierCancelReserveCallback
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierFreeCallback
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierMarkUsedCallback
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierReleaseCallback
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierReserveCallback
import io.temporal.sdkbridge.TemporalCoreCustomSlotSupplierTryReserveCallback
import io.temporal.sdkbridge.TemporalCoreFixedSizeSlotSupplier
import io.temporal.sdkbridge.TemporalCorePollerBehavior
import io.temporal.sdkbridge.TemporalCorePollerBehaviorAutoscaling
import io.temporal.sdkbridge.TemporalCorePollerBehaviorSimpleMaximum
import io.temporal.sdkbridge.TemporalCoreResourceBasedSlotSupplier
import io.temporal.sdkbridge.TemporalCoreResourceBasedTunerOptions
import io.temporal.sdkbridge.TemporalCoreSlotSupplier
import io.temporal.sdkbridge.TemporalCoreTunerHolder
import io.temporal.sdkbridge.TemporalCoreWorkerDeploymentOptions
import io.temporal.sdkbridge.TemporalCoreWorkerDeploymentVersion
import io.temporal.sdkbridge.TemporalCoreWorkerOptions
import io.temporal.sdkbridge.TemporalCoreWorkerOrFail
import io.temporal.sdkbridge.TemporalCoreWorkerReplayPushResult
import io.temporal.sdkbridge.TemporalCoreWorkerReplayerOrFail
import io.temporal.sdkbridge.TemporalCoreWorkerTaskTypes
import io.temporal.sdkbridge.TemporalCoreWorkerVersioningNone
import io.temporal.sdkbridge.TemporalCoreWorkerVersioningStrategy
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * FFM bridge for Temporal Core worker operations.
 *
 * Workers poll for tasks from the Temporal server and execute workflows
 * and activities. This bridge provides access to worker creation, polling,
 * completion, and shutdown functionality.
 *
 * Uses jextract-generated bindings for direct function calls.
 */
internal object TemporalCoreWorker {
    init {
        // Ensure native library is loaded before using generated bindings
        TemporalCoreFfmUtil.ensureLoaded()
    }

    // ============================================================
    // Callback Interfaces
    // ============================================================

    /**
     * Callback interface for simple operations (validate, shutdown).
     */
    fun interface WorkerCallback {
        fun onComplete(error: String?)
    }

    // ============================================================
    // Worker Creation API
    // ============================================================

    data class CreateWorkerResult(
        val workerPtr: MemorySegment,
        val slotSupplierBridges: List<SlotSupplierBridgeEntry>,
    )

    /**
     * Creates a new worker.
     *
     * @param clientPtr Pointer to the client
     * @param arena Arena for allocations
     * @param namespace The namespace
     * @param taskQueue The task queue to poll
     * @param maxCachedWorkflows Maximum cached workflow executions
     * @param deploymentOptions Optional deployment versioning options
     * @param maxConcurrentWorkflowTasks Maximum concurrent workflow task executions
     * @param maxConcurrentActivities Maximum concurrent activity executions
     * @return Pointer to the worker
     * @throws TemporalCoreException if worker creation fails
     */
    fun createWorker(
        clientPtr: MemorySegment,
        arena: Arena,
        namespace: String,
        taskQueue: String,
        config: WorkerConfig = WorkerConfig(),
    ): CreateWorkerResult {
        val bridges = mutableListOf<SlotSupplierBridgeEntry>()
        val options = buildWorkerOptions(arena, namespace, taskQueue, config, bridges)

        val result = CoreBridge.temporal_core_worker_new(arena, clientPtr, options)

        val workerPtr = TemporalCoreWorkerOrFail.worker(result)
        val failPtr = TemporalCoreWorkerOrFail.fail(result)

        if (failPtr != MemorySegment.NULL) {
            val errorMessage = TemporalCoreFfmUtil.readByteArray(failPtr)
            throw TemporalCoreException(errorMessage ?: "Unknown error creating worker")
        }

        return CreateWorkerResult(workerPtr, bridges)
    }

    /**
     * Frees a worker.
     *
     * @param workerPtr Pointer to the worker to free
     */
    fun freeWorker(workerPtr: MemorySegment) {
        CoreBridge.temporal_core_worker_free(workerPtr)
    }

    /**
     * Validates a worker's configuration using a reusable callback stub.
     *
     * @param workerPtr Pointer to the worker
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param callback Callback invoked when validation completes
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun validate(
        workerPtr: MemorySegment,
        dispatcher: WorkerCallbackDispatcher,
        callback: WorkerCallback,
    ): MemorySegment {
        val contextPtr = dispatcher.registerWorker(callback)
        CoreBridge.temporal_core_worker_validate(
            workerPtr,
            contextPtr,
            dispatcher.workerCallbackStub,
        )
        return contextPtr
    }

    /**
     * Replaces the client used by a worker.
     *
     * @param workerPtr Pointer to the worker
     * @param newClientPtr Pointer to the new client
     * @return Error message if failed, null if successful
     */
    fun replaceClient(
        workerPtr: MemorySegment,
        newClientPtr: MemorySegment,
    ): String? {
        val result = CoreBridge.temporal_core_worker_replace_client(workerPtr, newClientPtr)
        return if (result != MemorySegment.NULL) {
            TemporalCoreFfmUtil.readByteArray(result)
        } else {
            null
        }
    }

    // ============================================================
    // Polling API
    // ============================================================

    /**
     * Polls for a workflow activation with zero-copy protobuf parsing.
     * The message is parsed directly from native memory without intermediate ByteArray copy.
     *
     * @param workerPtr Pointer to the worker
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param callback Typed callback invoked when poll completes
     * @param parser Function that parses the CodedInputStream into the message type
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun <T : com.google.protobuf.MessageLite> pollWorkflowActivation(
        workerPtr: MemorySegment,
        dispatcher: WorkerCallbackDispatcher,
        callback: TemporalCoreFfmUtil.TypedCallback<T>,
        parser: (com.google.protobuf.CodedInputStream) -> T,
    ): MemorySegment {
        val contextPtr = dispatcher.registerPoll(callback, parser)
        CoreBridge.temporal_core_worker_poll_workflow_activation(
            workerPtr,
            contextPtr,
            dispatcher.pollCallbackStub,
        )
        return contextPtr
    }

    /**
     * Polls for an activity task with zero-copy protobuf parsing.
     * The message is parsed directly from native memory without intermediate ByteArray copy.
     *
     * @param workerPtr Pointer to the worker
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param callback Typed callback invoked when poll completes
     * @param parser Function that parses the CodedInputStream into the message type
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun <T : com.google.protobuf.MessageLite> pollActivityTask(
        workerPtr: MemorySegment,
        dispatcher: WorkerCallbackDispatcher,
        callback: TemporalCoreFfmUtil.TypedCallback<T>,
        parser: (com.google.protobuf.CodedInputStream) -> T,
    ): MemorySegment {
        val contextPtr = dispatcher.registerPoll(callback, parser)
        CoreBridge.temporal_core_worker_poll_activity_task(
            workerPtr,
            contextPtr,
            dispatcher.pollCallbackStub,
        )
        return contextPtr
    }

    /**
     * Polls for a nexus task with zero-copy protobuf parsing.
     * The message is parsed directly from native memory without intermediate ByteArray copy.
     *
     * @param workerPtr Pointer to the worker
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param callback Typed callback invoked when poll completes
     * @param parser Function that parses the CodedInputStream into the message type
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun <T : com.google.protobuf.MessageLite> pollNexusTask(
        workerPtr: MemorySegment,
        dispatcher: WorkerCallbackDispatcher,
        callback: TemporalCoreFfmUtil.TypedCallback<T>,
        parser: (com.google.protobuf.CodedInputStream) -> T,
    ): MemorySegment {
        val contextPtr = dispatcher.registerPoll(callback, parser)
        CoreBridge.temporal_core_worker_poll_nexus_task(
            workerPtr,
            contextPtr,
            dispatcher.pollCallbackStub,
        )
        return contextPtr
    }

    // ============================================================
    // Completion API
    // ============================================================

    /**
     * Completes a workflow activation using a reusable callback stub.
     *
     * Uses zero-copy serialization: the protobuf message is serialized directly
     * to native memory without intermediate ByteArray allocation.
     *
     * @param workerPtr Pointer to the worker
     * @param arena Arena for allocations (for completion data)
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param completion The completion protobuf message
     * @param callback Callback invoked when completion is processed
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun <T : com.google.protobuf.MessageLite> completeWorkflowActivation(
        workerPtr: MemorySegment,
        arena: Arena,
        dispatcher: WorkerCallbackDispatcher,
        completion: T,
        callback: WorkerCallback,
    ): MemorySegment {
        val completionRef = TemporalCoreFfmUtil.serializeToByteArrayRef(arena, completion)
        val contextPtr = dispatcher.registerWorker(callback)
        CoreBridge.temporal_core_worker_complete_workflow_activation(
            workerPtr,
            completionRef,
            contextPtr,
            dispatcher.workerCallbackStub,
        )
        return contextPtr
    }

    /**
     * Completes an activity task using a reusable callback stub.
     *
     * Uses zero-copy serialization: the protobuf message is serialized directly
     * to native memory without intermediate ByteArray allocation.
     *
     * @param workerPtr Pointer to the worker
     * @param arena Arena for allocations (for completion data)
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param completion The completion protobuf message
     * @param callback Callback invoked when completion is processed
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun <T : com.google.protobuf.MessageLite> completeActivityTask(
        workerPtr: MemorySegment,
        arena: Arena,
        dispatcher: WorkerCallbackDispatcher,
        completion: T,
        callback: WorkerCallback,
    ): MemorySegment {
        val completionRef = TemporalCoreFfmUtil.serializeToByteArrayRef(arena, completion)
        val contextPtr = dispatcher.registerWorker(callback)
        CoreBridge.temporal_core_worker_complete_activity_task(
            workerPtr,
            completionRef,
            contextPtr,
            dispatcher.workerCallbackStub,
        )
        return contextPtr
    }

    /**
     * Completes a nexus task using a reusable callback stub.
     *
     * Uses zero-copy serialization: the protobuf message is serialized directly
     * to native memory without intermediate ByteArray allocation.
     *
     * @param workerPtr Pointer to the worker
     * @param arena Arena for allocations (for completion data)
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param completion The completion protobuf message
     * @param callback Callback invoked when completion is processed
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun <T : com.google.protobuf.MessageLite> completeNexusTask(
        workerPtr: MemorySegment,
        arena: Arena,
        dispatcher: WorkerCallbackDispatcher,
        completion: T,
        callback: WorkerCallback,
    ): MemorySegment {
        val completionRef = TemporalCoreFfmUtil.serializeToByteArrayRef(arena, completion)
        val contextPtr = dispatcher.registerWorker(callback)
        CoreBridge.temporal_core_worker_complete_nexus_task(
            workerPtr,
            completionRef,
            contextPtr,
            dispatcher.workerCallbackStub,
        )
        return contextPtr
    }

    /**
     * Records an activity heartbeat.
     *
     * Uses zero-copy serialization: the protobuf message is serialized directly
     * to native memory without intermediate ByteArray allocation.
     *
     * @param workerPtr Pointer to the worker
     * @param arena Arena for allocations
     * @param heartbeat The heartbeat protobuf message
     * @return Error message if failed, null if successful
     */
    fun <T : com.google.protobuf.MessageLite> recordActivityHeartbeat(
        workerPtr: MemorySegment,
        arena: Arena,
        heartbeat: T,
    ): String? {
        val heartbeatRef = TemporalCoreFfmUtil.serializeToByteArrayRef(arena, heartbeat)
        val result = CoreBridge.temporal_core_worker_record_activity_heartbeat(workerPtr, heartbeatRef)
        return if (result != MemorySegment.NULL) {
            TemporalCoreFfmUtil.readByteArray(result)
        } else {
            null
        }
    }

    /**
     * Requests eviction of a workflow from the cache.
     *
     * @param workerPtr Pointer to the worker
     * @param arena Arena for allocations
     * @param runId The run ID to evict
     */
    fun requestWorkflowEviction(
        workerPtr: MemorySegment,
        arena: Arena,
        runId: String,
    ) {
        val runIdRef = TemporalCoreFfmUtil.createByteArrayRef(arena, runId)
        CoreBridge.temporal_core_worker_request_workflow_eviction(workerPtr, runIdRef)
    }

    // ============================================================
    // Shutdown API
    // ============================================================

    /**
     * Initiates worker shutdown.
     *
     * @param workerPtr Pointer to the worker
     */
    fun initiateShutdown(workerPtr: MemorySegment) {
        CoreBridge.temporal_core_worker_initiate_shutdown(workerPtr)
    }

    /**
     * Finalizes worker shutdown using a reusable callback stub.
     *
     * @param workerPtr Pointer to the worker
     * @param dispatcher Callback dispatcher with reusable stubs
     * @param callback Callback invoked when shutdown completes
     * @return Context pointer containing the callback ID (for cancellation support)
     */
    fun finalizeShutdown(
        workerPtr: MemorySegment,
        dispatcher: WorkerCallbackDispatcher,
        callback: WorkerCallback,
    ): MemorySegment {
        val contextPtr = dispatcher.registerWorker(callback)
        CoreBridge.temporal_core_worker_finalize_shutdown(
            workerPtr,
            contextPtr,
            dispatcher.workerCallbackStub,
        )
        return contextPtr
    }

    // ============================================================
    // Replay API
    // ============================================================

    /**
     * Result of creating a replayer.
     */
    data class ReplayerResult(
        val workerPtr: MemorySegment,
        val pusherPtr: MemorySegment,
    )

    /**
     * Creates a new replayer for workflow history replay.
     *
     * @param runtimePtr Pointer to the runtime
     * @param arena Arena for allocations
     * @param namespace The namespace
     * @param taskQueue The task queue
     * @return The worker and pusher pointers
     * @throws TemporalCoreException if replayer creation fails
     */
    fun createReplayer(
        runtimePtr: MemorySegment,
        arena: Arena,
        namespace: String,
        taskQueue: String,
    ): ReplayerResult {
        val options =
            buildWorkerOptions(
                arena = arena,
                namespace = namespace,
                taskQueue = taskQueue,
                config = WorkerConfig(maxCachedWorkflows = 1, enableActivities = false, enableNexus = false),
            )

        val result = CoreBridge.temporal_core_worker_replayer_new(arena, runtimePtr, options)

        val workerPtr = TemporalCoreWorkerReplayerOrFail.worker(result)
        val pusherPtr = TemporalCoreWorkerReplayerOrFail.worker_replay_pusher(result)
        val failPtr = TemporalCoreWorkerReplayerOrFail.fail(result)

        if (failPtr != MemorySegment.NULL) {
            val errorMessage = TemporalCoreFfmUtil.readByteArray(failPtr)
            throw TemporalCoreException(errorMessage ?: "Unknown error creating replayer")
        }

        return ReplayerResult(workerPtr, pusherPtr)
    }

    /**
     * Frees a replay pusher.
     *
     * @param pusherPtr Pointer to the pusher to free
     */
    fun freeReplayPusher(pusherPtr: MemorySegment) {
        CoreBridge.temporal_core_worker_replay_pusher_free(pusherPtr)
    }

    /**
     * Pushes a workflow history for replay.
     *
     * @param arena Arena for allocations
     * @param workerPtr Pointer to the worker
     * @param pusherPtr Pointer to the pusher
     * @param workflowId The workflow ID
     * @param history The history protobuf bytes
     * @return Error message if failed, null if successful
     */
    fun replayPush(
        arena: Arena,
        workerPtr: MemorySegment,
        pusherPtr: MemorySegment,
        workflowId: String,
        history: ByteArray,
    ): String? {
        val workflowIdRef = TemporalCoreFfmUtil.createByteArrayRef(arena, workflowId)
        val historyRef = TemporalCoreFfmUtil.createByteArrayRef(arena, history)

        val result = CoreBridge.temporal_core_worker_replay_push(arena, workerPtr, pusherPtr, workflowIdRef, historyRef)

        val failPtr = TemporalCoreWorkerReplayPushResult.fail(result)
        return if (failPtr != MemorySegment.NULL) {
            TemporalCoreFfmUtil.readByteArray(failPtr)
        } else {
            null
        }
    }

    // ============================================================
    // Helper Functions
    // ============================================================

    private fun buildWorkerOptions(
        arena: Arena,
        namespace: String,
        taskQueue: String,
        config: WorkerConfig,
        bridges: MutableList<SlotSupplierBridgeEntry> = mutableListOf(),
    ): MemorySegment {
        val options = TemporalCoreWorkerOptions.allocate(arena)

        TemporalCoreWorkerOptions.namespace_(options, TemporalCoreFfmUtil.createByteArrayRef(arena, namespace))
        TemporalCoreWorkerOptions.task_queue(options, TemporalCoreFfmUtil.createByteArrayRef(arena, taskQueue))
        // Left unset when the worker has no identity of its own: an empty byte ref reaches Core as
        // None, which makes the worker inherit the client's identity. Matches sdk-python, which
        // passes identity_override=config.get("identity") and lets the bridge fall back.
        TemporalCoreWorkerOptions.identity_override(
            options,
            TemporalCoreFfmUtil.createByteArrayRef(arena, config.workerIdentity),
        )
        TemporalCoreWorkerOptions.max_cached_workflows(options, config.maxCachedWorkflows)

        TemporalCoreWorkerOptions.versioning_strategy(
            options,
            config.deploymentOptions.toFfmVersioningStrategy(arena, config.buildId),
        )

        // Set task types
        val taskTypes = TemporalCoreWorkerTaskTypes.allocate(arena)
        TemporalCoreWorkerTaskTypes.enable_workflows(taskTypes, config.enableWorkflows)
        TemporalCoreWorkerTaskTypes.enable_remote_activities(taskTypes, config.enableActivities)
        TemporalCoreWorkerTaskTypes.enable_local_activities(taskTypes, config.enableLocalActivities)
        TemporalCoreWorkerTaskTypes.enable_nexus(taskTypes, config.enableNexus)
        TemporalCoreWorkerOptions.task_types(options, taskTypes)

        // Initialize tuner with slot suppliers
        val tuner = TemporalCoreWorkerOptions.tuner(options)
        initializeSlotSupplier(
            TemporalCoreTunerHolder.workflow_slot_supplier(tuner),
            config.workflowSlotSupplier,
            arena,
            bridges,
            "workflow",
        )
        initializeSlotSupplier(
            TemporalCoreTunerHolder.activity_slot_supplier(tuner),
            config.activitySlotSupplier,
            arena,
            bridges,
            "activity",
        )
        initializeSlotSupplier(
            TemporalCoreTunerHolder.local_activity_slot_supplier(tuner),
            config.localActivitySlotSupplier,
            arena,
            bridges,
            "local_activity",
        )
        initializeSlotSupplier(
            TemporalCoreTunerHolder.nexus_task_slot_supplier(tuner),
            config.nexusSlotSupplier,
            arena,
            bridges,
            "nexus",
        )

        // Set timeouts and limits
        TemporalCoreWorkerOptions.max_heartbeat_throttle_interval_millis(
            options,
            config.maxHeartbeatThrottleIntervalMs,
        )
        TemporalCoreWorkerOptions.default_heartbeat_throttle_interval_millis(
            options,
            config.defaultHeartbeatThrottleIntervalMs,
        )
        TemporalCoreWorkerOptions.max_activities_per_second(options, config.maxActivitiesPerSecond)
        TemporalCoreWorkerOptions.max_task_queue_activities_per_second(
            options,
            config.maxTaskQueueActivitiesPerSecond,
        )
        TemporalCoreWorkerOptions.graceful_shutdown_period_millis(options, config.gracefulShutdownPeriodMs)
        TemporalCoreWorkerOptions.sticky_queue_schedule_to_start_timeout_millis(
            options,
            config.stickyQueueScheduleToStartTimeoutMs,
        )

        // Set poller behavior
        TemporalCoreWorkerOptions.workflow_task_poller_behavior(
            options,
            config.workflowPollerBehavior.toFfm(arena),
        )
        TemporalCoreWorkerOptions.nonsticky_to_sticky_poll_ratio(options, config.nonstickyToStickyPollRatio)
        TemporalCoreWorkerOptions.activity_task_poller_behavior(
            options,
            config.activityPollerBehavior.toFfm(arena),
        )
        TemporalCoreWorkerOptions.nexus_task_poller_behavior(
            options,
            config.nexusPollerBehavior.toFfm(arena),
        )

        // Set nondeterminism options
        TemporalCoreWorkerOptions.nondeterminism_as_workflow_fail(options, config.nondeterminismAsWorkflowFail)
        TemporalCoreWorkerOptions.nondeterminism_as_workflow_fail_for_types(
            options,
            config.nondeterminismAsWorkflowFailForTypes.toFfmByteArrayRefArray(arena),
        )
        TemporalCoreWorkerOptions.plugins(options, createEmptyByteArrayRefArray(arena))
        TemporalCoreWorkerOptions.storage_drivers(options, createEmptyByteArrayRefArray(arena))

        return options
    }

    private fun CorePollerBehavior.toFfm(arena: Arena): MemorySegment {
        val behavior = TemporalCorePollerBehavior.allocate(arena)
        return when (this) {
            is CorePollerBehavior.SimpleMaximum -> {
                val simpleMax = TemporalCorePollerBehaviorSimpleMaximum.allocate(arena)
                TemporalCorePollerBehaviorSimpleMaximum.simple_maximum(simpleMax, maximum.toLong())
                TemporalCorePollerBehavior.simple_maximum(behavior, simpleMax)
                TemporalCorePollerBehavior.autoscaling(behavior, MemorySegment.NULL)
                behavior
            }

            is CorePollerBehavior.Autoscaling -> {
                val autoscaling = TemporalCorePollerBehaviorAutoscaling.allocate(arena)
                TemporalCorePollerBehaviorAutoscaling.minimum(autoscaling, minimum.toLong())
                TemporalCorePollerBehaviorAutoscaling.maximum(autoscaling, maximum.toLong())
                TemporalCorePollerBehaviorAutoscaling.initial(autoscaling, initial.toLong())
                TemporalCorePollerBehavior.autoscaling(behavior, autoscaling)
                TemporalCorePollerBehavior.simple_maximum(behavior, MemorySegment.NULL)
                behavior
            }
        }
    }

    private fun WorkerDeploymentOptions?.toFfmVersioningStrategy(
        arena: Arena,
        buildId: String = "",
    ): MemorySegment {
        val strategy = TemporalCoreWorkerVersioningStrategy.allocate(arena)
        if (this != null) {
            TemporalCoreWorkerVersioningStrategy.tag(strategy, CoreBridge.DeploymentBased())
            val deploymentBased = TemporalCoreWorkerVersioningStrategy.deployment_based(strategy)
            val versionSegment = TemporalCoreWorkerDeploymentOptions.version(deploymentBased)
            TemporalCoreWorkerDeploymentVersion.deployment_name(
                versionSegment,
                TemporalCoreFfmUtil.createByteArrayRef(arena, version.deploymentName),
            )
            TemporalCoreWorkerDeploymentVersion.build_id(
                versionSegment,
                TemporalCoreFfmUtil.createByteArrayRef(arena, version.buildId),
            )
            TemporalCoreWorkerDeploymentOptions.use_worker_versioning(deploymentBased, useWorkerVersioning)
            TemporalCoreWorkerDeploymentOptions.default_versioning_behavior(
                deploymentBased,
                defaultVersioningBehavior.value,
            )
        } else {
            TemporalCoreWorkerVersioningStrategy.tag(strategy, CoreBridge.None())
            val noneStrategy = TemporalCoreWorkerVersioningNone.allocate(arena)
            TemporalCoreWorkerVersioningNone.build_id(
                noneStrategy,
                TemporalCoreFfmUtil.createByteArrayRef(arena, buildId),
            )
            TemporalCoreWorkerVersioningStrategy.none(strategy, noneStrategy)
        }
        return strategy
    }

    private fun List<String>.toFfmByteArrayRefArray(arena: Arena): MemorySegment {
        val arr = TemporalCoreByteArrayRefArray.allocate(arena)
        if (isEmpty()) {
            TemporalCoreByteArrayRefArray.data(arr, MemorySegment.NULL)
            TemporalCoreByteArrayRefArray.size(arr, 0L)
        } else {
            val refByteSize = TemporalCoreByteArrayRef.layout().byteSize()
            val refsSegment = arena.allocate(refByteSize * size)
            forEachIndexed { i, str ->
                val ref = refsSegment.asSlice(refByteSize * i, refByteSize)
                val bytes = str.toByteArray(Charsets.UTF_8)
                val dataSegment = arena.allocate(bytes.size.toLong())
                MemorySegment.copy(bytes, 0, dataSegment, ValueLayout.JAVA_BYTE, 0, bytes.size)
                TemporalCoreByteArrayRef.data(ref, dataSegment)
                TemporalCoreByteArrayRef.size(ref, bytes.size.toLong())
            }
            TemporalCoreByteArrayRefArray.data(arr, refsSegment)
            TemporalCoreByteArrayRefArray.size(arr, size.toLong())
        }
        return arr
    }

    private fun createEmptyByteArrayRefArray(arena: Arena): MemorySegment {
        val arr = TemporalCoreByteArrayRefArray.allocate(arena)
        TemporalCoreByteArrayRefArray.data(arr, MemorySegment.NULL)
        TemporalCoreByteArrayRefArray.size(arr, 0L)
        return arr
    }

    @Suppress("DEPRECATION")
    private fun initializeSlotSupplier(
        slotSupplier: MemorySegment,
        config: SlotSupplier,
        arena: Arena,
        bridges: MutableList<SlotSupplierBridgeEntry>,
        slotType: String = "",
    ) {
        when (config) {
            is SlotSupplier.FixedSize -> {
                TemporalCoreSlotSupplier.tag(slotSupplier, CoreBridge.FixedSize())
                val fixedSize = TemporalCoreSlotSupplier.fixed_size(slotSupplier)
                TemporalCoreFixedSizeSlotSupplier.num_slots(fixedSize, config.slots.toLong())
            }

            is SlotSupplier.CGroupResourceBased -> {
                TemporalCoreSlotSupplier.tag(slotSupplier, CoreBridge.ResourceBased())
                val resourceBased = TemporalCoreSlotSupplier.resource_based(slotSupplier)
                TemporalCoreResourceBasedSlotSupplier.minimum_slots(resourceBased, config.minimumSlots.toLong())
                TemporalCoreResourceBasedSlotSupplier.maximum_slots(resourceBased, config.maximumSlots.toLong())
                TemporalCoreResourceBasedSlotSupplier.ramp_throttle_ms(resourceBased, config.rampThrottleMs)
                val tunerOptions = TemporalCoreResourceBasedSlotSupplier.tuner_options(resourceBased)
                TemporalCoreResourceBasedTunerOptions.target_memory_usage(tunerOptions, config.targetMemoryUsage)
                TemporalCoreResourceBasedTunerOptions.target_cpu_usage(tunerOptions, config.targetCpuUsage)
            }

            is SlotSupplier.JvmResourceBased -> {
                TemporalCoreSlotSupplier.tag(slotSupplier, CoreBridge.Custom())
                val bridge = CustomSlotSupplierBridge(config.maximumSlots, config.minimumSlots)
                bridges.add(SlotSupplierBridgeEntry(slotType, bridge))

                val callbacks = TemporalCoreCustomSlotSupplierCallbacksStruct.allocate(arena)
                TemporalCoreCustomSlotSupplierCallbacksStruct.reserve(
                    callbacks,
                    TemporalCoreCustomSlotSupplierReserveCallback.allocate(bridge::onReserve, arena),
                )
                TemporalCoreCustomSlotSupplierCallbacksStruct.cancel_reserve(
                    callbacks,
                    TemporalCoreCustomSlotSupplierCancelReserveCallback.allocate(bridge::onCancelReserve, arena),
                )
                TemporalCoreCustomSlotSupplierCallbacksStruct.try_reserve(
                    callbacks,
                    TemporalCoreCustomSlotSupplierTryReserveCallback.allocate(bridge::onTryReserve, arena),
                )
                TemporalCoreCustomSlotSupplierCallbacksStruct.mark_used(
                    callbacks,
                    TemporalCoreCustomSlotSupplierMarkUsedCallback.allocate(bridge::onMarkUsed, arena),
                )
                TemporalCoreCustomSlotSupplierCallbacksStruct.release(
                    callbacks,
                    TemporalCoreCustomSlotSupplierReleaseCallback.allocate(bridge::onRelease, arena),
                )
                TemporalCoreCustomSlotSupplierCallbacksStruct.available_slots(
                    callbacks,
                    TemporalCoreCustomSlotSupplierAvailableSlotsCallback.allocate(bridge::onAvailableSlots, arena),
                )
                TemporalCoreCustomSlotSupplierCallbacksStruct.free(
                    callbacks,
                    TemporalCoreCustomSlotSupplierFreeCallback.allocate(bridge::onFree, arena),
                )
                TemporalCoreCustomSlotSupplierCallbacksStruct.user_data(callbacks, MemorySegment.NULL)

                val customImpl = TemporalCoreSlotSupplier.custom(slotSupplier)
                TemporalCoreCustomSlotSupplierCallbacksImpl._0(customImpl, callbacks)
            }
        }
    }
}
