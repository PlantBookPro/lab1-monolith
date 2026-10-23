plugins {
    `java`
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared-lib"))
    implementation(project(":shared-web"))
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.actuator)
    implementation(libs.eureka.client)
    implementation(libs.config.client)
    implementation(libs.openapi.webmvc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.boot.flyway)
    implementation(libs.openfeign)
    implementation(libs.spring.boot.http.converter)
    implementation(libs.circuitbreaker)
    implementation(libs.onnxruntime)
    runtimeOnly(libs.postgresql)

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.jdbc.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit)
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
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
tasks.test { exclude("**/*IT.class") }
tasks.named("check") { dependsOn(integrationTestTask) }
tasks.jar { enabled = false }

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
