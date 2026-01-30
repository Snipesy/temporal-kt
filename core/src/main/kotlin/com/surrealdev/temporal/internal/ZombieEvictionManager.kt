package com.surrealdev.temporal.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages zombie thread detection and eviction for both workflows and activities.
 *
 * A "zombie" thread is one that doesn't respond to Thread.interrupt() - typically due to
 * non-interruptible blocking operations like busy loops or certain native calls.
 *
 * This manager:
 * - Tracks zombie threads via atomic counter
 * - Launches async eviction jobs that retry interruption
 * - Triggers fatal error callback when threshold is exceeded
 * - Provides shutdown coordination via [awaitAllEvictions]
 */
internal class ZombieEvictionManager(
    private val logger: Logger,
    private val taskQueue: String,
    /**
     * Grace period to wait for a thread to terminate after interrupt.
     */
    private val terminationGracePeriodMs: Long,
    /**
     * Maximum zombie count before triggering fatal error.
     * Set to 0 to disable threshold check.
     */
    private val maxZombieCount: Int,
    /**
     * Maximum retry attempts before giving up on a zombie.
     */
    private val maxZombieRetries: Int,
    /**
     * Interval between zombie eviction retry attempts.
     */
    private val zombieRetryIntervalMs: Long,
    /**
     * Timeout for waiting on all eviction jobs during shutdown.
     */
    private val evictionShutdownTimeoutMs: Long,
    /**
     * Callback invoked when zombie threshold is exceeded.
     * Only invoked once via atomic guard.
     */
    private val onFatalError: (suspend () -> Unit)?,
    /**
     * Error code prefix for logging (e.g., "TKT11" for workflow, "TKT12" for activity).
     */
    private val errorCodePrefix: String,
    /**
     * Entity type for logging (e.g., "workflow", "activity").
     */
    private val entityType: String,
) {
    private val zombieCount = AtomicInteger(0)
    private val zombieEvictionJobs = ConcurrentHashMap<String, Job>()
    private val fatalErrorTriggered = AtomicBoolean(false)

    /**
     * Gets the current count of zombie threads.
     */
    fun getZombieCount(): Int = zombieCount.get()

    /**
     * Launches an async job to terminate a thread and monitor for zombies.
     * This method does NOT block - it launches a job that handles termination asynchronously.
     *
     * @param zombieId Unique identifier for this zombie (used to prevent duplicate jobs)
     * @param entityId Identifier for logging (e.g., runId, activityId)
     * @param entityName Name for logging (e.g., workflowType, activityType)
     * @param terminateFn Function to signal termination (non-blocking)
     * @param interruptFn Function to interrupt the thread (for retries)
     * @param isAliveFn Function to check if thread is still alive
     * @param awaitTerminationFn Function to wait for thread termination (blocking)
     * @param immediate If true, use immediate termination mode
     */
    fun launchEviction(
        zombieId: String,
        entityId: String,
        entityName: String,
        terminateFn: (immediate: Boolean) -> Unit,
        interruptFn: () -> Unit,
        isAliveFn: () -> Boolean,
        awaitTerminationFn: (timeoutMs: Long) -> Boolean,
        immediate: Boolean,
    ) {
        zombieEvictionJobs.computeIfAbsent(zombieId) {
            CoroutineScope(NonCancellable + Dispatchers.IO).launch {
                try {
                    // Send termination signal (non-blocking)
                    terminateFn(immediate)

                    // Wait for graceful termination
                    val terminated = awaitTerminationFn(terminationGracePeriodMs)

                    if (terminated) {
                        logger.debug(
                            "{} thread terminated gracefully. {}={}",
                            entityType.replaceFirstChar { it.uppercase() },
                            entityType,
                            entityId,
                        )
                        return@launch
                    }

                    // Thread didn't respond - start zombie eviction loop
                    runEvictionLoop(
                        entityId = entityId,
                        entityName = entityName,
                        interruptFn = interruptFn,
                        isAliveFn = isAliveFn,
                    )
                } finally {
                    zombieEvictionJobs.remove(zombieId)
                }
            }
        }
    }

    private suspend fun runEvictionLoop(
        entityId: String,
        entityName: String,
        interruptFn: () -> Unit,
        isAliveFn: () -> Boolean,
    ) {
        var attemptCount = 1
        var countedAsZombie = false

        while (isAliveFn() && attemptCount <= maxZombieRetries) {
            // Track as zombie on first iteration
            if (!countedAsZombie) {
                val currentZombies = zombieCount.incrementAndGet()
                countedAsZombie = true

                // Check threshold
                if (maxZombieCount > 0 && currentZombies >= maxZombieCount) {
                    if (fatalErrorTriggered.compareAndSet(false, true)) {
                        logger.error(
                            "[${errorCodePrefix}05] FATAL: Zombie threshold exceeded ({} >= {}). " +
                                "Worker cannot safely continue. This indicates $entityType code " +
                                "that uses non-interruptible blocking (busy loops, native calls). " +
                                "Initiating graceful shutdown.",
                            currentZombies,
                            maxZombieCount,
                        )
                        onFatalError?.invoke()
                    }
                }
            }

            logger.error(
                "[${errorCodePrefix}02] CRITICAL: Zombie $entityType thread detected (attempt {}). " +
                    "Thread did not respond to interrupt - this leaks a virtual thread slot. " +
                    "{}={}, {}={}, task_queue={}, total_zombies={}. " +
                    "Check for non-interruptible blocking operations (busy loops, native calls). " +
                    "Worker shutdown may hang until this thread terminates.",
                attemptCount,
                entityType + "_id",
                entityId,
                entityType + "_type",
                entityName,
                taskQueue,
                zombieCount.get(),
            )

            delay(zombieRetryIntervalMs)
            attemptCount++

            // Try to interrupt again
            interruptFn()
        }

        // Exhausted retries
        if (isAliveFn() && attemptCount > maxZombieRetries) {
            logger.error(
                "[${errorCodePrefix}06] Giving up on zombie $entityType thread after {} attempts. " +
                    "Thread will remain leaked. {}={}, {}={}",
                maxZombieRetries,
                entityType + "_id",
                entityId,
                entityType + "_type",
                entityName,
            )
            return // Keep counted as zombie, stop retrying
        }

        // Thread finally terminated
        if (countedAsZombie) {
            val remaining = zombieCount.decrementAndGet()
            logger.info(
                "Zombie $entityType thread finally terminated after {} attempts. " +
                    "{}={}, remaining_zombies={}",
                attemptCount,
                entityType + "_id",
                entityId,
                remaining,
            )
        }
    }

    /**
     * Logs a warning if there are active zombies during shutdown.
     */
    fun logShutdownWarning() {
        val currentZombies = zombieCount.get()
        if (currentZombies > 0) {
            logger.warn(
                "[${errorCodePrefix}03] Worker shutdown with {} zombie $entityType thread(s) still active. " +
                    "Shutdown may be delayed until these threads terminate. task_queue={}",
                currentZombies,
                taskQueue,
            )
        }
    }

    /**
     * Awaits completion of all zombie eviction jobs with timeout.
     *
     * @return true if all jobs completed, false if timeout reached
     */
    suspend fun awaitAllEvictions(): Boolean {
        if (zombieEvictionJobs.isEmpty()) return true

        logger.info(
            "Waiting for {} zombie eviction job(s) to complete...",
            zombieEvictionJobs.size,
        )

        val jobs = zombieEvictionJobs.values.toList()
        val completed =
            withTimeoutOrNull(evictionShutdownTimeoutMs) {
                jobs.joinAll()
                true
            } ?: false

        if (!completed) {
            logger.warn(
                "[${errorCodePrefix}04] Zombie eviction timeout - {} job(s) still running after {}ms",
                zombieEvictionJobs.size,
                evictionShutdownTimeoutMs,
            )
        }

        return completed
    }
}
