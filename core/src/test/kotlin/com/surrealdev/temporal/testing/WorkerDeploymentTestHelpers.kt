package com.surrealdev.temporal.testing

import com.surrealdev.temporal.client.WorkerDeploymentClient
import com.surrealdev.temporal.client.WorkerDeploymentDescription
import com.surrealdev.temporal.common.exceptions.ClientWorkerDeploymentNotFoundException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Describes [name], retrying while the server has not seen the deployment yet. Worker registration is
 * asynchronous: the deployment exists only once the worker's first poll reaches the server.
 */
internal suspend fun WorkerDeploymentClient.awaitDescribe(
    name: String,
    timeout: Duration = 30.seconds,
): WorkerDeploymentDescription =
    withTimeout(timeout) {
        var last: ClientWorkerDeploymentNotFoundException
        do {
            try {
                return@withTimeout describe(name)
            } catch (e: ClientWorkerDeploymentNotFoundException) {
                last = e
            }
            delay(100.milliseconds)
        } while (true)
        @Suppress("UNREACHABLE_CODE")
        throw last
    }
