package com.surrealdev.temporal.client.internal

import com.surrealdev.temporal.common.exceptions.ClientPermissionDeniedException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowAlreadyExistsException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowNotFoundException
import com.surrealdev.temporal.core.TemporalCoreException

// Standard gRPC status codes (https://grpc.github.io/grpc/core/md_doc_statuscodes.html).
// io.grpc is not on the classpath in this module, so these are kept as constants.
internal const val GRPC_CANCELLED = 1
internal const val GRPC_DEADLINE_EXCEEDED = 4
internal const val GRPC_NOT_FOUND = 5
internal const val GRPC_ALREADY_EXISTS = 6
internal const val GRPC_PERMISSION_DENIED = 7

/**
 * Maps common gRPC status codes to typed domain exceptions and rethrows.
 *
 * - NOT_FOUND (5)        → [ClientWorkflowNotFoundException]
 * - ALREADY_EXISTS (6)   → [ClientWorkflowAlreadyExistsException]
 * - PERMISSION_DENIED (7) → [ClientPermissionDeniedException]
 * - All others            → re-throws as-is
 *
 * @param workflowId The workflow ID involved in the operation, used to populate NOT_FOUND
 *   and ALREADY_EXISTS exceptions.
 * @param runId The run ID involved in the operation, used to populate NOT_FOUND exceptions.
 */
internal fun TemporalCoreException.rethrowMapped(
    workflowId: String,
    runId: String? = null,
): Nothing =
    when (statusCode) {
        GRPC_NOT_FOUND -> {
            throw ClientWorkflowNotFoundException(workflowId = workflowId, runId = runId, cause = this)
        }

        GRPC_ALREADY_EXISTS -> {
            throw ClientWorkflowAlreadyExistsException(workflowId = workflowId, existingRunId = null, cause = this)
        }

        GRPC_PERMISSION_DENIED -> {
            throw ClientPermissionDeniedException(cause = this)
        }

        else -> {
            throw this
        }
    }
