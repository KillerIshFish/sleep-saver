package com.sleepsaver.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class GuardTest {

    @Test
    fun `returns the block's value`() {
        assertThat(guarded("T", "ok") { 42 }).isEqualTo(42)
    }

    @Test
    fun `turns exceptions and unimplemented code into null`() {
        assertThat(guarded<Int>("T", "boom") { error("boom") }).isNull()
        assertThat(guarded<Int>("T", "todo") { TODO() }).isNull()
    }

    @Test
    fun `cancellation still propagates`() {
        assertThrows(CancellationException::class.java) {
            guarded<Int>("T", "cancel") { throw CancellationException("stop") }
        }
    }
}
