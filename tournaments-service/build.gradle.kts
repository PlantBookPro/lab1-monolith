plugins {
    `java`
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared-lib"))
    implementation(project(":shared-webflux"))
    implementation(libs.spring.boot.webflux)
    implementation(libs.spring.boot.jpa)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.actuator)
    implementation(libs.eureka.client)
    implementation(libs.config.client)
    implementation(libs.openfeign)
    implementation(libs.circuitbreaker)
    implementation(libs.openapi.webflux)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.boot.flyway)
    runtimeOnly(libs.postgresql)

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.webflux.test)
    testImplementation(libs.spring.boot.jdbc.test)
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility)
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
    shouldRunAfter(tasks.test)
}
tasks.test { exclude("**/*IT.class") }
tasks.named("check") { dependsOn(integrationTestTask) }
tasks.jar { enabled = false }
