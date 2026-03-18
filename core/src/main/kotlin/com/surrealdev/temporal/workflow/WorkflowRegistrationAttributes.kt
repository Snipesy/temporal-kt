package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.util.AttributeKey

/**
 * When set to `true` in the [AttributeScope][com.surrealdev.temporal.util.AttributeScope] hierarchy,
 * the worker will **permanently fail** a workflow execution whose type is not registered on this
 * task queue, rather than returning a task-level failure.
 *
 * ## Background
 *
 * By default, receiving an activation for an unknown
 * workflow type produces a *task* failure. This is intentional in production: a second worker
 * on the same task queue that *does* know about the type will eventually pick up the task and
 * handle it correctly.
 *
 * In single-worker test environments there is no second worker. The task failure is retried
 * indefinitely, and the test hangs until it times out — which gives a confusing error message
 * with no indication of what went wrong.
 *
 * ## What this key does
 *
 * When `StrictWorkflowRegistrationKey` is `true`:
 * - An unregistered workflow type results in a `FailWorkflowExecution` command (permanent failure)
 *   instead of a task-level failure.
 * - The failure message lists the requested type and all types registered on this task queue,
 *   making it easy to spot typos or missing registrations.
 *
 * ## Enabling / disabling
 *
 * The `runTemporalTest` test builder enables strict registration by default. To opt out:
 * ```kotlin
 * runTemporalTest {
 *     strictWorkflowRegistration(false)
 *
 *     application {
 *         taskQueue("my-queue") { ... }
 *     }
 *
 *     // ...
 * }
 * ```
 *
 * You can also set it explicitly on a production application:
 * ```kotlin
 * app.attributes.put(StrictWorkflowRegistrationKey, true)
 * ```
 *
 * @see com.surrealdev.temporal.testing.TemporalTestApplicationBuilder.strictWorkflowRegistration
 */
val StrictWorkflowRegistrationKey: AttributeKey<Boolean> = AttributeKey("temporal.workflow.strictRegistration")
