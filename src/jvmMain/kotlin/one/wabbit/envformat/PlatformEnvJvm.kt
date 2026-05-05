// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.envformat

/** Returns the JVM process environment from [System.getenv]. */
actual fun platformEnvironment(): Map<String, String> = System.getenv()
