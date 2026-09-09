import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("app.kmp.library")
}

val entitlements = layout.projectDirectory.file("src/iosTest/keychain-test.entitlements")
kotlin.targets.withType<KotlinNativeTarget>().matching { it.name == "iosSimulatorArm64" }.configureEach {
    binaries.withType<TestExecutable>().configureEach {
        linkerOpts("-sectcreate", "__TEXT", "__entitlements", entitlements.asFile.absolutePath)
        linkTaskProvider.configure { inputs.file(entitlements) }
    }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    standalone.set(false)
    device.set(providers.gradleProperty("bmaps.test.iosDevice").orElse("booted"))
}
