package com.sleepsaver.core.notifications

import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.ReminderPlanner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationsModule {
    @Binds
    abstract fun reminderPlanner(impl: ReminderPlannerImpl): ReminderPlanner

    @Binds
    abstract fun morningReviewNotifier(impl: MorningReviewNotifierImpl): MorningReviewNotifier
}
