// RUN_PIPELINE_TILL: FRONTEND

// `knownTaskQueues = [known-queue]` is injected by TemporalExtensionRegistrarConfigurator.

fun withTaskQueue(name: String, action: () -> Unit) {
    action()
}

fun test() {
    withTaskQueue(<!TEMPORAL_UNKNOWN_TASK_QUEUE("bad-queue")!>"bad-queue"<!>) {}
    withTaskQueue("known-queue") {}
}
