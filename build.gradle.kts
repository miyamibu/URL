import java.io.ByteArrayOutputStream
import java.io.File

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

private enum class ConnectedAndroidGuardCode {
    ALLOWED,
    ALLOW_FLAG_MISSING,
    DATA_RESET_APPROVAL_MISSING,
    TARGET_SERIAL_MISSING,
    ADB_FAILED,
    ADB_OUTPUT_INVALID,
    DEVICE_ENTRY_COUNT_NOT_ONE,
    DEVICE_NOT_READY,
    TARGET_SERIAL_MISMATCH,
}

private data class ConnectedAndroidGuardDecision(
    val code: ConnectedAndroidGuardCode,
    val targetSerial: String? = null,
) {
    val allowed: Boolean
        get() = code == ConnectedAndroidGuardCode.ALLOWED
}

private data class AdbDeviceRow(
    val serial: String,
    val state: String,
)

private fun parseAdbDeviceRows(output: String): List<AdbDeviceRow>? {
    val lines = output.lineSequence().map(String::trim).toList()
    val headerIndex = lines.indexOfFirst { it == "List of devices attached" }
    if (headerIndex < 0) return null

    val rows = mutableListOf<AdbDeviceRow>()
    for (line in lines.drop(headerIndex + 1).filter(String::isNotEmpty)) {
        val columns = line.split(Regex("\\s+"), limit = 3)
        if (columns.size < 2) return null
        rows += AdbDeviceRow(serial = columns[0], state = columns[1])
    }
    return rows
}

private fun evaluateConnectedAndroidEnvironment(
    allowConnectedTests: String?,
    approveAppDataReset: String?,
    targetSerial: String?,
): ConnectedAndroidGuardDecision? {
    if (allowConnectedTests != "true") {
        return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.ALLOW_FLAG_MISSING)
    }
    if (approveAppDataReset != "true") {
        return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.DATA_RESET_APPROVAL_MISSING)
    }
    if (targetSerial.isNullOrBlank()) {
        return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.TARGET_SERIAL_MISSING)
    }
    return null
}

private fun evaluateConnectedAndroidDeviceGuard(
    allowConnectedTests: String?,
    approveAppDataReset: String?,
    targetSerial: String?,
    adbSucceeded: Boolean,
    adbOutput: String,
): ConnectedAndroidGuardDecision {
    evaluateConnectedAndroidEnvironment(
        allowConnectedTests = allowConnectedTests,
        approveAppDataReset = approveAppDataReset,
        targetSerial = targetSerial,
    )?.let { return it }

    val expectedSerial = requireNotNull(targetSerial)
    if (!adbSucceeded) {
        return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.ADB_FAILED, expectedSerial)
    }
    val rows = parseAdbDeviceRows(adbOutput)
        ?: return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.ADB_OUTPUT_INVALID, expectedSerial)
    if (rows.size != 1) {
        return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.DEVICE_ENTRY_COUNT_NOT_ONE, expectedSerial)
    }
    val onlyDevice = rows.single()
    if (onlyDevice.state != "device") {
        return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.DEVICE_NOT_READY, expectedSerial)
    }
    if (onlyDevice.serial != expectedSerial) {
        return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.TARGET_SERIAL_MISMATCH, expectedSerial)
    }
    return ConnectedAndroidGuardDecision(ConnectedAndroidGuardCode.ALLOWED, expectedSerial)
}

private fun isGuardedConnectedAndroidTask(taskName: String): Boolean {
    val normalized = taskName.lowercase()
    return (normalized.startsWith("connected") &&
        (normalized.endsWith("androidtest") || normalized.endsWith("check"))) ||
        (normalized.startsWith("device") &&
            (normalized.endsWith("androidtest") || normalized.endsWith("check"))) ||
        (normalized.startsWith("install") && normalized.endsWith("androidtest"))
}

private fun connectedAndroidGuardFailureMessage(decision: ConnectedAndroidGuardDecision): String {
    val target = decision.targetSerial
    return when (decision.code) {
        ConnectedAndroidGuardCode.ALLOWED -> "Connected Android target verified: $target"
        ConnectedAndroidGuardCode.ALLOW_FLAG_MISSING ->
            "Refusing connected Android tasks: URLSAVER_ALLOW_CONNECTED_ANDROID_TESTS must be exactly true."
        ConnectedAndroidGuardCode.DATA_RESET_APPROVAL_MISSING ->
            "Refusing connected Android tasks: URLSAVER_APPROVE_ANDROID_APP_DATA_RESET must be exactly true."
        ConnectedAndroidGuardCode.TARGET_SERIAL_MISSING ->
            "Refusing connected Android tasks: URLSAVER_CONNECTED_ANDROID_SERIAL must be non-empty."
        ConnectedAndroidGuardCode.ADB_FAILED ->
            "Refusing connected Android tasks for target $target: adb devices failed."
        ConnectedAndroidGuardCode.ADB_OUTPUT_INVALID ->
            "Refusing connected Android tasks for target $target: adb devices returned unrecognized output."
        ConnectedAndroidGuardCode.DEVICE_ENTRY_COUNT_NOT_ONE ->
            "Refusing connected Android tasks for target $target: adb must list exactly one device entry."
        ConnectedAndroidGuardCode.DEVICE_NOT_READY ->
            "Refusing connected Android tasks for target $target: the only adb entry is not in device state."
        ConnectedAndroidGuardCode.TARGET_SERIAL_MISMATCH ->
            "Refusing connected Android tasks for target $target: the only ready device does not match the approved target."
    }
}

private fun resolveAdbExecutable(): String {
    val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "adb.exe"
    } else {
        "adb"
    }
    val sdkRoots = listOfNotNull(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
    ).filter(String::isNotBlank)
    return sdkRoots
        .map { File(it, "platform-tools/$executableName") }
        .firstOrNull(File::isFile)
        ?.absolutePath
        ?: executableName
}

val verifyConnectedAndroidDeviceTarget by tasks.registering {
    group = "verification"
    description = "Fails closed unless exactly one approved adb target is ready for connected Android tasks."
    doLast {
        val allowConnectedTests = System.getenv("URLSAVER_ALLOW_CONNECTED_ANDROID_TESTS")
        val approveAppDataReset = System.getenv("URLSAVER_APPROVE_ANDROID_APP_DATA_RESET")
        val targetSerial = System.getenv("URLSAVER_CONNECTED_ANDROID_SERIAL")
        evaluateConnectedAndroidEnvironment(
            allowConnectedTests = allowConnectedTests,
            approveAppDataReset = approveAppDataReset,
            targetSerial = targetSerial,
        )?.let { throw GradleException(connectedAndroidGuardFailureMessage(it)) }

        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val execution = runCatching {
            providers.exec {
                commandLine(resolveAdbExecutable(), "devices")
                this.standardOutput = standardOutput
                this.errorOutput = errorOutput
                isIgnoreExitValue = true
            }.result.get()
        }
        val decision = evaluateConnectedAndroidDeviceGuard(
            allowConnectedTests = allowConnectedTests,
            approveAppDataReset = approveAppDataReset,
            targetSerial = targetSerial,
            adbSucceeded = execution.getOrNull()?.exitValue == 0,
            adbOutput = standardOutput.toString(Charsets.UTF_8.name()),
        )
        if (!decision.allowed) {
            throw GradleException(connectedAndroidGuardFailureMessage(decision))
        }
        logger.lifecycle(connectedAndroidGuardFailureMessage(decision))
    }
}

val testConnectedAndroidDeviceGuard by tasks.registering {
    group = "verification"
    description = "Runs host-only decision tests for the connected Android target guard without invoking adb."
    doLast {
        data class GuardCase(
            val name: String,
            val allow: String? = "true",
            val approveReset: String? = "true",
            val target: String? = "emulator-approved",
            val adbSucceeded: Boolean = true,
            val adbOutput: String = "List of devices attached\nemulator-approved\tdevice\n",
            val expected: ConnectedAndroidGuardCode,
        )

        val cases = listOf(
            GuardCase("zero devices", adbOutput = "List of devices attached\n", expected = ConnectedAndroidGuardCode.DEVICE_ENTRY_COUNT_NOT_ONE),
            GuardCase("one matching device", expected = ConnectedAndroidGuardCode.ALLOWED),
            GuardCase("one mismatching device", adbOutput = "List of devices attached\nother-device\tdevice\n", expected = ConnectedAndroidGuardCode.TARGET_SERIAL_MISMATCH),
            GuardCase("two ready devices", adbOutput = "List of devices attached\nemulator-approved\tdevice\nother-device\tdevice\n", expected = ConnectedAndroidGuardCode.DEVICE_ENTRY_COUNT_NOT_ONE),
            GuardCase("unauthorized only", adbOutput = "List of devices attached\nemulator-approved\tunauthorized\n", expected = ConnectedAndroidGuardCode.DEVICE_NOT_READY),
            GuardCase("offline only", adbOutput = "List of devices attached\nemulator-approved\toffline\n", expected = ConnectedAndroidGuardCode.DEVICE_NOT_READY),
            GuardCase("duplicate serial rows", adbOutput = "List of devices attached\nemulator-approved\tdevice\nemulator-approved\tdevice\n", expected = ConnectedAndroidGuardCode.DEVICE_ENTRY_COUNT_NOT_ONE),
            GuardCase("ready plus offline", adbOutput = "List of devices attached\nemulator-approved\tdevice\nother-device\toffline\n", expected = ConnectedAndroidGuardCode.DEVICE_ENTRY_COUNT_NOT_ONE),
            GuardCase("empty target env", target = "", expected = ConnectedAndroidGuardCode.TARGET_SERIAL_MISSING),
            GuardCase("missing allow env", allow = null, expected = ConnectedAndroidGuardCode.ALLOW_FLAG_MISSING),
            GuardCase("missing reset approval env", approveReset = null, expected = ConnectedAndroidGuardCode.DATA_RESET_APPROVAL_MISSING),
            GuardCase("adb failure", adbSucceeded = false, adbOutput = "", expected = ConnectedAndroidGuardCode.ADB_FAILED),
            GuardCase("invalid adb output", adbOutput = "unexpected output\n", expected = ConnectedAndroidGuardCode.ADB_OUTPUT_INVALID),
        )
        cases.forEach { case ->
            val actual = evaluateConnectedAndroidDeviceGuard(
                allowConnectedTests = case.allow,
                approveAppDataReset = case.approveReset,
                targetSerial = case.target,
                adbSucceeded = case.adbSucceeded,
                adbOutput = case.adbOutput,
            ).code
            check(actual == case.expected) {
                "Guard case '${case.name}' expected ${case.expected} but got $actual"
            }
        }

        val guardedNames = listOf(
            "connectedDebugAndroidTest",
            "connectedReleaseAndroidTest",
            "connectedCheck",
            "deviceAndroidTest",
            "installDebugAndroidTest",
        )
        guardedNames.forEach { taskName ->
            check(isGuardedConnectedAndroidTask(taskName)) { "$taskName must be guarded" }
        }
        check(!isGuardedConnectedAndroidTask("testDebugUnitTest"))
        check(!isGuardedConnectedAndroidTask("assembleDebug"))

        val appProject = rootProject.project(":app")
        val guardTask = verifyConnectedAndroidDeviceTarget.get()
        val guardedTasks = appProject.tasks.filter { isGuardedConnectedAndroidTask(it.name) }
        check(guardedTasks.isNotEmpty()) { "At least one connected Android task must be wired to the guard" }
        check(guardedTasks.any { it.name == "connectedDebugAndroidTest" }) {
            ":app:connectedDebugAndroidTest must be included in the guarded task set"
        }
        guardedTasks.forEach { guardedTask ->
            check(guardTask in guardedTask.taskDependencies.getDependencies(guardedTask)) {
                "${guardedTask.path} must depend on :verifyConnectedAndroidDeviceTarget"
            }
        }
        val hostTasks = listOf("testDebugUnitTest", "lintDebug", "assembleDebug")
            .map { name -> checkNotNull(appProject.tasks.findByName(name)) { ":app:$name must exist" } }
        hostTasks.forEach { hostTask ->
            check(guardTask !in hostTask.taskDependencies.getDependencies(hostTask)) {
                "${hostTask.path} must not depend on the adb-invoking guard"
            }
        }
        logger.lifecycle(
            "Connected Android guard logic: ${cases.size}/${cases.size} decision cases passed; " +
                "${guardedTasks.size} actual connected/install task wiring checks passed; " +
                "host-only task isolation checks passed.",
        )
        logger.lifecycle("Guarded Android tasks: ${guardedTasks.map { it.path }.sorted().joinToString()}")
    }
}

allprojects {
    tasks.configureEach {
        if (isGuardedConnectedAndroidTask(name)) {
            dependsOn(rootProject.tasks.named("verifyConnectedAndroidDeviceTarget"))
        }
        if (name == "testDebugUnitTest") {
            dependsOn(rootProject.tasks.named("testConnectedAndroidDeviceGuard"))
        }
    }
}
