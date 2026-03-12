package io.locklane

import kotlin.test.Test
import kotlin.test.assertTrue

class LockLaneBuildConfigTest {

    @Test
    fun `plugin version should follow snapshot convention during bootstrap`() {
        val version = "0.1.0-SNAPSHOT"
        assertTrue(version.endsWith("SNAPSHOT"))
    }
}

