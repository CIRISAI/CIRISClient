package ai.ciris.mobile.shared.platform

/** Not carried here: this platform's agent logs are reachable without the app (see the expect). */
actual fun agentLogTails(maxBytesEach: Int): List<Pair<String, String>> = emptyList()
