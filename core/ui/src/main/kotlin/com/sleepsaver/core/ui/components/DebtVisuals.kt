package com.sleepsaver.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.theme.SleepSaverTheme

@get:StringRes
val DebtStatus.labelRes: Int
    get() = when (this) {
        DebtStatus.LOW -> R.string.ss_debt_status_low
        DebtStatus.MODERATE -> R.string.ss_debt_status_moderate
        DebtStatus.HIGH -> R.string.ss_debt_status_high
    }

@Composable
@ReadOnlyComposable
fun DebtStatus.label(): String = stringResource(labelRes)

/** Tide for low, amber for moderate, coral for high. */
val DebtStatus.color: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        DebtStatus.LOW -> SleepSaverTheme.colors.debtLow
        DebtStatus.MODERATE -> SleepSaverTheme.colors.debtModerate
        DebtStatus.HIGH -> SleepSaverTheme.colors.debtHigh
    }
