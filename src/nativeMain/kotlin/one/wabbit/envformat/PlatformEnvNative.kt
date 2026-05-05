// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.envformat

/** Returns an empty environment on Native targets. */
actual fun platformEnvironment(): Map<String, String> = emptyMap()
