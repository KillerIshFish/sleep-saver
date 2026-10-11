package com.sleepsaver.core.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.AnchorSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Drop anchor and its Undo (SPEC_V2 §0.1 feature 9): Undo works only for tonight's anchor, within the undo window.
 * "I'm up" ends the night state and publishes when it was tapped.
 */
@RunWith(AndroidJUnit4::class)
internal class NightControllerAnchorTest : AlarmRobolectricTest() {

    private val undoWindow = SleepConfig.DEFAULT.anchor.undoWindow

    private fun drop(): Long = checkNotNull(runBlocking { graph.nightController.dropAnchor(AnchorSource.TONIGHT, wholeNightLog = false) })

    @Test
    fun `undo within the window removes the anchor and leaves the night state`() {
        now = at(today, "22:30")
        val id = drop()
        assertThat(m.night?.anchorId).isEqualTo(id)

        now = now + undoWindow
        runBlocking { graph.nightController.undoAnchor(id) }

        assertThat(m.night).isNull()
        assertThat(graph.nightLog.anchorsDeleted).containsExactly(id)
    }

    @Test
    fun `a late undo keeps the anchor`() {
        now = at(today, "22:30")
        val id = drop()

        now = now + undoWindow + Duration.ofSeconds(1)
        runBlocking { graph.nightController.undoAnchor(id) }

        assertThat(m.night?.anchorId).isEqualTo(id)
        assertThat(graph.nightLog.anchorsDeleted).isEmpty()
    }

    @Test
    fun `an undo for another anchor changes nothing`() {
        now = at(today, "22:30")
        val id = drop()

        runBlocking { graph.nightController.undoAnchor(id + 1) }

        assertThat(m.night?.anchorId).isEqualTo(id)
        assertThat(graph.nightLog.anchorsDeleted).isEmpty()
    }

    @Test
    fun `I'm up ends the night state and tells every screen when, also without an anchor`() {
        now = at(today, "02:10")
        graph.nightController.endNight()
        assertThat(graph.nightController.state.value.endedAt).isEqualTo(now)

        now = at(today, "22:30")
        drop()
        now = at(today, "23:40")
        graph.nightController.endNight()

        assertThat(m.night).isNull()
        assertThat(graph.nightController.state.value.night).isNull()
        assertThat(graph.nightController.state.value.endedAt).isEqualTo(at(today, "23:40"))
    }
}
