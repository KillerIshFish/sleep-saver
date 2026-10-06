package com.sleepsaver.feature.habits

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import com.sleepsaver.core.ui.testing.captureScreen
import org.junit.Test
import java.time.Duration

class HabitsScreenshotTest : ScreenshotTest() {

    private val ready: InsightsState = SampleData.readyState()
    private val defaults: List<HabitSettings> = SampleData.habits

    private fun settings(vararg changes: Pair<HabitType, (HabitSettings) -> HabitSettings>): List<HabitSettings> {
        val byType = changes.toMap()
        return defaults.map { byType[it.type]?.invoke(it) ?: it }
    }

    private fun detail(
        type: HabitType,
        settings: List<HabitSettings> = defaults,
        activities: List<RoutineActivity> = ActivitiesWithCustom,
        insights: InsightsState = ready,
    ) = habitDetailUiState(type, settings, activities, insights)

    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f), content = content)
    }

    @Composable
    private fun ListScreen(state: HabitsUiState, firstItem: Int = 0, blocked: Boolean = false) = HabitsContent(
        state = state,
        onBack = {},
        onOpenHabit = {},
        onToggleHabit = { _, _ -> },
        onToggleReminder = { _, _ -> },
        notificationsBlocked = blocked,
        listState = rememberLazyListState(initialFirstVisibleItemIndex = firstItem),
    )

    @Composable
    private fun DetailScreen(state: HabitDetailUiState, scrolledToEnd: Boolean = false, blocked: Boolean = false) = HabitDetailContent(
        state = state,
        onBack = {},
        onToggleHabit = {},
        onToggleReminder = {},
        onTimingChange = {},
        onResetTiming = {},
        onChooseActivities = {},
        notificationsBlocked = blocked,
        scrollState = rememberScrollState(if (scrolledToEnd) 10_000 else 0),
    )

    @Composable
    private fun RoutineScreen(state: EveningRoutineUiState) = EveningRoutineContent(
        state = state,
        onClose = {},
        onToggle = { _, _ -> },
        onAddCustom = { _, _ -> },
        onDelete = {},
        onAddToSchedule = {},
    )

    // ---------------------------------------------------------------- Habit list

    @Test
    fun list() = captureScreen("habits_list") { ListScreen(habitsUiState(defaults, ready)) }

    @Test
    fun listAvailableSection() = captureScreen("habits_list_available") { ListScreen(habitsUiState(defaults, ready), firstItem = 3) }

    @Test
    fun list24h() = captureScreen("habits_list_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) { ListScreen(habitsUiState(defaults, ready)) }
    }

    @Test
    fun listNoNightsNotificationsBlocked() = captureScreen("habits_list_no_nights_blocked") {
        ListScreen(habitsUiState(defaults, InsightsState.NoNights), blocked = true)
    }

    @Test
    fun listAllOff() = captureScreen("habits_list_all_off") {
        ListScreen(habitsUiState(defaults.map { it.copy(enabled = false) }, ready))
    }

    @Test
    fun listLoading() = captureScreen("habits_list_loading") { ListScreen(HabitsUiState.Loading) }

    @Test
    fun listUnavailable() = captureScreen("habits_list_unavailable") { ListScreen(HabitsUiState.Unavailable) }

    @Test
    fun listLargeFont() = captureScreen("habits_list_large_font") { LargeFont { ListScreen(habitsUiState(defaults, ready)) } }

    // ---------------------------------------------------------------- Habit detail

    @Test
    fun detailCaffeine() = captureScreen("habit_detail_caffeine") { DetailScreen(detail(HabitType.CAFFEINE_CUTOFF)) }

    @Test
    fun detailCaffeineBottom() = captureScreen("habit_detail_caffeine_bottom") {
        DetailScreen(
            detail(HabitType.CAFFEINE_CUTOFF, settings(HabitType.CAFFEINE_CUTOFF to { it.copy(offset = Duration.ofMinutes(-510)) })),
            scrolledToEnd = true,
        )
    }

    @Test
    fun detailEveningRoutine() = captureScreen("habit_detail_evening_routine") {
        DetailScreen(detail(HabitType.EVENING_ROUTINE), scrolledToEnd = true)
    }

    @Test
    fun detailBedtimeShiftedBlocked() = captureScreen("habit_detail_bedtime_blocked") {
        DetailScreen(
            detail(HabitType.BEDTIME, settings(HabitType.BEDTIME to { it.copy(offset = Duration.ofMinutes(-30)) })),
            scrolledToEnd = true,
            blocked = true,
        )
    }

    @Test
    fun detailMorningLight24h() = captureScreen("habit_detail_morning_light_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) { DetailScreen(detail(HabitType.MORNING_LIGHT)) }
    }

    @Test
    fun detailOffNoNights() = captureScreen("habit_detail_nap_no_nights") {
        DetailScreen(detail(HabitType.NAP_WINDOW, insights = InsightsState.NoNights))
    }

    @Test
    fun detailNotFound() = captureScreen("habit_detail_not_found") { DetailScreen(HabitDetailUiState.NotFound) }

    @Test
    fun detailLargeFont() = captureScreen("habit_detail_large_font") { LargeFont { DetailScreen(detail(HabitType.DIM_LIGHTS)) } }

    @Test
    fun detailLargeFontBottom() = captureScreen("habit_detail_large_font_bottom") {
        LargeFont { DetailScreen(detail(HabitType.DIM_LIGHTS), scrolledToEnd = true) }
    }

    // ---------------------------------------------------------------- Evening routine

    @Test
    fun routine() = captureScreen("evening_routine") {
        RoutineScreen(EveningRoutineUiState.Ready(ActivitiesWithCustom, routineEnabled = false))
    }

    @Test
    fun routineNothingPicked() = captureScreen("evening_routine_nothing_picked") {
        RoutineScreen(EveningRoutineUiState.Ready(FakeHabitsRepository.DEFAULT_ACTIVITIES.map { it.copy(selected = false) }, routineEnabled = true))
    }

    @Test
    fun routineLoading() = captureScreen("evening_routine_loading") { RoutineScreen(EveningRoutineUiState.Loading) }

    @Test
    fun routineLargeFont() = captureScreen("evening_routine_large_font") {
        LargeFont { RoutineScreen(EveningRoutineUiState.Ready(ActivitiesWithCustom, routineEnabled = true)) }
    }

    @Test
    fun customForm() = captureComponent("evening_routine_custom_form") {
        CustomActivityForm(onCancel = {}, onAdd = { _, _ -> }, initialLabel = "Herbal tea", initialEmoji = "🍵")
    }

    @Test
    fun customFormEmpty() = captureComponent("evening_routine_custom_form_empty") {
        CustomActivityForm(onCancel = {}, onAdd = { _, _ -> })
    }
}
