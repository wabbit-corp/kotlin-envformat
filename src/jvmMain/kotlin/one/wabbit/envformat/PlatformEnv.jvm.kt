package one.wabbit.envformat

/** Returns the JVM process environment from [System.getenv]. */
actual fun platformEnvironment(): Map<String, String> = System.getenv()
