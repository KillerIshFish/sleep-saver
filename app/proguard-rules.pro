# Libraries used here (Hilt, Room, Health Connect, kotlinx.serialization, WorkManager) ship their own consumer rules.
# Keep @Serializable navigation routes' generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
