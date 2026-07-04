plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "dev.swissknife"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        pluginVerifier()
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

intellijPlatform {
    pluginConfiguration {
        name = "SwissKnife Javanist"
        ideaVersion { sinceBuild = "251" }
        vendor { name = "SwissKnife"; email = "dev@swissknife.local" }
    }
}
