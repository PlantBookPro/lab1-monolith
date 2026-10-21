import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.8"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.plantarena"
version = "0.1.0-SNAPSHOT"

description = "Лабораторная №1: модульный монолит DDD — турниры растений"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

extra["springdoc.version"] = "3.1.1"
extra["archunit.version"] = "1.5.0"
extra["testcontainers.version"] = "2.0.5"
extra["onnxruntime.version"] = "1.20.0"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${property("springdoc.version")}")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.microsoft.onnxruntime:onnxruntime:${property("onnxruntime.version")}")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-jdbc-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit6:${property("archunit.version")}")
    testImplementation("org.awaitility:awaitility")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

// Integration-тесты (*IT) лежат в src/test/java вместе с unit-тестами
// (наследие Maven-конвенции): отдельная задача Test на том же classpath,
// фильтром разделяются по имени класса.
val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Интеграционные/приёмочные тесты (*IT) на Testcontainers PostgreSQL"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class")
    if (project.hasProperty("inference")) {
        useJUnitPlatform { includeTags("inference") }
    } else {
        useJUnitPlatform { excludeTags("inference") }
    }
    shouldRunAfter(tasks.test)
    dependsOn(tasks.named("downloadModerationModel"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.SHORT
    }
}

tasks.test {
    exclude("**/*IT.class")
}

// Исполняемый boot-jar — единственный артефакт; plain-jar не нужен
tasks.jar { enabled = false }

tasks.named("check") {
    dependsOn(integrationTestTask)
}

// Модель классификатора (ADR-009): аналог download-maven-plugin — качается перед
// integration-тестами; offline-сборка оставляет каталог пустым (задания честно
// уходят в RETRY, тесты с тегом inference пропускаются по Assumption).
val moderationModelFile = layout.buildDirectory.file("models/mobilenetv2-1.0.onnx")
tasks.register("downloadModerationModel") {
    outputs.file(moderationModelFile)
    doLast {
        val target = moderationModelFile.get().asFile
        target.parentFile.mkdirs()
        try {
            ant.withGroovyBuilder {
                "get"(
                    "src" to "https://github.com/onnx/models/raw/main/validated/vision/classification/mobilenet/model/mobilenetv2-12.onnx",
                    "dest" to target,
                    "skipExisting" to false
                )
            }
        } catch (e: Exception) {
            logger.lifecycle("Модель не скачана (offline): {}", e.message)
            target.delete()
        }
    }
}

jacoco {
    toolVersion = "0.8.15"
}

// Единый отчёт по unit- и integration-прогонам: обе exec-записи подаются
// в отчёт и gate напрямую (JacocoMerge удалён в Gradle 9).
val testExec = layout.buildDirectory.file("jacoco/test.exec")
val integrationExec = layout.buildDirectory.file("jacoco/integrationTest.exec")

val jacocoReport = tasks.register<JacocoReport>("jacocoMergedReport") {
    group = "verification"
    dependsOn(tasks.test, integrationTestTask)
    executionData(testExec, integrationExec)
    sourceSets(sourceSets.main.get())
    reports {
        html.required = true
        xml.required = true
        csv.required = true
    }
}

val jacocoCheck = tasks.register<JacocoCoverageVerification>("jacocoMergedCheck") {
    group = "verification"
    dependsOn(tasks.test, integrationTestTask)
    executionData(testExec, integrationExec)
    sourceSets(sourceSets.main.get())
    // Точка входа: main() не выполняется тестами, покрывается запуском приложения
    classDirectories.setFrom(
        files(classDirectories.map { dir ->
            fileTree(dir) { exclude("com/plantarena/PlantArenaApplication.class") }
        })
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(jacocoCheck, jacocoReport)
}
