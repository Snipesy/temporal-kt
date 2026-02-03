package com.surrealdev.temporal.testing

import com.google.protobuf.ByteString
import com.google.protobuf.Duration
import com.google.protobuf.Timestamp
import coresdk.activity_result.ActivityResult
import coresdk.child_workflow.ChildWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass
import coresdk.workflow_activation.WorkflowActivationOuterClass.CancelWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.DoUpdate
import coresdk.workflow_activation.WorkflowActivationOuterClass.FireTimer
import coresdk.workflow_activation.WorkflowActivationOuterClass.InitializeWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.NotifyHasPatch
import coresdk.workflow_activation.WorkflowActivationOuterClass.QueryWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.RemoveFromCache
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveActivity
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecution
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStart
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStartSuccess
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveNexusOperation
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveNexusOperationStart
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveRequestCancelExternalWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveSignalExternalWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.SignalWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.UpdateRandomSeed
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import java.util.UUID

/**
 * Factory functions for creating proto objects for testing purposes.
 * These helpers allow creating activation jobs without mocking frameworks.
 */
object ProtoTestHelpers {
    /**
     * Creates a WorkflowActivation with the given jobs.
     */
    fun createActivation(
        runId: String = UUID.randomUUID().toString(),
        jobs: List<WorkflowActivationJob>,
        timestamp: Timestamp? = Timestamp.newBuilder().setSeconds(1000).build(),
        isReplaying: Boolean = false,
        historyLength: Int = 1,
    ): WorkflowActivation {
        val builder =
            WorkflowActivation
                .newBuilder()
                .setRunId(runId)
                .setIsReplaying(isReplaying)
                .setHistoryLength(historyLength)
                .addAllJobs(jobs)

        if (timestamp != null) {
            builder.timestamp = timestamp
        }

        return builder.build()
    }

    /**
     * Creates an InitializeWorkflow job.
     */
    fun initializeWorkflowJob(
        workflowId: String = "test-workflow-${UUID.randomUUID()}",
        workflowType: String = "TestWorkflow",
        arguments: List<Payload> = emptyList(),
        randomnessSeed: Long = 12345L,
        attempt: Int = 1,
    ): WorkflowActivationJob {
        val initWorkflow =
            InitializeWorkflow
                .newBuilder()
                .setWorkflowId(workflowId)
                .setWorkflowType(workflowType)
                .setRandomnessSeed(randomnessSeed)
                .setAttempt(attempt)
                .addAllArguments(arguments)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setInitializeWorkflow(initWorkflow)
            .build()
    }

    /**
     * Creates a FireTimer job.
     */
    fun fireTimerJob(seq: Int): WorkflowActivationJob {
        val fireTimer =
            FireTimer
                .newBuilder()
                .setSeq(seq)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setFireTimer(fireTimer)
            .build()
    }

    /**
     * Creates a ResolveActivity job with a completed result.
     */
    fun resolveActivityJobCompleted(
        seq: Int,
        result: Payload = Payload.getDefaultInstance(),
    ): WorkflowActivationJob {
        val completed =
            ActivityResult.Success
                .newBuilder()
                .setResult(result)
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setCompleted(completed)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a ResolveActivity job with a failed result.
     */
    fun resolveActivityJobFailed(
        seq: Int,
        message: String = "Activity failed",
    ): WorkflowActivationJob {
        val failure =
            Failure
                .newBuilder()
                .setMessage(message)
                .build()

        val failed =
            ActivityResult.Failure
                .newBuilder()
                .setFailure(failure)
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setFailed(failed)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a ResolveActivity job with a cancelled result.
     */
    fun resolveActivityJobCancelled(seq: Int): WorkflowActivationJob {
        val cancelled =
            ActivityResult.Cancellation
                .newBuilder()
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setCancelled(cancelled)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    // ================================================================
    // Local Activity Resolution Jobs
    // ================================================================

    /**
     * Creates a ResolveActivity job for a local activity with a completed result.
     * Local activities use the same ResolveActivity proto but with isLocal=true.
     */
    fun resolveLocalActivityJobCompleted(
        seq: Int,
        result: Payload = Payload.getDefaultInstance(),
    ): WorkflowActivationJob {
        val completed =
            ActivityResult.Success
                .newBuilder()
                .setResult(result)
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setCompleted(completed)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .setIsLocal(true)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a ResolveActivity job for a local activity with a failed result.
     */
    fun resolveLocalActivityJobFailed(
        seq: Int,
        message: String = "Local activity failed",
    ): WorkflowActivationJob {
        val failure =
            Failure
                .newBuilder()
                .setMessage(message)
                .build()

        val failed =
            ActivityResult.Failure
                .newBuilder()
                .setFailure(failure)
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setFailed(failed)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .setIsLocal(true)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a ResolveActivity job for a local activity with a cancelled result.
     */
    fun resolveLocalActivityJobCancelled(seq: Int): WorkflowActivationJob {
        val cancelled =
            ActivityResult.Cancellation
                .newBuilder()
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setCancelled(cancelled)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .setIsLocal(true)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a ResolveActivity job for a local activity with a backoff result.
     * This signals that lang should schedule a timer and retry the activity.
     *
     * @param seq The sequence number of the local activity
     * @param attempt The NEXT attempt number (the one to use when retrying)
     * @param backoffSeconds How long to wait before retrying
     * @param originalScheduleTime Optional timestamp of when the first attempt was scheduled
     */
    fun resolveLocalActivityJobBackoff(
        seq: Int,
        attempt: Int = 2,
        backoffSeconds: Long = 5,
        originalScheduleTime: com.google.protobuf.Timestamp? = null,
    ): WorkflowActivationJob {
        val backoffBuilder =
            ActivityResult.DoBackoff
                .newBuilder()
                .setAttempt(attempt)
                .setBackoffDuration(
                    Duration
                        .newBuilder()
                        .setSeconds(backoffSeconds)
                        .build(),
                )

        originalScheduleTime?.let { backoffBuilder.setOriginalScheduleTime(it) }

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setBackoff(backoffBuilder.build())
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .setIsLocal(true)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a ResolveActivity job for a local activity with a timeout failure.
     *
     * @param seq The sequence number of the local activity
     * @param timeoutType The type of timeout (START_TO_CLOSE, SCHEDULE_TO_CLOSE, etc.)
     * @param message Optional message for the timeout
     */
    fun resolveLocalActivityJobTimeout(
        seq: Int,
        timeoutType: io.temporal.api.enums.v1.TimeoutType = io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE,
        message: String = "Local activity timed out",
    ): WorkflowActivationJob {
        val timeoutInfo =
            io.temporal.api.failure.v1.TimeoutFailureInfo
                .newBuilder()
                .setTimeoutType(timeoutType)
                .build()

        val failure =
            Failure
                .newBuilder()
                .setMessage(message)
                .setTimeoutFailureInfo(timeoutInfo)
                .build()

        val failed =
            ActivityResult.Failure
                .newBuilder()
                .setFailure(failure)
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setFailed(failed)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .setIsLocal(true)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a ResolveActivity job for a local activity with detailed failure information.
     *
     * @param seq The sequence number of the local activity
     * @param message The failure message
     * @param errorType The error type (e.g., "RuntimeException")
     * @param causeMessage Optional message for the cause
     */
    fun resolveLocalActivityJobFailedWithDetails(
        seq: Int,
        message: String,
        errorType: String = "ApplicationFailure",
        causeMessage: String? = null,
    ): WorkflowActivationJob {
        val failureBuilder =
            Failure
                .newBuilder()
                .setMessage(message)
                .setApplicationFailureInfo(
                    io.temporal.api.failure.v1.ApplicationFailureInfo
                        .newBuilder()
                        .setType(errorType)
                        .setNonRetryable(false)
                        .build(),
                )

        if (causeMessage != null) {
            failureBuilder.setCause(
                Failure
                    .newBuilder()
                    .setMessage(causeMessage)
                    .build(),
            )
        }

        val failed =
            ActivityResult.Failure
                .newBuilder()
                .setFailure(failureBuilder.build())
                .build()

        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setFailed(failed)
                .build()

        val resolveActivity =
            ResolveActivity
                .newBuilder()
                .setSeq(seq)
                .setResult(resolution)
                .setIsLocal(true)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveActivity(resolveActivity)
            .build()
    }

    /**
     * Creates a QueryWorkflow job.
     */
    fun queryWorkflowJob(
        queryId: String = UUID.randomUUID().toString(),
        queryType: String = "TestQuery",
        arguments: List<Payload> = emptyList(),
    ): WorkflowActivationJob {
        val query =
            QueryWorkflow
                .newBuilder()
                .setQueryId(queryId)
                .setQueryType(queryType)
                .addAllArguments(arguments)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setQueryWorkflow(query)
            .build()
    }

    /**
     * Creates a SignalWorkflow job.
     */
    fun signalWorkflowJob(
        signalName: String = "TestSignal",
        input: List<Payload> = emptyList(),
    ): WorkflowActivationJob {
        val signal =
            SignalWorkflow
                .newBuilder()
                .setSignalName(signalName)
                .addAllInput(input)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setSignalWorkflow(signal)
            .build()
    }

    /**
     * Creates a DoUpdate job.
     *
     * @param id A workflow-unique identifier for this update
     * @param protocolInstanceId The protocol message instance ID (used for response tracking)
     * @param name The name of the update handler
     * @param input The input payloads to the update
     * @param runValidator If true, lang must run the update's validator before the handler
     */
    fun doUpdateJob(
        id: String = UUID.randomUUID().toString(),
        protocolInstanceId: String = "update-protocol-${UUID.randomUUID()}",
        name: String = "TestUpdate",
        input: List<Payload> = emptyList(),
        runValidator: Boolean = true,
    ): WorkflowActivationJob {
        val update =
            DoUpdate
                .newBuilder()
                .setId(id)
                .setProtocolInstanceId(protocolInstanceId)
                .setName(name)
                .addAllInput(input)
                .setRunValidator(runValidator)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setDoUpdate(update)
            .build()
    }

    /**
     * Creates a NotifyHasPatch job.
     */
    fun notifyHasPatchJob(patchId: String = "test-patch"): WorkflowActivationJob {
        val patch =
            NotifyHasPatch
                .newBuilder()
                .setPatchId(patchId)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setNotifyHasPatch(patch)
            .build()
    }

    /**
     * Creates a CancelWorkflow job.
     */
    fun cancelWorkflowJob(): WorkflowActivationJob {
        val cancel = CancelWorkflow.newBuilder().build()

        return WorkflowActivationJob
            .newBuilder()
            .setCancelWorkflow(cancel)
            .build()
    }

    /**
     * Creates a RemoveFromCache (eviction) job.
     */
    fun removeFromCacheJob(message: String = "Cache eviction"): WorkflowActivationJob {
        val remove =
            RemoveFromCache
                .newBuilder()
                .setMessage(message)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setRemoveFromCache(remove)
            .build()
    }

    /**
     * Creates an UpdateRandomSeed job.
     */
    fun updateRandomSeedJob(seed: Long = 54321L): WorkflowActivationJob {
        val updateSeed =
            UpdateRandomSeed
                .newBuilder()
                .setRandomnessSeed(seed)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setUpdateRandomSeed(updateSeed)
            .build()
    }

    /**
     * Creates a ResolveChildWorkflowExecutionStart job (successful start).
     */
    fun resolveChildWorkflowStartJob(
        seq: Int,
        runId: String = UUID.randomUUID().toString(),
    ): WorkflowActivationJob {
        val succeeded =
            ResolveChildWorkflowExecutionStartSuccess
                .newBuilder()
                .setRunId(runId)
                .build()

        val resolve =
            ResolveChildWorkflowExecutionStart
                .newBuilder()
                .setSeq(seq)
                .setSucceeded(succeeded)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveChildWorkflowExecutionStart(resolve)
            .build()
    }

    /**
     * Creates a ResolveChildWorkflowExecutionStart job (failed to start).
     */
    fun resolveChildWorkflowStartFailedJob(
        seq: Int,
        workflowId: String = "child-workflow-id",
        workflowType: String = "ChildWorkflow",
        cause: ChildWorkflow.StartChildWorkflowExecutionFailedCause =
            ChildWorkflow.StartChildWorkflowExecutionFailedCause
                .START_CHILD_WORKFLOW_EXECUTION_FAILED_CAUSE_WORKFLOW_ALREADY_EXISTS,
    ): WorkflowActivationJob {
        val failed =
            WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStartFailure
                .newBuilder()
                .setWorkflowId(workflowId)
                .setWorkflowType(workflowType)
                .setCause(cause)
                .build()

        val resolve =
            ResolveChildWorkflowExecutionStart
                .newBuilder()
                .setSeq(seq)
                .setFailed(failed)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveChildWorkflowExecutionStart(resolve)
            .build()
    }

    /**
     * Creates a ResolveChildWorkflowExecutionStart job (cancelled before start).
     */
    fun resolveChildWorkflowStartCancelledJob(
        seq: Int,
        message: String = "Child workflow cancelled before start",
    ): WorkflowActivationJob {
        val cancelled =
            WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStartCancelled
                .newBuilder()
                .setFailure(
                    Failure
                        .newBuilder()
                        .setMessage(message)
                        .build(),
                ).build()

        val resolve =
            ResolveChildWorkflowExecutionStart
                .newBuilder()
                .setSeq(seq)
                .setCancelled(cancelled)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveChildWorkflowExecutionStart(resolve)
            .build()
    }

    /**
     * Creates a ResolveChildWorkflowExecution job (completed).
     */
    fun resolveChildWorkflowExecutionJob(
        seq: Int,
        result: Payload = Payload.getDefaultInstance(),
    ): WorkflowActivationJob {
        val childResult =
            ChildWorkflow.ChildWorkflowResult
                .newBuilder()
                .setCompleted(
                    ChildWorkflow.Success
                        .newBuilder()
                        .setResult(result)
                        .build(),
                ).build()

        val resolve =
            ResolveChildWorkflowExecution
                .newBuilder()
                .setSeq(seq)
                .setResult(childResult)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveChildWorkflowExecution(resolve)
            .build()
    }

    /**
     * Creates a ResolveChildWorkflowExecution job (failed).
     */
    fun resolveChildWorkflowExecutionFailedJob(
        seq: Int,
        message: String = "Child workflow failed",
    ): WorkflowActivationJob {
        val childResult =
            ChildWorkflow.ChildWorkflowResult
                .newBuilder()
                .setFailed(
                    ChildWorkflow.Failure
                        .newBuilder()
                        .setFailure(
                            Failure
                                .newBuilder()
                                .setMessage(message)
                                .build(),
                        ).build(),
                ).build()

        val resolve =
            ResolveChildWorkflowExecution
                .newBuilder()
                .setSeq(seq)
                .setResult(childResult)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveChildWorkflowExecution(resolve)
            .build()
    }

    /**
     * Creates a ResolveChildWorkflowExecution job (cancelled).
     */
    fun resolveChildWorkflowExecutionCancelledJob(
        seq: Int,
        message: String = "Child workflow cancelled",
    ): WorkflowActivationJob {
        val childResult =
            ChildWorkflow.ChildWorkflowResult
                .newBuilder()
                .setCancelled(
                    ChildWorkflow.Cancellation
                        .newBuilder()
                        .setFailure(
                            Failure
                                .newBuilder()
                                .setMessage(message)
                                .build(),
                        ).build(),
                ).build()

        val resolve =
            ResolveChildWorkflowExecution
                .newBuilder()
                .setSeq(seq)
                .setResult(childResult)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveChildWorkflowExecution(resolve)
            .build()
    }

    /**
     * Creates a ResolveSignalExternalWorkflow job.
     */
    fun resolveSignalExternalWorkflowJob(seq: Int): WorkflowActivationJob {
        val resolve =
            ResolveSignalExternalWorkflow
                .newBuilder()
                .setSeq(seq)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveSignalExternalWorkflow(resolve)
            .build()
    }

    /**
     * Creates a ResolveRequestCancelExternalWorkflow job.
     */
    fun resolveCancelExternalWorkflowJob(seq: Int): WorkflowActivationJob {
        val resolve =
            ResolveRequestCancelExternalWorkflow
                .newBuilder()
                .setSeq(seq)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveRequestCancelExternalWorkflow(resolve)
            .build()
    }

    /**
     * Creates a ResolveNexusOperationStart job.
     */
    fun resolveNexusOperationStartJob(seq: Int): WorkflowActivationJob {
        val resolve =
            ResolveNexusOperationStart
                .newBuilder()
                .setSeq(seq)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveNexusOperationStart(resolve)
            .build()
    }

    /**
     * Creates a ResolveNexusOperation job.
     */
    fun resolveNexusOperationJob(seq: Int): WorkflowActivationJob {
        val resolve =
            ResolveNexusOperation
                .newBuilder()
                .setSeq(seq)
                .build()

        return WorkflowActivationJob
            .newBuilder()
            .setResolveNexusOperation(resolve)
            .build()
    }

    /**
     * Creates a Timestamp from seconds and nanos.
     */
    fun timestamp(
        seconds: Long,
        nanos: Int = 0,
    ): Timestamp =
        Timestamp
            .newBuilder()
            .setSeconds(seconds)
            .setNanos(nanos)
            .build()

    /**
     * Creates a Duration from seconds.
     */
    fun duration(
        seconds: Long,
        nanos: Int = 0,
    ): Duration =
        Duration
            .newBuilder()
            .setSeconds(seconds)
            .setNanos(nanos)
            .build()

    /**
     * Creates a Payload from a string.
     */
    fun payload(data: String): Payload =
        Payload
            .newBuilder()
            .setData(ByteString.copyFromUtf8(data))
            .build()
}
