# Libraries used here (Hilt, Room, Health Connect, kotlinx.serialization, WorkManager) ship their own consumer rules.
# Keep @Serializable navigation routes' generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Glance stores each widget provider's class name per receiver (GlanceAppWidgetManager) and finds the placed widgets by
# it, so the name must not change between releases: when R8 renamed and merged DebtWidget, updateAll() after each app
# update found no widgets until the next APPWIDGET_UPDATE or the 30-min period. Receivers keep their names through the
# manifest; Glance's own rules keep ActionCallback classes.
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget
