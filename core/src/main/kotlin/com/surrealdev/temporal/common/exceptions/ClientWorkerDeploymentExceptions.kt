package com.surrealdev.temporal.common.exceptions

/**
 * The server has no worker deployment by this name, or no such version of it when [buildId] is set.
 * Deployments come into being when the first worker declaring them polls, so this is the normal
 * state for a short while after startup.
 */
class ClientWorkerDeploymentNotFoundException(
    val deploymentName: String,
    val buildId: String? = null,
    message: String =
        if (buildId == null) {
            "Worker deployment not found: $deploymentName"
        } else {
            "Worker deployment version not found: $deploymentName/$buildId"
        },
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause)
