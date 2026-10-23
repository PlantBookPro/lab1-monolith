import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.springframework.boot") version "4.0.8" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.plantarena"
    version = "0.2.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    plugins.withType<JavaPlugin>().configureEach {
        extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.SHORT
        }
    }
}
