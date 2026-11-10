package dev.bmcreations.protovalidate.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

class WirePluginOptionsTest {
    @Test
    fun `target only when box size is unset`() {
        assertEquals(listOf("target=wire"), wirePluginOptions(null))
    }

    @Test
    fun `box size becomes wire_box_oneofs_min_size`() {
        assertEquals(listOf("target=wire", "wire_box_oneofs_min_size=2"), wirePluginOptions(2))
    }
}
