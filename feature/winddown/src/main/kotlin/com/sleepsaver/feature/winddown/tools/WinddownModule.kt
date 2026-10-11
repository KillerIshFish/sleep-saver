package com.sleepsaver.feature.winddown.tools

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The guided tools' seams: the phone's vibrator and its monotonic clock (tests pass fakes to the ViewModels). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WinddownModule {
    @Binds
    abstract fun haptics(impl: VibratorHaptics): Haptics

    @Binds
    abstract fun guidedClock(impl: SystemGuidedClock): GuidedClock
}
