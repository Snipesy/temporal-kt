// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.dsl.activity
import com.surrealdev.temporal.dsl.workflow
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

fun module(builder: TaskQueueBuilder) {
    with(builder) {
        workflow("Inline") {
            // Direct non-determinism inside a workflow lambda — must be flagged.
            <!TEMPORAL_NONDETERMINISTIC_CALL("System.currentTimeMillis()")!>System.currentTimeMillis()<!>

            <!TEMPORAL_NONDETERMINISTIC_CALL("GlobalScope extension receiver")!><!OPT_IN_USAGE!>GlobalScope<!>
                .async { "boom" }<!>

            // Inside an activity lambda, the same calls are permitted — activities may be
            // non-deterministic. No diagnostic should fire here.
            activity("Side") {
                System.currentTimeMillis()
                <!OPT_IN_USAGE!>GlobalScope<!>.async { "fine" }
            }
        }
    }
}
