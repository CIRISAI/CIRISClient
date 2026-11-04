package ai.ciris.mobile.shared.platform

/**
 * Closing the app ends the JVM, whatever is still running.
 *
 * `System.exit` waits for every shutdown hook, so one hook that never returns
 * keeps a JVM alive with no window. The launcher waits on that JVM and never
 * stops the agent or node, and on Windows the JVM keeps its jar open, which
 * wedges `pip install -U`. `halt` skips the hooks, so it runs only at the deadline.
 *
 * The default deadline leaves room for PythonRuntime.shutdown to SIGTERM a
 * backend this app started, wait its 5s grace and SIGKILL it. Stacks are printed
 * first, on their own thread: reading them needs the VM's cooperation, and a VM
 * that cannot give it must not stop the halt.
 */
fun armExitDeadline(
    deadlineMs: Long = 8_000,
    stacksAfterMs: Long = deadlineMs - 1_000,
    printStacks: () -> Unit = ::printAllStacks,
    halt: () -> Unit = { Runtime.getRuntime().halt(0) },
) {
    daemon("exit-deadline-stacks") {
        Thread.sleep(stacksAfterMs)
        printStacks()
    }
    daemon("exit-deadline") {
        Thread.sleep(deadlineMs)
        println("[Desktop] Still running ${deadlineMs}ms after close: halting without the remaining shutdown hooks")
        halt()
    }
}

private fun daemon(name: String, body: () -> Unit) {
    Thread(body, name).apply { isDaemon = true }.start()
}

private fun printAllStacks() {
    Thread.getAllStackTraces().forEach { (thread, stack) ->
        println("[Desktop] ${thread.name} (${thread.state})")
        stack.forEach { println("[Desktop]     at $it") }
    }
}
