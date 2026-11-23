# The instrumentation finds benchmark classes and their JUnit entry points by reflection.
-keep @org.junit.runner.RunWith class * { *; }
-keepclassmembers class * { @org.junit.** <methods>; @org.junit.** <fields>; }
-dontwarn org.junit.**

# Compose pulls in androidx.window, whose OEM extension classes exist only on the device.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
