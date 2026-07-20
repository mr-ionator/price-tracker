# Keep kotlinx.serialization generated serializers
-keepclassmembers class com.pricetracker.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.pricetracker.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
