package one.wabbit.envformat

/** Returns the Android process environment from [System.getenv]. */
actual fun platformEnvironment(): Map<String, String> = System.getenv()
