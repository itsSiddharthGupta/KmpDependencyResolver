package com.kmpdependencyresolver.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun `test runtime uses Java 21 or newer`() {
        assertTrue(Runtime.version().feature() >= 21)
    }
}
