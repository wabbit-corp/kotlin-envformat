package one.wabbit.envformat

actual fun platformEnvironment(): Map<String, String> = System.getenv()
