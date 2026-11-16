# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-FileCopyrightText: borz7zy
# SPDX-License-Identifier: Apache-2.0

# Any class extending SettingsFragment can be referenced in XML.
-keep class ** extends org.lineageos.aperture.SettingsActivity$SettingsFragment

# Suppress warnings for the dynamically loaded camera extensions library
-dontwarn androidx.camera.extensions.impl.advanced.**

# GcamPipeline.ResultTap reaches CameraX internals by reflection; the app optimizes androidx.**.
-keepclassmembers class androidx.camera.camera2.interop.Camera2CameraControl {
    *** getRequestListener$camera_camera2();
}
-keep interface androidx.camera.camera2.pipe.Request$Listener { *; }
-keepclassmembers class * {
    *** addListener(androidx.camera.camera2.pipe.Request$Listener, java.util.concurrent.Executor);
    *** removeListener(androidx.camera.camera2.pipe.Request$Listener);
    *** unwrapAs(java.lang.Class);
}
