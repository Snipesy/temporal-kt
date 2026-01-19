package com.surrealdev.temporal.core.internal

/**
 * FFM bridge for Temporal Core worker operations.
 *
 * Workers poll for and execute workflow and activity tasks.
 */
internal object TemporalCoreWorker {
    // ============================================================
    // Worker Lifecycle
    // ============================================================

    // TODO: temporal_core_worker_new
    // WorkerOrFail temporal_core_worker_new(client, options)

    // TODO: temporal_core_worker_free
    // void temporal_core_worker_free(worker)

    // TODO: temporal_core_worker_validate
    // void temporal_core_worker_validate(worker, user_data, callback)

    // TODO: temporal_core_worker_replace_client
    // ByteArray* temporal_core_worker_replace_client(worker, new_client)

    // ============================================================
    // Polling
    // ============================================================

    // TODO: temporal_core_worker_poll_workflow_activation
    // void temporal_core_worker_poll_workflow_activation(worker, user_data, callback)

    // TODO: temporal_core_worker_poll_activity_task
    // void temporal_core_worker_poll_activity_task(worker, user_data, callback)

    // TODO: temporal_core_worker_poll_nexus_task
    // void temporal_core_worker_poll_nexus_task(worker, user_data, callback)

    // ============================================================
    // Completions
    // ============================================================

    // TODO: temporal_core_worker_complete_workflow_activation
    // void temporal_core_worker_complete_workflow_activation(worker, completion, user_data, callback)

    // TODO: temporal_core_worker_complete_activity_task
    // void temporal_core_worker_complete_activity_task(worker, completion, user_data, callback)

    // TODO: temporal_core_worker_complete_nexus_task
    // void temporal_core_worker_complete_nexus_task(worker, completion, user_data, callback)

    // ============================================================
    // Activity Heartbeat
    // ============================================================

    // TODO: temporal_core_worker_record_activity_heartbeat
    // ByteArray* temporal_core_worker_record_activity_heartbeat(worker, heartbeat)

    // ============================================================
    // Workflow Management
    // ============================================================

    // TODO: temporal_core_worker_request_workflow_eviction
    // void temporal_core_worker_request_workflow_eviction(worker, run_id)

    // ============================================================
    // Shutdown
    // ============================================================

    // TODO: temporal_core_worker_initiate_shutdown
    // void temporal_core_worker_initiate_shutdown(worker)

    // TODO: temporal_core_worker_finalize_shutdown
    // void temporal_core_worker_finalize_shutdown(worker, user_data, callback)

    // ============================================================
    // Replay
    // ============================================================

    // TODO: temporal_core_worker_replayer_new
    // WorkerReplayerOrFail temporal_core_worker_replayer_new(runtime, options)

    // TODO: temporal_core_worker_replay_pusher_free
    // void temporal_core_worker_replay_pusher_free(pusher)

    // TODO: temporal_core_worker_replay_push
    // ReplayPushResult temporal_core_worker_replay_push(worker, pusher, workflow_id, history)

    // ============================================================
    // Async Slot Reservation
    // ============================================================

    // TODO: temporal_core_complete_async_reserve
    // bool temporal_core_complete_async_reserve(completion_ctx, permit_id)

    // TODO: temporal_core_complete_async_cancel_reserve
    // bool temporal_core_complete_async_cancel_reserve(completion_ctx)
}
