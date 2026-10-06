package com.sleepsaver.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `orLastGood starts from the fallback and then keeps the last good value`() = runTest {
        val values = flowOf(null, 1, null, 2, null).orLastGood(0).toList()

        assertThat(values).containsExactly(0, 1, 1, 2, 2).inOrder()
    }

    @Test
    fun `orFallback retries, then emits the fallback when nothing was emitted`() = runTest {
        var attempts = 0
        val failing = flow<Int> {
            attempts++
            throw IllegalStateException("database closed")
        }

        assertThat(failing.orFallback("T", "query") { -1 }.toList()).containsExactly(-1)
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `orFallback recovers when a retry succeeds`() = runTest {
        var attempts = 0
        val flaky = flow {
            if (attempts++ == 0) throw IllegalStateException("busy")
            emit(7)
        }

        assertThat(flaky.orFallback("T", "query") { -1 }.toList()).containsExactly(7)
    }

    @Test
    fun `orFallback keeps the last value when the flow fails after emitting`() = runTest {
        val failingLater = flow {
            emit(1)
            emit(2)
            throw IllegalStateException("database closed")
        }

        val values = failingLater.orFallback("T", "query") { -1 }.toList()

        assertThat(values).doesNotContain(-1)
        assertThat(values.last()).isEqualTo(2)
    }

    @Test
    fun `orFallback lets cancellation through`() = runTest {
        val cancelled = flow<Int> { throw CancellationException("stop") }

        val error = runCatching { cancelled.orFallback("T", "query") { -1 }.toList() }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }
}
