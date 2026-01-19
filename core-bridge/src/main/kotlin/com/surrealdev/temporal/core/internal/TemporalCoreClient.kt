package com.surrealdev.temporal.core.internal

/**
 * FFM bridge for Temporal Core client operations.
 *
 * The client provides connectivity to the Temporal server for starting
 * workflows, sending signals, and other operations.
 */
internal object TemporalCoreClient {
    // TODO: temporal_core_client_connect
    // void temporal_core_client_connect(runtime, options, user_data, callback)

    // TODO: temporal_core_client_free
    // void temporal_core_client_free(TemporalCoreClient *client)

    // TODO: temporal_core_client_update_metadata
    // void temporal_core_client_update_metadata(client, metadata)

    // TODO: temporal_core_client_update_binary_metadata
    // void temporal_core_client_update_binary_metadata(client, metadata)

    // TODO: temporal_core_client_update_api_key
    // void temporal_core_client_update_api_key(client, api_key)

    // TODO: temporal_core_client_rpc_call
    // void temporal_core_client_rpc_call(client, options, user_data, callback)

    // TODO: temporal_core_client_env_config_load
    // ClientEnvConfigOrFail temporal_core_client_env_config_load(options)

    // TODO: temporal_core_client_env_config_profile_load
    // ClientEnvConfigProfileOrFail temporal_core_client_env_config_profile_load(options)

    // ============================================================
    // gRPC Override API
    // ============================================================

    // TODO: temporal_core_client_grpc_override_request_service
    // ByteArrayRef temporal_core_client_grpc_override_request_service(req)

    // TODO: temporal_core_client_grpc_override_request_rpc
    // ByteArrayRef temporal_core_client_grpc_override_request_rpc(req)

    // TODO: temporal_core_client_grpc_override_request_headers
    // MetadataRef temporal_core_client_grpc_override_request_headers(req)

    // TODO: temporal_core_client_grpc_override_request_proto
    // ByteArrayRef temporal_core_client_grpc_override_request_proto(req)

    // TODO: temporal_core_client_grpc_override_request_respond
    // void temporal_core_client_grpc_override_request_respond(req, resp)

    // ============================================================
    // Cancellation Token API
    // ============================================================

    // TODO: temporal_core_cancellation_token_new
    // TemporalCoreCancellationToken* temporal_core_cancellation_token_new(void)

    // TODO: temporal_core_cancellation_token_cancel
    // void temporal_core_cancellation_token_cancel(token)

    // TODO: temporal_core_cancellation_token_free
    // void temporal_core_cancellation_token_free(token)
}
