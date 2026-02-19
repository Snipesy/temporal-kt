package com.surrealdev.temporal.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for zombie thread eviction.
 */
data class ZombieEvictionConfig(
    /**
     * Maximum zombie count before triggering fatal error.
     * Set to 0 to disable threshold check.
     */
    val maxZombieCount: Int = 10,
    /**
     * Initial interval between zombie eviction retry attempts.
     * Uses exponential backoff up to [retryMaxDelay].
     * Since we use thread.join() which returns immediately on termination,
     * this can be relatively long without impacting responsiveness.
     */
    val retryInterval: Duration = 1.seconds,
    /**
     * Maximum delay between zombie eviction retry attempts.
     */
    val retryMaxDelay: Duration = 60.seconds,
    /**
     * Grace period before considering a thread a zombie.
     * During this period, the thread is retried but not counted toward [maxZombieCount]
     * and errors are not logged. This prevents false positives for threads that just
     * need a moment to terminate.
     */
    val gracePeriod: Duration = 10.seconds,
    /**
     * Time after which zombie eviction gives up and stops retrying.
     */
    val giveUpAfter: Duration = 1.hours,
    /**
     * Timeout for waiting on all eviction jobs during shutdown.
     */
    val shutdownTimeout: Duration = 30.seconds,
)

/**
 * Manages zombie thread detection and eviction for both workflows and activities.
 *
 * A "zombie" thread is one that doesn't respond to Thread.interrupt() - typically due to
 * non-interruptible blocking operations like busy loops or certain native calls.
 *
 * This manager:
 * - Tracks zombie threads via atomic counter
 * - Launches async eviction jobs that retry interruption with exponential backoff
 * - Triggers fatal error callback when threshold is exceeded
 * - Provides shutdown coordination via [awaitAllEvictions]
 */
internal class ZombieEvictionManager(
    private val logger: Logger,
    private val taskQueue: String,
    private val config: ZombieEvictionConfig,
    /**
     * Callback invoked when zombie threshold is exceeded.
     * May be called multiple times if multiple zombies cross the threshold concurrently;
     * callers are expected to provide their own at-most-once guarantee.
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
     * @param joinFn Function to wait for thread termination with timeout, returns true if terminated
     * @param getStackTraceFn Function to get the thread's current stack trace for debugging
     */
    fun launchEviction(
        zombieId: String,
        entityId: String,
        entityName: String,
        terminateFn: () -> Unit,
        interruptFn: () -> Unit,
        isAliveFn: () -> Boolean,
        joinFn: (Duration) -> Boolean,
        getStackTraceFn: () -> String,
    ) {
        zombieEvictionJobs.computeIfAbsent(zombieId) {
            CoroutineScope(NonCancellable + Dispatchers.IO).launch {
                try {
                    // Send termination signal with immediate interrupt (no grace period)
                    // At this point the task should be done, anything lingering is a leak
                    terminateFn()

                    // If still alive, start zombie eviction loop
                    if (isAliveFn()) {
                        runEvictionLoop(
                            entityId = entityId,
                            entityName = entityName,
                            interruptFn = interruptFn,
                            isAliveFn = isAliveFn,
                            joinFn = joinFn,
                            getStackTraceFn = getStackTraceFn,
                        )
                    } else {
                        logger.debug(
                            "{} thread terminated. {}={}",
                            entityType.replaceFirstChar { it.uppercase() },
                            entityType,
                            entityId,
                        )
                    }
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
        joinFn: (Duration) -> Boolean,
        getStackTraceFn: () -> String,
    ) {
        var attemptCount = 1
        var countedAsZombie = false
        var currentDelay = config.retryInterval
        var cumulativeTime = Duration.ZERO

        // Initial wait - join on thread with timeout (faster than delay if thread terminates early)
        if (joinFn(currentDelay)) {
            // Thread terminated during initial wait
            logger.debug(
                "{} thread terminated quickly. {}={}",
                entityType.replaceFirstChar { it.uppercase() },
                entityType,
                entityId,
            )
            return
        }
        cumulativeTime += currentDelay

        while (isAliveFn() && cumulativeTime < config.giveUpAfter) {
            // Only count as zombie and log errors after grace period
            val pastGracePeriod = cumulativeTime >= config.gracePeriod

            if (pastGracePeriod && !countedAsZombie) {
                val currentZombies = zombieCount.incrementAndGet()
                countedAsZombie = true

                // Log first zombie detection with stack trace
                logger.error(
                    "[${errorCodePrefix}02] CRITICAL: Zombie $entityType thread detected " +
                        "(attempt {}, elapsed {}). " +
                        "Thread did not respond to interrupt - this leaks a virtual thread slot. " +
                        "{}={}, {}={}, task_queue={}, total_zombies={}. " +
                        "Check for non-interruptible blocking operations (busy loops, native calls). " +
                        "Worker shutdown may hang until this thread terminates.\n" +
                        "Stack trace:\n{}",
                    attemptCount,
                    cumulativeTime,
                    entityType + "_id",
                    entityId,
                    entityType + "_type",
                    entityName,
                    taskQueue,
                    currentZombies,
                    getStackTraceFn(),
                )

                // Check threshold
                if (config.maxZombieCount in 1..currentZombies) {
                    logger.error(
                        "[${errorCodePrefix}05] FATAL: Zombie threshold exceeded ({} >= {}). " +
                            "Worker cannot safely continue. This indicates $entityType code " +
                            "that uses non-interruptible blocking (busy loops, native calls). " +
                            "Initiating graceful shutdown.",
                        currentZombies,
                        config.maxZombieCount,
                    )
                    onFatalError?.invoke()
                }
            }

            // Wait for thread termination with timeout (faster than delay if thread terminates early)
            val terminated = joinFn(currentDelay)
            cumulativeTime += currentDelay
            attemptCount++

            if (terminated) {
                // Thread terminated during wait - exit loop and handle cleanup below
                break
            }

            // Exponential backoff: double the delay up to configured max
            currentDelay = (currentDelay * 2).coerceAtMost(config.retryMaxDelay)

            // Try to interrupt again
            interruptFn()
        }

        // Exhausted timeout
        if (isAliveFn() && cumulativeTime >= config.giveUpAfter) {
            logger.error(
                "[${errorCodePrefix}06] Giving up on zombie $entityType thread after {}. " +
                    "Thread will remain leaked. {}={}, {}={}\n" +
                    "Final stack trace:\n{}",
                cumulativeTime,
                entityType + "_id",
                entityId,
                entityType + "_type",
                entityName,
                getStackTraceFn(),
            )
            return // Keep counted as zombie, stop retrying
        }

        // Thread finally terminated
        if (countedAsZombie) {
            val remaining = zombieCount.decrementAndGet()
            logger.info(
                "Zombie $entityType thread finally terminated after {}. " +
                    "{}={}, remaining_zombies={}",
                cumulativeTime,
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
            withTimeoutOrNull(config.shutdownTimeout) {
                jobs.joinAll()
                true
            } ?: false

        if (!completed) {
            logger.warn(
                "[${errorCodePrefix}04] Zombie eviction timeout - {} job(s) still running after {}",
                zombieEvictionJobs.size,
                config.shutdownTimeout,
            )
        }

        return completed
    }
}
