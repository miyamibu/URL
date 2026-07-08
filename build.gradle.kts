plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

gradle.taskGraph.whenReady {
    val guardedTaskNames = listOf(
        "connectedAndroidTest",
        "connectedCheck",
        "connectedDebugAndroidTest",
        "deviceAndroidTest",
        "deviceCheck",
        "installDebugAndroidTest",
    )
    val requestedGuardedTasks = allTasks.filter { task ->
        guardedTaskNames.any { guardedName ->
            task.name.equals(guardedName, ignoreCase = true) ||
                task.path.endsWith(":$guardedName", ignoreCase = true)
        }
    }

    if (requestedGuardedTasks.isNotEmpty()) {
        val allowConnectedAndroidTests =
            System.getenv("URLSAVER_ALLOW_CONNECTED_ANDROID_TESTS") == "true"
        val approveAndroidAppDataReset =
            System.getenv("URLSAVER_APPROVE_ANDROID_APP_DATA_RESET") == "true"
        if (!allowConnectedAndroidTests || !approveAndroidAppDataReset) {
            val taskList = requestedGuardedTasks.joinToString { it.path }
            throw GradleException(
                "Refusing to run connected Android test/install tasks on attached devices: $taskList. " +
                    "These tasks can install instrumentation APKs and reset URL Saver app data on a real device. " +
                    "Use JVM tests such as ':app:testDebugUnitTest', or rerun on a disposable device with " +
                    "both URLSAVER_ALLOW_CONNECTED_ANDROID_TESTS=true and " +
                    "URLSAVER_APPROVE_ANDROID_APP_DATA_RESET=true."
            )
        }
    }
}
