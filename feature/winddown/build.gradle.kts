// Night steps: tonight's checklist, the routine editor, the step library, guided tools and Night reset.
// Reaches core:alarm only through core:data's hooks.
plugins {
    alias(libs.plugins.sleepsaver.android.feature)
}

android {
    namespace = "com.sleepsaver.feature.winddown"
}
