package one.wabbit.envformat

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformEnvJvmTest {
    @Test
    fun platformEnvironment_matchesSystem() {
        assertEquals(System.getenv(), platformEnvironment())
    }
}
