package com.surrealdev.temporal.protos

import coresdk.activityTaskCompletion
import coresdk.activity_result.success
import io.temporal.api.sdk.v1.workflowMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards the generated protobuf class names that the rest of the SDK compiles against.
 *
 * These protos declare no `java_package` or `java_outer_classname` options, so every generated
 * name is derived from protoc's defaults and the .proto file's path. If upstream ever adds those
 * options, renames a file, or moves a message between files, the JVM class names change --
 * silently breaking source compatibility for anyone who already depends on the published `protos`
 * artifact, and breaking a few hundred call sites in `core` all at once.
 *
 * A failure here is not a bug in this test: it means the generated surface moved, and the change
 * needs to be deliberate. The failing name tells you exactly which one.
 */
class GeneratedProtoNamesTest {
    @Test
    fun `every generated class the SDK imports is still present under the same name`() {
        val missing =
            GOLDEN_CLASS_NAMES.filterNot { name ->
                runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
            }
        assertEquals(
            emptyList(),
            missing,
            "Generated protobuf classes disappeared or were renamed. Either upstream changed the " +
                "protos (update this list deliberately) or protoc naming options were introduced.",
        )
    }

    /**
     * The Kotlin DSL builders are asserted by *calling* them rather than by reflection: they are
     * top-level functions in generated file classes, so a compile error here is the real signal
     * that `protobuf-kotlin` stopped being exported or that the `kotlin` protoc builtin stopped
     * running for this module.
     */
    @Test
    fun `generated kotlin DSL builders are usable`() {
        assertNotNull(activityTaskCompletion { })
        assertNotNull(success { })
        assertNotNull(workflowMetadata { })
    }

    private companion object {
        val GOLDEN_CLASS_NAMES =
            listOf(
                "coresdk.CoreInterface",
                "coresdk.activity_result.ActivityResult",
                "coresdk.activity_task.ActivityTaskOuterClass",
                "coresdk.activity_task.ActivityTaskOuterClass\$ActivityTask",
                "coresdk.activity_task.ActivityTaskOuterClass\$Start",
                "coresdk.child_workflow.ChildWorkflow",
                "coresdk.common.Common\$NamespacedWorkflowExecution",
                "coresdk.workflow_activation.WorkflowActivationOuterClass",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$CancelWorkflow",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$DoUpdate",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$FireTimer",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$InitializeWorkflow",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$NotifyHasPatch",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$QueryWorkflow",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$RemoveFromCache",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveActivity",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveChildWorkflowExecution",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveChildWorkflowExecutionStart",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveChildWorkflowExecutionStartSuccess",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveNexusOperation",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveNexusOperationStart",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveRequestCancelExternalWorkflow",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$ResolveSignalExternalWorkflow",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$SignalWorkflow",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$UpdateRandomSeed",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$WorkflowActivation",
                "coresdk.workflow_activation.WorkflowActivationOuterClass\$WorkflowActivationJob",
                "coresdk.workflow_commands.WorkflowCommands",
                "coresdk.workflow_commands.WorkflowCommands\$WorkflowCommand",
                "coresdk.workflow_completion.WorkflowCompletion",
                "coresdk.workflow_completion.WorkflowCompletion\$WorkflowActivationCompletion",
                "io.temporal.api.common.v1.Payload",
                "io.temporal.api.common.v1.SearchAttributes",
                "io.temporal.api.common.v1.WorkflowExecution",
                "io.temporal.api.common.v1.WorkflowType",
                "io.temporal.api.enums.v1.ContinueAsNewVersioningBehavior",
                "io.temporal.api.enums.v1.EventType",
                "io.temporal.api.enums.v1.HistoryEventFilterType",
                "io.temporal.api.enums.v1.RetryState",
                "io.temporal.api.enums.v1.SuggestContinueAsNewReason",
                "io.temporal.api.enums.v1.TimeoutType",
                "io.temporal.api.enums.v1.WorkflowIdConflictPolicy",
                "io.temporal.api.enums.v1.WorkflowIdReusePolicy",
                "io.temporal.api.failure.v1.ActivityFailureInfo",
                "io.temporal.api.failure.v1.ApplicationFailureInfo",
                "io.temporal.api.failure.v1.Failure",
                "io.temporal.api.history.v1.History",
                "io.temporal.api.history.v1.HistoryEvent",
                "io.temporal.api.taskqueue.v1.TaskQueue",
                "io.temporal.api.testservice.v1.GetCurrentTimeResponse",
                "io.temporal.api.testservice.v1.LockTimeSkippingRequest",
                "io.temporal.api.testservice.v1.SleepRequest",
                "io.temporal.api.testservice.v1.SleepUntilRequest",
                "io.temporal.api.testservice.v1.UnlockTimeSkippingRequest",
                "io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest",
                "io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse",
                "io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest",
                "io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse",
                "io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest",
                "io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse",
                "io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest",
                "io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse",
                "io.temporal.api.workflowservice.v1.QueryWorkflowRequest",
                "io.temporal.api.workflowservice.v1.QueryWorkflowResponse",
                "io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest",
                "io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse",
                "io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest",
                "io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse",
                "io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest",
                "io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse",
                "io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest",
                "io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse",
                "io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionRequest",
                "io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionResponse",
            )
    }
}
