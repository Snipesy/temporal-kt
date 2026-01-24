package com.surrealdev.temporal.activity

import kotlinx.coroutines.currentCoroutineContext

/**
 * Returns the current [ActivityContext] from the coroutine context.
 *
 * This function must be called from within an activity execution to access
 * the activity's context
 *
 * @throws IllegalStateException if called outside of an activity execution
 */
suspend fun activity(): ActivityContext =
    currentCoroutineContext()[ActivityContext]
        ?: error("activity() must be called from within an activity execution")
