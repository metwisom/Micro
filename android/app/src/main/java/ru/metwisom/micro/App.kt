package ru.metwisom.micro

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: tint the app with the system wallpaper colors (Android 12+).
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
