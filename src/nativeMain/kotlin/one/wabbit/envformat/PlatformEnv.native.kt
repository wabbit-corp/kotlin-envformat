package one.wabbit.envformat

/** Returns an empty environment on Native targets. */
actual fun platformEnvironment(): Map<String, String> = emptyMap()
