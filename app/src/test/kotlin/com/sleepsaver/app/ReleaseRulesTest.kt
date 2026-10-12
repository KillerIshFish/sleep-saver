package com.sleepsaver.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * What the release build's R8 rules (app/proguard-rules.pro) must keep. Glance stores each widget provider's class
 * name per receiver and finds the placed widgets by it, so a provider renamed by a new release has no widgets to update
 * until the launcher next asks for one.
 */
class ReleaseRulesTest {

    /** The active lines (no comments, no blanks). Unit tests run in the module directory. */
    private val rules: List<String> by lazy {
        File("proguard-rules.pro").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun `widget providers keep their class names`() {
        assertThat(rules).contains("-keepnames class * extends $GLANCE_APP_WIDGET")
        // The rule covers the app's widget (by name: feature:widget keeps Glance off app's compile classpath).
        assertThat(load(GLANCE_APP_WIDGET).isAssignableFrom(load("com.sleepsaver.feature.widget.DebtWidget"))).isTrue()
    }

    private fun load(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    private companion object {
        const val GLANCE_APP_WIDGET = "androidx.glance.appwidget.GlanceAppWidget"
    }
}
