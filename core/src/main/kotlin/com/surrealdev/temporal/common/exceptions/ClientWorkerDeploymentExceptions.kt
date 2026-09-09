package com.surrealdev.temporal.common.exceptions

/**
 * The server has no worker deployment by this name. Deployments come into being when the first
 * worker declaring them polls, so this is the normal state for a short while after startup.
 */
class ClientWorkerDeploymentNotFoundException(
    val deploymentName: String,
    message: String = "Worker deployment not found: $deploymentName",
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause)
