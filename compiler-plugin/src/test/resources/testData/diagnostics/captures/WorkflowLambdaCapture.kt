// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.dsl.workflow

object Logger {
    fun log(msg: String) {}
}

fun module(builder: TaskQueueBuilder, captured: String) {
    with(builder) {
        // Captures `captured` from the enclosing function — error.
        workflow("Bad") {
            Logger.log(<!TEMPORAL_WORKFLOW_LAMBDA_CAPTURES_NOT_SUPPORTED!>captured<!>)
        }

        // No captures — references only static / package-level symbols. Allowed.
        workflow("Good") {
            Logger.log("hello")
        }
    }
}
