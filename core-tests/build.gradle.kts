plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Gradle's Windows test worker cannot load loose test classes from a path
// containing non-ASCII characters. Keep only generated test output in an
// ASCII temporary path; source and deliverables remain in the workspace.
layout.buildDirectory = file(System.getProperty("java.io.tmpdir"))
    .resolve("lossless-video-rotate-core-tests")

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDir("../app/src/main/java")
            kotlin.include("com/losslessrotate/video/core/RotationAngle.kt")
            kotlin.include("com/losslessrotate/video/core/IsoBmffAnalyzer.kt")
            kotlin.include("com/losslessrotate/video/core/PatchCopier.kt")
            kotlin.include("com/losslessrotate/video/job/RotationJobSpec.kt")
            kotlin.include("com/losslessrotate/video/job/MediaStoreOutputPolicy.kt")
            kotlin.include("com/losslessrotate/video/media/VideoLibraryLogic.kt")
            kotlin.include("com/losslessrotate/video/ui/FilterState.kt")
        }
        test {
            kotlin.srcDir("src/test/kotlin")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    workingDir = rootProject.projectDir
    System.getProperty("emulatorOutput")?.let { systemProperty("emulatorOutput", it) }
    System.getProperty("emulatorExpectedAngle")?.let { systemProperty("emulatorExpectedAngle", it) }
}
