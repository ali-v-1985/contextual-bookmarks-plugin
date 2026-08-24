import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.3.4")
        bundledModule("intellij.platform.vcs.dvcs")
        // VcsRepositoryManager is public API but is physically shipped in this module in build 253.
        bundledModule("intellij.platform.vcs.dvcs.impl")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            // IntelliJ IDEA 2026.2.0.1 resolves to build branch 262.
            untilBuild = "262.*"
        }
        changeNotes = provider {
            changelog.renderItem(
                changelog.getOrNull(project.version.toString())
                    ?: changelog.getUnreleased(),
                Changelog.OutputType.HTML,
            )
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.1.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.0.1")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        hidden = true
    }
}

tasks {
    wrapper {
        gradleVersion = "9.5.0"
        distributionType = Wrapper.DistributionType.BIN
    }
}
