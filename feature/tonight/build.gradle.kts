// The Tonight tab: Week plan, day plan editor, one-offs, Night screen, Alarm check and the ringing screen.
// Reaches core:alarm only through core:data's hooks.
plugins {
    alias(libs.plugins.sleepsaver.android.feature)
}

android {
    namespace = "com.sleepsaver.feature.tonight"
}
