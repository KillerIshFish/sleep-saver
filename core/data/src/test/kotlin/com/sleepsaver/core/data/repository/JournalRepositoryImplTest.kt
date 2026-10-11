package com.sleepsaver.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class JournalRepositoryImplTest {

    private val db = inMemoryDatabase(ApplicationProvider.getApplicationContext())

    /** Tuesday 2026-10-06, 21:30 in New York (EDT, −04:00). */
    private val zone = ZoneId.of("America/New_York")
    private val time = FakeTimeProvider(now = instant("2026-10-07T01:30:00Z"), zone = zone)
    private val today = LocalDate.of(2026, 10, 6)
    private val repository = JournalRepositoryImpl(db, time)

    @After
    fun tearDown() = db.close()

    @Test
    fun `marks are set and cleared per wake date, stored with the current offset`() = runTest {
        val tonight = today.plusDays(1)

        repository.setMark(tonight, Mark.ALCOHOL, on = true)
        repository.setMark(tonight, Mark.LATE_MEAL, on = true)
        repository.setMark(today, Mark.STRESS, on = true)

        assertThat(repository.marks(tonight).first()).containsExactly(Mark.ALCOHOL, Mark.LATE_MEAL)
        assertThat(repository.marksByDate.first()).isEqualTo(mapOf(tonight to setOf(Mark.ALCOHOL, Mark.LATE_MEAL), today to setOf(Mark.STRESS)))
        assertThat(db.nightMarkDao().getAll().first().offsetSec).isEqualTo(-4 * 3600)

        repository.setMark(tonight, Mark.ALCOHOL, on = false)
        assertThat(repository.marks(tonight).first()).containsExactly(Mark.LATE_MEAL)
    }

    @Test
    fun `setting a mark twice keeps the first row`() = runTest {
        repository.setMark(today, Mark.UNWELL, on = true)
        val first = db.nightMarkDao().getAll().single()
        time.now = time.now.plusSeconds(600)

        repository.setMark(today, Mark.UNWELL, on = true)

        assertThat(db.nightMarkDao().getAll()).containsExactly(first)
    }

    @Test
    fun `derived marks and wake dates outside the edit window are refused`() = runTest {
        val derived = runCatching { repository.setMark(today, Mark.ANCHOR_DROPPED, on = true) }
        val tooOld = runCatching { repository.setMark(today.minusDays(7), Mark.STRESS, on = true) }
        val tooLate = runCatching { repository.setMark(today.plusDays(2), Mark.STRESS, on = true) }

        assertThat(derived.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(tooOld.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(tooLate.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        repository.setMark(today.minusDays(6), Mark.STRESS, on = true)
        assertThat(db.nightMarkDao().getAll().map { it.wakeDate }).containsExactly("2026-09-30")
    }

    @Test
    fun `Morning feel keeps a buffered tap's own time, and null clears it`() = runTest {
        val tappedAt = instant("2026-10-06T10:41:00Z")

        repository.setFeel(today, MorningFeel.CLEAR, FeelSource.RING, at = tappedAt, offset = ZoneOffset.ofHours(-4))
        repository.setFeel(today.minusDays(1), MorningFeel.SLOW, FeelSource.REVIEW)

        assertThat(repository.feel(today).first()).isEqualTo(MorningFeelEntry(today, MorningFeel.CLEAR, tappedAt, ZoneOffset.ofHours(-4), FeelSource.RING))
        val yesterday = repository.feels.first().getValue(today.minusDays(1))
        assertThat(yesterday.at).isEqualTo(time.now)
        assertThat(yesterday.offset).isEqualTo(ZoneOffset.ofHours(-4))

        repository.setFeel(today, null, FeelSource.REVIEW)
        assertThat(repository.feel(today).first()).isNull()
    }

    @Test
    fun `a blank Tomorrow list deletes it`() = runTest {
        repository.setTomorrowList(today, "- call Sam\n- buy milk")
        assertThat(repository.tomorrowList(today).first()!!.items).containsExactly("call Sam", "buy milk").inOrder()

        repository.setTomorrowList(today, "  \n ")

        assertThat(repository.tomorrowList(today).first()).isNull()
    }

    @Test
    fun `a prompt shows once per wake date`() = runTest {
        assertThat(repository.promptShown(today, PromptKind.ODD_NIGHT).first()).isFalse()

        repository.markPromptShown(today, PromptKind.ODD_NIGHT)
        repository.markPromptShown(today, PromptKind.ODD_NIGHT)

        assertThat(repository.promptShown(today, PromptKind.ODD_NIGHT).first()).isTrue()
        assertThat(repository.promptShown(today.plusDays(1), PromptKind.ODD_NIGHT).first()).isFalse()
        assertThat(db.promptLogDao().getAll()).hasSize(1)
    }
}
