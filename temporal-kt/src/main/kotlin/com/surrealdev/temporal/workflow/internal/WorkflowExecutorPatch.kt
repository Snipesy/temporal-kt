package com.surrealdev.temporal.workflow.internal

import coresdk.workflow_activation.WorkflowActivationOuterClass.NotifyHasPatch
import coresdk.workflow_commands.WorkflowCommands

/*
 * Extension functions for handling workflow versioning (patches) in WorkflowExecutor.
 *
 * Patches allow safe code evolution while maintaining replay compatibility.
 * When a workflow calls patched(), it creates a marker in history that allows
 * the SDK to take the correct code path during replay.
 */

/**
 * Handles a NotifyHasPatch job from Core.
 *
 * This job is sent by Core during replay when a patch marker exists in history.
 * It's sent via "lookahead" before the workflow code runs, allowing patched()
 * to return the correct value synchronously.
 *
 * @param patch The NotifyHasPatch job containing the patch ID
 */
internal fun WorkflowExecutor.handlePatchJob(patch: NotifyHasPatch) {
    logger.debug("Patch notified: patchId={}", patch.patchId)
    state.notifyPatch(patch.patchId)
}

/**
 * Creates a SetPatchMarker command.
 *
 * This command records a patch marker in workflow history. When the workflow
 * is replayed, Core will send a NotifyHasPatch job for this patch ID.
 *
 * @param patchId The unique identifier for this patch/version
 * @param deprecated If true, indicates all workflows now have this patch and
 *                   the old code path can be removed
 * @return The SetPatchMarker command to add to workflow state
 */
internal fun createSetPatchMarkerCommand(
    patchId: String,
    deprecated: Boolean = false,
): WorkflowCommands.WorkflowCommand =
    WorkflowCommands.WorkflowCommand
        .newBuilder()
        .setSetPatchMarker(
            WorkflowCommands.SetPatchMarker
                .newBuilder()
                .setPatchId(patchId)
                .setDeprecated(deprecated),
        ).build()
