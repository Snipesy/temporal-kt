package com.surrealdev.temporal.client.history

import io.temporal.api.enums.v1.EventType

/**
 * Kotlin-friendly event type enum for workflow history events.
 *
 * This wraps the protobuf [EventType] to avoid exposing proto types in the public API.
 */
enum class TemporalEventType {
    WORKFLOW_EXECUTION_STARTED,
    WORKFLOW_EXECUTION_COMPLETED,
    WORKFLOW_EXECUTION_FAILED,
    WORKFLOW_EXECUTION_TIMED_OUT,
    WORKFLOW_EXECUTION_CANCELED,
    WORKFLOW_EXECUTION_TERMINATED,
    WORKFLOW_EXECUTION_CONTINUED_AS_NEW,
    WORKFLOW_EXECUTION_SIGNALED,
    WORKFLOW_EXECUTION_CANCEL_REQUESTED,
    WORKFLOW_EXECUTION_PAUSED,
    WORKFLOW_EXECUTION_UNPAUSED,
    WORKFLOW_EXECUTION_OPTIONS_UPDATED,
    WORKFLOW_EXECUTION_UPDATE_ADMITTED,
    WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
    WORKFLOW_EXECUTION_UPDATE_REJECTED,
    WORKFLOW_EXECUTION_UPDATE_COMPLETED,
    WORKFLOW_TASK_SCHEDULED,
    WORKFLOW_TASK_STARTED,
    WORKFLOW_TASK_COMPLETED,
    WORKFLOW_TASK_TIMED_OUT,
    WORKFLOW_TASK_FAILED,
    ACTIVITY_TASK_SCHEDULED,
    ACTIVITY_TASK_STARTED,
    ACTIVITY_TASK_COMPLETED,
    ACTIVITY_TASK_FAILED,
    ACTIVITY_TASK_TIMED_OUT,
    ACTIVITY_TASK_CANCEL_REQUESTED,
    ACTIVITY_TASK_CANCELED,
    TIMER_STARTED,
    TIMER_FIRED,
    TIMER_CANCELED,
    START_CHILD_WORKFLOW_EXECUTION_INITIATED,
    START_CHILD_WORKFLOW_EXECUTION_FAILED,
    CHILD_WORKFLOW_EXECUTION_STARTED,
    CHILD_WORKFLOW_EXECUTION_COMPLETED,
    CHILD_WORKFLOW_EXECUTION_FAILED,
    CHILD_WORKFLOW_EXECUTION_CANCELED,
    CHILD_WORKFLOW_EXECUTION_TERMINATED,
    CHILD_WORKFLOW_EXECUTION_TIMED_OUT,
    SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
    SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED,
    EXTERNAL_WORKFLOW_EXECUTION_SIGNALED,
    REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
    REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED,
    EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED,
    MARKER_RECORDED,
    UPSERT_WORKFLOW_SEARCH_ATTRIBUTES,
    WORKFLOW_PROPERTIES_MODIFIED,
    WORKFLOW_PROPERTIES_MODIFIED_EXTERNALLY,
    ACTIVITY_PROPERTIES_MODIFIED_EXTERNALLY,
    NEXUS_OPERATION_SCHEDULED,
    NEXUS_OPERATION_STARTED,
    NEXUS_OPERATION_COMPLETED,
    NEXUS_OPERATION_FAILED,
    NEXUS_OPERATION_CANCELED,
    NEXUS_OPERATION_TIMED_OUT,
    NEXUS_OPERATION_CANCEL_REQUESTED,
    NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED,
    NEXUS_OPERATION_CANCEL_REQUEST_FAILED,
    UNKNOWN,
    ;

    companion object {
        internal fun fromProto(proto: EventType): TemporalEventType =
            when (proto) {
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED -> {
                    WORKFLOW_EXECUTION_STARTED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED -> {
                    WORKFLOW_EXECUTION_COMPLETED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED -> {
                    WORKFLOW_EXECUTION_FAILED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT -> {
                    WORKFLOW_EXECUTION_TIMED_OUT
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED -> {
                    WORKFLOW_EXECUTION_CANCELED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED -> {
                    WORKFLOW_EXECUTION_TERMINATED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW -> {
                    WORKFLOW_EXECUTION_CONTINUED_AS_NEW
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED -> {
                    WORKFLOW_EXECUTION_SIGNALED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED -> {
                    WORKFLOW_EXECUTION_CANCEL_REQUESTED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_PAUSED -> {
                    WORKFLOW_EXECUTION_PAUSED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UNPAUSED -> {
                    WORKFLOW_EXECUTION_UNPAUSED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED -> {
                    WORKFLOW_EXECUTION_OPTIONS_UPDATED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED -> {
                    WORKFLOW_EXECUTION_UPDATE_ADMITTED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED -> {
                    WORKFLOW_EXECUTION_UPDATE_ACCEPTED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED -> {
                    WORKFLOW_EXECUTION_UPDATE_REJECTED
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED -> {
                    WORKFLOW_EXECUTION_UPDATE_COMPLETED
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED -> {
                    WORKFLOW_TASK_SCHEDULED
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED -> {
                    WORKFLOW_TASK_STARTED
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED -> {
                    WORKFLOW_TASK_COMPLETED
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT -> {
                    WORKFLOW_TASK_TIMED_OUT
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED -> {
                    WORKFLOW_TASK_FAILED
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
                    ACTIVITY_TASK_SCHEDULED
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED -> {
                    ACTIVITY_TASK_STARTED
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED -> {
                    ACTIVITY_TASK_COMPLETED
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED -> {
                    ACTIVITY_TASK_FAILED
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT -> {
                    ACTIVITY_TASK_TIMED_OUT
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED -> {
                    ACTIVITY_TASK_CANCEL_REQUESTED
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED -> {
                    ACTIVITY_TASK_CANCELED
                }

                EventType.EVENT_TYPE_TIMER_STARTED -> {
                    TIMER_STARTED
                }

                EventType.EVENT_TYPE_TIMER_FIRED -> {
                    TIMER_FIRED
                }

                EventType.EVENT_TYPE_TIMER_CANCELED -> {
                    TIMER_CANCELED
                }

                EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED -> {
                    START_CHILD_WORKFLOW_EXECUTION_INITIATED
                }

                EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED -> {
                    START_CHILD_WORKFLOW_EXECUTION_FAILED
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED -> {
                    CHILD_WORKFLOW_EXECUTION_STARTED
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED -> {
                    CHILD_WORKFLOW_EXECUTION_COMPLETED
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED -> {
                    CHILD_WORKFLOW_EXECUTION_FAILED
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED -> {
                    CHILD_WORKFLOW_EXECUTION_CANCELED
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED -> {
                    CHILD_WORKFLOW_EXECUTION_TERMINATED
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT -> {
                    CHILD_WORKFLOW_EXECUTION_TIMED_OUT
                }

                EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED -> {
                    SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
                }

                EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED -> {
                    SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED
                }

                EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED -> {
                    EXTERNAL_WORKFLOW_EXECUTION_SIGNALED
                }

                EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED -> {
                    REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
                }

                EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED -> {
                    REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED
                }

                EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED -> {
                    EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED
                }

                EventType.EVENT_TYPE_MARKER_RECORDED -> {
                    MARKER_RECORDED
                }

                EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES -> {
                    UPSERT_WORKFLOW_SEARCH_ATTRIBUTES
                }

                EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED -> {
                    WORKFLOW_PROPERTIES_MODIFIED
                }

                EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED_EXTERNALLY -> {
                    WORKFLOW_PROPERTIES_MODIFIED_EXTERNALLY
                }

                EventType.EVENT_TYPE_ACTIVITY_PROPERTIES_MODIFIED_EXTERNALLY -> {
                    ACTIVITY_PROPERTIES_MODIFIED_EXTERNALLY
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED -> {
                    NEXUS_OPERATION_SCHEDULED
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED -> {
                    NEXUS_OPERATION_STARTED
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED -> {
                    NEXUS_OPERATION_COMPLETED
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED -> {
                    NEXUS_OPERATION_FAILED
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED -> {
                    NEXUS_OPERATION_CANCELED
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT -> {
                    NEXUS_OPERATION_TIMED_OUT
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED -> {
                    NEXUS_OPERATION_CANCEL_REQUESTED
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED -> {
                    NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_FAILED -> {
                    NEXUS_OPERATION_CANCEL_REQUEST_FAILED
                }

                else -> {
                    UNKNOWN
                }
            }
    }
}
