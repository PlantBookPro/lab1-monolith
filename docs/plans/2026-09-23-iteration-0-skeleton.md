# Итерация 0 — Скелет модульного монолита: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Каркас Plant Arena: один Maven-модуль Spring Boot 4 с пакетами bounded contexts, ArchUnit-правилами границ, Testcontainers, Flyway по схемам контекстов, ProblemDetail-ошибками с traceId, Clock-бин, Swagger UI, Docker Compose и доменной документацией (глоссарий, context map, ADR).

**Architecture:** Модульный монолит `com.plantarena` с пакетами контекстов (identity, media, plants, moderation, tournaments, geo, feed), техническим `shared` и связывающим `config`. Границы контекстов проверяются ArchUnit-тестами, а не структурой сборки. Одна PostgreSQL со схемой на контекст, миграции Flyway — по каталогу на контекст.

**Tech Stack:** Java 21, Spring Boot 4.0.8 (webmvc, data-jpa, validation, actuator), Flyway 11.14.1 (из BOM Boot), PostgreSQL 17.5, springdoc-openapi 3.1.1, JUnit Jupiter 6.0.3 (из BOM Boot), AssertJ, Testcontainers 2.0.5, ArchUnit 1.5.0 (`archunit-junit6`), JaCoCo 0.8.15, Maven Wrapper.

## Global Constraints

- Один Maven-модуль; никаких multi-module build и артефактов `-api`/`-domain`.
- Версии закреплены (Boot 4.0.8, springdoc 3.1.1, Testcontainers 2.0.5, ArchUnit 1.5.0, JaCoCo 0.8.15, ONNX Runtime 1.30.0 — позже); без SNAPSHOT/milestone/latest.
- Пакеты контекстов: `identity`, `media`, `plants`, `moderation`, `tournaments`, `geo`, `feed` + `shared` (только техническое) + `config` (единственное место, знающее несколько контекстов).
- Между контекстами — только UUID, `api`-контракты и события; `domain` без Spring/JPA/Jackson/HTTP.
- Enum в БД — VARCHAR + CHECK, JPA `EnumType.STRING`.
- Время — только из `Clock` (UTC); домен получает `Instant now` аргументом.
- Тесты называются на едином языке, `@DisplayName` на русском допустим.
- Git: ветка `feat/iteration-0-skeleton`, Conventional Commits, красные тесты в `main` не попадают, push — только по отдельной команде пользователя.
- `./mvnw verify` — единственная команда полной проверки; JaCoCo gate: LINE ≥ 70%.
- Секреты только через ENV; `.env.example` — только примеры.
- Docker-образы: `postgres:17.5-alpine`, `maven:3.9.11-eclipse-temurin-21`, `eclipse-temurin:21-jre` (теги проверены 2026-09-23).

---

### Task 1: Maven-скелет, wrapper, приложение

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `src/main/java/com/plantarena/PlantArenaApplication.java`
- Create: `src/main/resources/application.yml`

**Interfaces:**
- Produces: собираемый проект `com.plantarena:plant-arena:0.1.0-SNAPSHOT`; класс входа `com.plantarena.PlantArenaApplication`; свойства `spring.datasource.*` из ENV (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`); `spring.flyway.enabled=false` (миграции делает Task 6); плагины surefire/failsafe с argLine-свойствами JaCoCo (`jacoco.surefire.argLine`, `jacoco.failsafe.argLine`), используемые всеми последующими задачами.

- [ ] **Step 1: Создать ветку и рабочую структуру**

```bash
cd /Users/vovabag/Desktop/personal/plantBook/lab1-monolith
git checkout -b feat/iteration-0-skeleton
mkdir -p src/main/java/com/plantarena src/main/resources
```

- [ ] **Step 2: Написать `pom.xml` (полностью)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.8</version>
    <relativePath/>
  </parent>

  <groupId>com.plantarena</groupId>
  <artifactId>plant-arena</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>Plant Arena</name>
  <description>Лабораторная №1: модульный монолит DDD — турниры растений</description>

  <properties>
    <java.version>21</java.version>
    <springdoc.version>3.1.1</springdoc.version>
    <archunit.version>1.5.0</archunit.version>
    <testcontainers.version>2.0.5</testcontainers.version>
    <jacoco.version>0.8.15</jacoco.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit6</artifactId>
      <version>${archunit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <argLine>${jacoco.surefire.argLine}</argLine>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
        <configuration>
          <argLine>${jacoco.failsafe.argLine}</argLine>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>${jacoco.version}</version>
        <executions>
          <execution>
            <id>prepare-unit-test-agent</id>
            <goals>
              <goal>prepare-agent</goal>
            </goals>
            <configuration>
              <propertyName>jacoco.surefire.argLine</propertyName>
              <destFile>${project.build.directory}/jacoco-surefire.exec</destFile>
            </configuration>
          </execution>
          <execution>
            <id>prepare-integration-test-agent</id>
            <goals>
              <goal>prepare-agent-integration</goal>
            </goals>
            <configuration>
              <propertyName>jacoco.failsafe.argLine</propertyName>
              <destFile>${project.build.directory}/jacoco-failsafe.exec</destFile>
            </configuration>
          </execution>
          <execution>
            <id>merge-coverage</id>
            <phase>verify</phase>
            <goals>
              <goal>merge</goal>
            </goals>
            <configuration>
              <fileSets>
                <fileSet>
                  <directory>${project.build.directory}</directory>
                  <includes>
                    <include>jacoco-surefire.exec</include>
                    <include>jacoco-failsafe.exec</include>
                  </includes>
                </fileSet>
              </fileSets>
              <destFile>${project.build.directory}/jacoco-merged.exec</destFile>
            </configuration>
          </execution>
          <execution>
            <id>report-coverage</id>
            <phase>verify</phase>
            <goals>
              <goal>report</goal>
            </goals>
            <configuration>
              <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
              <outputDirectory>${project.reporting.outputDirectory}/jacoco-merged</outputDirectory>
            </configuration>
          </execution>
          <execution>
            <id>enforce-line-coverage</id>
            <phase>verify</phase>
            <goals>
              <goal>check</goal>
            </goals>
            <configuration>
              <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
              <rules>
                <rule>
                  <element>BUNDLE</element>
                  <limits>
                    <limit>
                      <counter>LINE</counter>
                      <value>COVEREDRATIO</value>
                      <minimum>0.70</minimum>
                    </limit>
                  </limits>
                </rule>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Примечание: `flyway-core` и `flyway-database-postgresql` управляются BOM Boot (Flyway 11.14.1). Если сборка пожалуется на отсутствие версии у `flyway-database-postgresql` (маловероятно, зависимость управляется Boot), добавить `<version>11.14.1</version>`.

- [ ] **Step 3: Написать `.gitignore`**

```
target/
.idea/
*.iml
.DS_Store
.env
storage/
```

- [ ] **Step 4: Написать `PlantArenaApplication.java`**

```java
package com.plantarena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PlantArenaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlantArenaApplication.class, args);
    }
}
```

- [ ] **Step 5: Написать `application.yml`**

```yaml
spring:
  application:
    name: plant-arena
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/plantarena}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: false # миграции по схемам контекстов выполняет config.SchemaMigrationConfig (ADR-003)

server:
  port: ${SERVER_PORT:8080}

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 6: Сгенерировать Maven Wrapper (пин версии дистрибутива 3.9.11)**

```bash
mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.2:wrapper -Dmaven=3.9.11
```

Expected: созданы `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (в `distributionUrl` — apache-maven-3.9.11).

- [ ] **Step 7: Проверить сборку через wrapper**

```bash
./mvnw -q test
```

Expected: `BUILD SUCCESS` (тестов ещё нет). Примечание: `./mvnw verify` на этом шаге не запускать — без тестов JaCoCo `merge` не найдёт exec-файлы; полный `verify` заработает с Task 3.

- [ ] **Step 8: Commit**

```bash
git add pom.xml .gitignore mvnw mvnw.cmd .mvn src/
git commit -m "feat: maven-скелет монолита (Boot 4.0.8, Java 21, JaCoCo gate 70%)"
```

---

### Task 2: ArchUnit-правила границ контекстов

**Files:**
- Test: `src/test/java/com/plantarena/architecture/ContextBoundaryTest.java`
- Test: `src/test/java/com/plantarena/architecture/LayerRulesTest.java`
- Create: `src/test/resources/archunit.properties`

**Interfaces:**
- Produces: исполняемые правила раздела 10.2 требований: список контекстов `CONTEXTS` (identity, media, plants, moderation, tournaments, geo, feed), карта допустимых upstream-зависимостей `ALLOWED_UPSTREAM`, правила слоёв внутри контекста (`domain` ← `application` ← `adapter`, `api` независимо), чистота `domain`, размещение JPA, ограничения на `shared`/`config`. Все последующие итерации обязаны держать эти тесты зелёными.

Примечание о TDD: это архитектурные тесты-стражи. На пустом коде они проходят тривиально (пакетов контекстов ещё нет — спека запрещает пустые пакеты); «красными» они становятся при нарушении границ в итерациях 1–9. `archunit.properties` с `failOnEmptyShould=false` — временное упрощение до появления кода контекстов; удаляется в итерации 9 (Task в плане итерации 9).

- [ ] **Step 1: Написать `archunit.properties`**

```properties
# Временно (итерация 0): правила по ещё не существующим пакетам не падают на пустом should.
# Удалить в итерации 9, когда все контексты содержат код.
archRule.failOnEmptyShould=false
```

- [ ] **Step 2: Написать `ContextBoundaryTest.java`**

```java
package com.plantarena.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила границ bounded contexts (раздел 10.2 требований, правила 1–3).
 * Тестовые классы исключены из анализа: приёмочные IT legitimately импортируют
 * несколько контекстов, правила применяются только к основному коду.
 */
@AnalyzeClasses(packages = "com.plantarena", importOptions = ImportOption.DoNotIncludeTests.class)
public class ContextBoundaryTest {

    static final List<String> CONTEXTS =
        List.of("identity", "media", "plants", "moderation", "tournaments", "geo", "feed");

    /** Допустимые upstream-зависимости по context map (раздел 4.3 требований). */
    static final Map<String, Set<String>> ALLOWED_UPSTREAM = Map.of(
        "identity", Set.of(),
        "media", Set.of(),
        "geo", Set.of(),
        "plants", Set.of("media"),
        "moderation", Set.of("plants", "media"),
        "tournaments", Set.of("identity", "plants", "geo"),
        "feed", Set.of("tournaments", "plants", "media", "identity")
    );

    @ArchTest
    static void контексты_не_образуют_циклов(JavaClasses classes) {
        slices().matching("com.plantarena.(*)..").should().beFreeOfCycles().check(classes);
    }

    @ArchTest
    static void вне_адаптеров_контекст_не_зависит_от_других_контекстов(JavaClasses classes) {
        for (String a : CONTEXTS) {
            for (String b : CONTEXTS) {
                if (a.equals(b)) {
                    continue;
                }
                ArchRule rule = noClasses()
                    .that().resideInAPackage("com.plantarena." + a + "..")
                    .and().resideOutsideOfPackage("com.plantarena." + a + ".adapter..")
                    .should().dependOnClassesThat().resideInAPackage("com.plantarena." + b + "..");
                rule.check(classes);
            }
        }
    }

    @ArchTest
    static void адаптеры_обращаются_к_чужим_контекстам_только_через_api(JavaClasses classes) {
        for (String a : CONTEXTS) {
            for (String b : CONTEXTS) {
                if (a.equals(b)) {
                    continue;
                }
                ArchRule rule = noClasses()
                    .that().resideInAPackage("com.plantarena." + a + ".adapter..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.plantarena." + b + ".domain..",
                        "com.plantarena." + b + ".application..",
                        "com.plantarena." + b + ".adapter..");
                rule.check(classes);
            }
        }
    }

    @ArchTest
    static void зависимости_между_контекстами_только_в_допустимых_направлениях(JavaClasses classes) {
        for (String a : CONTEXTS) {
            for (String b : CONTEXTS) {
                if (a.equals(b) || ALLOWED_UPSTREAM.get(a).contains(b)) {
                    continue;
                }
                ArchRule rule = noClasses()
                    .that().resideInAPackage("com.plantarena." + a + "..")
                    .should().dependOnClassesThat().resideInAPackage("com.plantarena." + b + "..");
                rule.check(classes);
            }
        }
    }

    @ArchTest
    static void только_config_знает_несколько_контекстов_одновременно(JavaClasses classes) {
        classes.stream()
            .filter(javaClass -> javaClass.getPackageName().startsWith("com.plantarena"))
            .filter(javaClass -> !javaClass.getPackageName().startsWith("com.plantarena.config"))
            .forEach(javaClass -> {
                Set<String> dependedContexts = javaClass.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass().getPackageName())
                    .map(ContextBoundaryTest::contextOf)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
                assertThat(dependedContexts)
                    .as("класс %s зависит от нескольких контекстов одновременно", javaClass.getName())
                    .hasSizeLessThanOrEqualTo(1);
            });
    }

    private static String contextOf(String packageName) {
        return CONTEXTS.stream()
            .filter(context -> packageName.equals("com.plantarena." + context)
                || packageName.startsWith("com.plantarena." + context + "."))
            .findFirst()
            .orElse(null);
    }
}
```

- [ ] **Step 3: Написать `LayerRulesTest.java`**

```java
package com.plantarena.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Правила слоёв внутри контекстов и технических пакетов (раздел 10.2, правила 4–9).
 */
@AnalyzeClasses(packages = "com.plantarena", importOptions = ImportOption.DoNotIncludeTests.class)
public class LayerRulesTest {

    @ArchTest
    static void слои_внутри_контекста_соблюдают_иерархию(JavaClasses classes) {
        for (String context : ContextBoundaryTest.CONTEXTS) {
            String ctx = "com.plantarena." + context;

            ArchRule domainIsolated = noClasses()
                .that().resideInAPackage(ctx + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    ctx + ".application..", ctx + ".adapter..", ctx + ".api..");
            domainIsolated.check(classes);

            ArchRule applicationIsolated = noClasses()
                .that().resideInAPackage(ctx + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ctx + ".adapter..");
            applicationIsolated.check(classes);

            ArchRule apiIsolated = noClasses()
                .that().resideInAPackage(ctx + ".api..")
                .should().dependOnClassesThat().resideInAnyPackage(ctx + ".domain..", ctx + ".adapter..");
            apiIsolated.check(classes);
        }
    }

    @ArchTest
    static void домен_не_знает_об_инфраструктуре(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage("com.plantarena..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "com.fasterxml..",
                "java.net.http..")
            .check(classes);
    }

    @ArchTest
    static void jpa_сущности_только_в_adapter_out_persistence(JavaClasses classes) {
        classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..adapter.out.persistence..")
            .check(classes);
    }

    @ArchTest
    static void spring_data_репозитории_только_в_adapter_out_persistence(JavaClasses classes) {
        classes()
            .that().areAssignableTo(Repository.class)
            .should().resideInAPackage("..adapter.out.persistence..")
            .check(classes);
    }

    @ArchTest
    static void контроллеры_не_обращаются_к_домену_и_репозиториям(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage("com.plantarena..adapter.in.web..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.plantarena..domain..",
                "com.plantarena..adapter.out.persistence..")
            .check(classes);

        noClasses()
            .that().resideInAPackage("com.plantarena..adapter.in.web..")
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .check(classes);
    }

    @ArchTest
    static void shared_не_зависит_от_контекстов_и_config(JavaClasses classes) {
        for (String context : ContextBoundaryTest.CONTEXTS) {
            noClasses()
                .that().resideInAPackage("com.plantarena.shared..")
                .should().dependOnClassesThat().resideInAPackage("com.plantarena." + context + "..")
                .check(classes);
        }
        noClasses()
            .that().resideInAPackage("com.plantarena.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.plantarena.config..")
            .check(classes);
    }
}
```

- [ ] **Step 4: Запустить (должно быть зелёным — стражи на пустом коде)**

```bash
./mvnw -q test
```

Expected: `BUILD SUCCESS`, выполнены тесты `ContextBoundaryTest` и `LayerRulesTest`.

- [ ] **Step 5: Commit**

```bash
git add src/test/
git commit -m "test: archunit-правила границ контекстов и слоёв (раздел 10.2)"
```

---

### Task 3: Testcontainers PostgreSQL + smoke IT

**Files:**
- Test: `src/test/java/com/plantarena/ApplicationStartupIT.java`
- Create: `src/test/java/com/plantarena/support/PostgresSupport.java`
- Create: `src/test/java/com/plantarena/support/AbstractIntegrationTest.java`

**Interfaces:**
- Produces: `com.plantarena.support.PostgresSupport.postgres()` — singleton `PostgreSQLContainer<?>` (postgres:17.5-alpine, БД `plantarena`), стартуется один раз на JVM; `com.plantarena.support.AbstractIntegrationTest` — базовый класс всех IT (`@SpringBootTest` + `RANDOM_PORT` + `@DynamicPropertySource` с datasource из контейнера). Все IT последующих итераций наследуют его.

- [ ] **Step 1: Написать падающий smoke IT (без datasource)**

`src/test/java/com/plantarena/ApplicationStartupIT.java`:

```java
package com.plantarena;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Приложение запускается на реальном PostgreSQL (Testcontainers)")
@SpringBootTest
class ApplicationStartupIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void контекст_приложения_поднимается() {
        assertThat(applicationContext).isNotNull();
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении**

```bash
./mvnw -q -Dit.name=ApplicationStartupIT verify
```

Expected: FAIL — `Failed to load ApplicationContext`: нет DataSource (на classpath нет embedded-БД, datasource не настроен). Это красное состояние доказывает необходимость Testcontainers-инфраструктуры.

- [ ] **Step 3: Реализовать singleton-контейнер и базовый IT**

`src/test/java/com/plantarena/support/PostgresSupport.java`:

```java
package com.plantarena.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton Testcontainers-контейнер PostgreSQL на весь прогон тестов данной JVM.
 */
public final class PostgresSupport {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17.5-alpine")
            .withDatabaseName("plantarena")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    private PostgresSupport() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }
}
```

`src/test/java/com/plantarena/support/AbstractIntegrationTest.java`:

```java
package com.plantarena.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

- [ ] **Step 4: Переписать IT на базовый класс**

`src/test/java/com/plantarena/ApplicationStartupIT.java`:

```java
package com.plantarena;

import com.plantarena.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Приложение запускается на реальном PostgreSQL (Testcontainers)")
class ApplicationStartupIT extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void контекст_приложения_поднимается() {
        assertThat(applicationContext).isNotNull();
    }
}
```

- [ ] **Step 5: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dit.name=ApplicationStartupIT verify
```

Expected: PASS, `BUILD SUCCESS`. Теперь существует `target/jacoco-failsafe.exec` — полный `./mvnw verify` больше не падает на JaCoCo merge.

- [ ] **Step 6: Полный verify**

```bash
./mvnw -q verify
```

Expected: `BUILD SUCCESS` (surefire: ArchUnit; failsafe: ApplicationStartupIT; JaCoCo report + check).

- [ ] **Step 7: Commit**

```bash
git add src/test/
git commit -m "feat: testcontainers-postgresql и базовый класс интеграционных тестов"
```

---

### Task 4: Clock-бин (UTC)

**Files:**
- Test: `src/test/java/com/plantarena/config/ClockConfigTest.java`
- Create: `src/main/java/com/plantarena/config/ClockConfig.java`

**Interfaces:**
- Produces: бин `java.time.Clock` с именем `utcClock` (метод `ClockConfig.utcClock()`), всегда `Clock.systemUTC()`. Все контексты внедряют `Clock` для времени (домен получает `Instant now` аргументом — спека раздел 5).

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/plantarena/config/ClockConfigTest.java`:

```java
package com.plantarena.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Часы приложения всегда в UTC")
class ClockConfigTest {

    @Test
    void clock_бин_использует_UTC() {
        Clock clock = new ClockConfig().utcClock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении**

```bash
./mvnw -q -Dtest=ClockConfigTest test
```

Expected: COMPILATION ERROR — `ClockConfig` не существует.

- [ ] **Step 3: Реализовать `ClockConfig`**

`src/main/java/com/plantarena/config/ClockConfig.java`:

```java
package com.plantarena.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единственный источник времени приложения: UTC (раздел 5 требований).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dtest=ClockConfigTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/
git commit -m "feat: clock-бин в UTC"
```

---

### Task 5: ProblemDetail-ошибки и traceId

**Files:**
- Test: `src/test/java/com/plantarena/shared/web/ApiErrorHandlingIT.java`
- Create: `src/main/java/com/plantarena/shared/web/ApiError.java`
- Create: `src/main/java/com/plantarena/shared/web/TraceIdFilter.java`
- Create: `src/main/java/com/plantarena/shared/web/SharedWebConfig.java`
- Create: `src/main/java/com/plantarena/shared/web/ApiExceptionHandler.java`

**Interfaces:**
- Produces: тело ошибки `{type,title,status,detail,instance,code,fieldErrors,traceId}` (record `ApiError`, `ApiError.FieldError(field,message)`); HTTP-заголовок `X-Trace-Id` и атрибут запроса `plantarena.traceId` (константы `TraceIdFilter.TRACE_ID_HEADER`, `TraceIdFilter.TRACE_ID_ATTRIBUTE`); стабильные коды ошибок `RESOURCE_NOT_FOUND`, `MALFORMED_BODY`, `VALIDATION_FAILED`, `INTERNAL_ERROR`. Все доменные исключения итераций 1–9 добавляются в `ApiExceptionHandler` с сохранением этого контракта.

- [ ] **Step 1: Написать падающий IT**

`src/test/java/com/plantarena/shared/web/ApiErrorHandlingIT.java`:

```java
package com.plantarena.shared.web;

import com.plantarena.support.AbstractIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ошибки API возвращаются в виде ProblemDetail с traceId")
class ApiErrorHandlingIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void неизвестный_путь_даёт_404_с_телом_ProblemDetail_и_traceId() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/no-such-resource", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER)).isNotBlank();

        DocumentContext body = JsonPath.parse(response.getBody());
        assertThat((Integer) body.read("$.status")).isEqualTo(404);
        assertThat(body.read("$.code", String.class)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body.read("$.title", String.class)).isEqualTo("Not Found");
        assertThat(body.read("$.traceId", String.class))
            .isEqualTo(response.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER));
    }
}
```

Примечание: если `TestRestTemplate` отсутствует в Boot 4 (маловероятно), заменить на `@AutoConfigureMockMvc` + `MockMvc` с теми же утверждениями.

- [ ] **Step 2: Запустить и убедиться в падении**

```bash
./mvnw -q -Dit.name=ApiErrorHandlingIT verify
```

Expected: FAIL — дефолтное тело Boot-ошибки не содержит `code`/`traceId` (assertion error на `$.code`), заголовок `X-Trace-Id` отсутствует.

- [ ] **Step 3: Реализовать `ApiError`**

`src/main/java/com/plantarena/shared/web/ApiError.java`:

```java
package com.plantarena.shared.web;

import java.net.URI;
import java.util.List;

/**
 * Тело ошибки ProblemDetail-вида:
 * {type,title,status,detail,instance,code,fieldErrors,traceId} (раздел 13 требований).
 */
public record ApiError(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String code,
        List<FieldError> fieldErrors,
        String traceId) {

    public record FieldError(String field, String message) {
    }

    public ApiError withFieldErrors(List<FieldError> errors) {
        return new ApiError(type, title, status, detail, instance, code, errors, traceId);
    }
}
```

- [ ] **Step 4: Реализовать `TraceIdFilter`**

`src/main/java/com/plantarena/shared/web/TraceIdFilter.java`:

```java
package com.plantarena.shared.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;

/**
 * Назначает traceId каждому HTTP-запросу и возвращает его в заголовке X-Trace-Id.
 */
public final class TraceIdFilter implements Filter {

    public static final String TRACE_ID_ATTRIBUTE = "plantarena.traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString();
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpRequest.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        httpResponse.setHeader(TRACE_ID_HEADER, traceId);
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: Реализовать `SharedWebConfig` (регистрация фильтра)**

`src/main/java/com/plantarena/shared/web/SharedWebConfig.java`:

```java
package com.plantarena.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot автоматически регистрирует бины типа Filter в servlet-контейнере.
 */
@Configuration
public class SharedWebConfig {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }
}
```

- [ ] **Step 6: Реализовать `ApiExceptionHandler`**

`src/main/java/com/plantarena/shared/web/ApiExceptionHandler.java`:

```java
package com.plantarena.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Переводит исключения в ProblemDetail-подобное тело ApiError.
 * Доменные исключения контекстов добавляются сюда в итерациях 1–9;
 * они не знают об HTTP (раздел 13 требований).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
            "Ресурс не найден: " + request.getRequestURI(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_BODY",
            "Некорректное тело запроса", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
            .toList();
        ApiError error = error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Некорректные поля запроса", request).withFieldErrors(fieldErrors);
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Внутренняя ошибка сервера", request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(error(status, code, detail, request));
    }

    private ApiError error(HttpStatus status, String code, String detail, HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return new ApiError(
            URI.create("about:blank"),
            status.getReasonPhrase(),
            status.value(),
            detail,
            URI.create(request.getRequestURI()),
            code,
            List.of(),
            traceId);
    }
}
```

- [ ] **Step 7: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dit.name=ApiErrorHandlingIT verify
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/
git commit -m "feat: problemdetail-ошибки с traceId и общим обработчиком"
```

---

### Task 6: Flyway по схемам контекстов

**Files:**
- Test: `src/test/java/com/plantarena/config/SchemaMigrationIT.java`
- Create: `src/main/java/com/plantarena/config/SchemaMigrationConfig.java`
- Create: `src/main/resources/db/migration/identity/V1__init.sql`
- Create: `src/main/resources/db/migration/media/V1__init.sql`
- Create: `src/main/resources/db/migration/plants/V1__init.sql`
- Create: `src/main/resources/db/migration/moderation/V1__init.sql`
- Create: `src/main/resources/db/migration/tournaments/V1__init.sql`
- Create: `src/main/resources/db/migration/geo/V1__init.sql`
- Create: `src/main/resources/db/migration/feed/V1__init.sql`

**Interfaces:**
- Produces: `SchemaMigrationConfig.CONTEXT_SCHEMAS` — упорядоченный список схем (`identity`, `media`, `plants`, `moderation`, `tournaments`, `geo`, `feed`); контракт миграций: каталог `classpath:db/migration/<context>`, схема и `flyway_schema_history` — в схеме контекста (ADR-003). Миграции итераций 1–9 кладутся в эти каталоги с нумерацией `V2__`, `V3__` … в своём каталоге. Таблицы контекста создаются в своей схеме; межсхемных FK нет.

- [ ] **Step 1: Написать падающий IT**

`src/test/java/com/plantarena/config/SchemaMigrationIT.java`:

```java
package com.plantarena.config;

import com.plantarena.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Flyway: у каждого контекста своя схема и своя история миграций")
class SchemaMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void у_каждого_контекста_своя_схема_с_историей_миграций() {
        for (String schema : SchemaMigrationConfig.CONTEXT_SCHEMAS) {
            Integer historyTables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables"
                    + " where table_schema = ? and table_name = 'flyway_schema_history'",
                Integer.class, schema);
            assertThat(historyTables).as("схема %s", schema).isEqualTo(1);
        }
    }

    @Test
    void миграция_v1_применена_в_каждой_схеме() {
        for (String schema : SchemaMigrationConfig.CONTEXT_SCHEMAS) {
            Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from " + schema + ".flyway_schema_history where success",
                Integer.class);
            assertThat(applied).as("схема %s", schema).isGreaterThanOrEqualTo(1);
        }
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении**

```bash
./mvnw -q -Dit.name=SchemaMigrationIT verify
```

Expected: COMPILATION ERROR — `SchemaMigrationConfig` не существует.

- [ ] **Step 3: Реализовать `SchemaMigrationConfig`**

`src/main/java/com/plantarena/config/SchemaMigrationConfig.java`:

```java
package com.plantarena.config;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Миграции Flyway по контекстам (ADR-003): у каждого контекста свой каталог
 * db/migration/&lt;context&gt; и своя flyway_schema_history в своей схеме.
 * Выполняется при создании бина: пользовательские @Bean-методы регистрируются
 * и создаются раньше авто-конфигураций (в т.ч. EntityManagerFactory),
 * поэтому Hibernate validate видит уже мигрированные схемы.
 */
@Configuration
public class SchemaMigrationConfig {

    public static final List<String> CONTEXT_SCHEMAS =
        List.of("identity", "media", "plants", "moderation", "tournaments", "geo", "feed");

    @Bean
    public SchemaMigrations schemaMigrations(DataSource dataSource) {
        SchemaMigrations migrations = new SchemaMigrations(dataSource);
        migrations.migrateAll();
        return migrations;
    }

    public static final class SchemaMigrations {

        private final DataSource dataSource;

        private SchemaMigrations(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void migrateAll() {
            for (String context : CONTEXT_SCHEMAS) {
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/" + context)
                    .schemas(context)
                    .defaultSchema(context)
                    .table("flyway_schema_history")
                    .load()
                    .migrate();
            }
        }
    }
}
```

- [ ] **Step 4: Создать V1-миграции (7 файлов, содержимое одинаковое, кроме имени схемы)**

`src/main/resources/db/migration/identity/V1__init.sql`:

```sql
-- Схема identity (контекст identity). Таблицы появятся в итерации 1.
SELECT 1;
```

Аналогично для `media`, `plants`, `moderation`, `tournaments`, `geo`, `feed` (заменить имя схемы в комментарии).

- [ ] **Step 5: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dit.name=SchemaMigrationIT verify
```

Expected: PASS. Если падает с ошибкой валидации Hibernate/порядка бинов (маловероятно): добавить в `SchemaMigrationConfig` статический бин `EntityManagerFactoryDependsOnPostProcessor` для `SchemaMigrations.class` (класс из `spring-boot-autoconfigure`, если доступен в Boot 4), либо перенести вызов `migrateAll()` в `@PostConstruct`.

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: flyway-миграции по схемам контекстов (ADR-003)"
```

---

### Task 7: OpenAPI и Swagger UI

**Files:**
- Test: `src/test/java/com/plantarena/config/OpenApiDocsIT.java`
- Modify: `pom.xml` (добавить зависимость springdoc)
- Create: `src/main/java/com/plantarena/config/OpenApiConfig.java`

**Interfaces:**
- Produces: единая точка Swagger UI (`/swagger-ui/index.html`) и спецификация `/v3/api-docs`; бин `OpenAPI` с title `Plant Arena API`, version `1`. Контроллеры итераций 1–9 аннотируются `@Tag(name = "<контекст>")` с уникальными operationId (префикс контекста).

- [ ] **Step 1: Написать падающий IT**

`src/test/java/com/plantarena/config/OpenApiDocsIT.java`:

```java
package com.plantarena.config;

import com.plantarena.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenAPI: единая спецификация и Swagger UI")
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void openapi_спецификация_доступна() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"");
        assertThat(response.getBody()).contains("Plant Arena API");
    }

    @Test
    void swagger_ui_доступен() {
        ResponseEntity<String> response = rest.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении**

```bash
./mvnw -q -Dit.name=OpenApiDocsIT verify
```

Expected: FAIL — `/v3/api-docs` и `/swagger-ui/index.html` возвращают 404 (springdoc ещё не подключён).

- [ ] **Step 3: Добавить зависимость в `pom.xml`**

В `<dependencies>`, после `spring-boot-starter-validation`:

```xml
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
      <version>${springdoc.version}</version>
    </dependency>
```

- [ ] **Step 4: Реализовать `OpenApiConfig`**

`src/main/java/com/plantarena/config/OpenApiConfig.java`:

```java
package com.plantarena.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единая OpenAPI-спецификация монолита (раздел 13 требований).
 * Теги по контекстам добавляются контроллерами итераций 1–9.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI plantArenaOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Plant Arena API")
                .version("1")
                .description("Турниры растений: пользователи, файлы и растения, модерация, "
                    + "закрытые и глобальный турниры, голосование, лента"));
    }
}
```

- [ ] **Step 5: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dit.name=OpenApiDocsIT verify
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/
git commit -m "feat: springdoc-openapi и единый swagger ui"
```

---

### Task 8: Actuator health + Docker Compose

**Files:**
- Test: `src/test/java/com/plantarena/config/HealthEndpointIT.java`
- Modify: `pom.xml` (добавить actuator)
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `.env.example`

**Interfaces:**
- Produces: `GET /actuator/health` → `{"status":"UP"}` (healthcheck Compose); запуск системы из чистого состояния: `docker compose up --build`; ENV-параметры `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT`; volume `storage` (файловое хранилище media, итерация 2) и `pgdata`.

- [ ] **Step 1: Написать падающий IT**

`src/test/java/com/plantarena/config/HealthEndpointIT.java`:

```java
package com.plantarena.config;

import com.plantarena.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Actuator health для docker healthcheck")
class HealthEndpointIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void health_возвращает_UP() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении**

```bash
./mvnw -q -Dit.name=HealthEndpointIT verify
```

Expected: FAIL — 404 (actuator не подключён).

- [ ] **Step 3: Добавить actuator в `pom.xml`**

В `<dependencies>`, после `spring-boot-starter-validation`:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
```

- [ ] **Step 4: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dit.name=HealthEndpointIT verify
```

Expected: PASS.

- [ ] **Step 5: Написать `Dockerfile` (multi-stage)**

```dockerfile
# Сборка
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -DskipTests package

# Рантайм
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 6: Написать `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:17.5-alpine
    environment:
      POSTGRES_DB: plantarena
      POSTGRES_USER: ${DB_USERNAME:-postgres}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-postgres}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USERNAME:-postgres} -d plantarena"]
      interval: 5s
      timeout: 3s
      retries: 10

  app:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/plantarena
      DB_USERNAME: ${DB_USERNAME:-postgres}
      DB_PASSWORD: ${DB_PASSWORD:-postgres}
      SERVER_PORT: 8080
    ports:
      - "8080:8080"
    volumes:
      - storage:/app/storage
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 30s

volumes:
  pgdata:
  storage:
```

- [ ] **Step 7: Написать `.env.example`**

```
# Примеры параметров. Реальные значения передаются через ENV, не коммитятся.
DB_USERNAME=postgres
DB_PASSWORD=change-me
```

- [ ] **Step 8: Проверить compose-конфигурацию и запуск из чистого состояния**

```bash
docker compose config -q && echo "compose config OK"
docker compose up --build -d
# дождаться healthcheck (до ~60 сек)
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/v3/api-docs | head -c 200
docker compose down -v
```

Expected: `compose config OK`; health → `{"status":"UP",...}`; api-docs отдаёт JSON; `down -v` очищает volumes.

- [ ] **Step 9: Commit**

```bash
git add pom.xml Dockerfile docker-compose.yml .env.example src/
git commit -m "feat: actuator health и docker compose (app + postgres, healthchecks)"
```

---

### Task 9: Доменная документация и CI

**Files:**
- Create: `docs/domain/glossary.md`
- Create: `docs/domain/context-map.md`
- Create: `docs/domain/aggregates.md`
- Create: `docs/domain/adr/ADR-001-plant-classifier-onnx.md`
- Create: `docs/domain/adr/ADR-002-feed-own-projection.md`
- Create: `docs/domain/adr/ADR-003-flyway-per-context.md`
- Create: `docs/domain/adr/ADR-004-no-spring-modulith.md`
- Create: `docs/domain/adr/ADR-005-demo-identification.md`
- Create: `docs/domain/adr/ADR-006-product-assumptions.md`
- Create: `.github/workflows/ci.yml`
- Modify: `README.md`
- Create: `docs/assignment-lab1.md` (исходный текст задания из README)

**Interfaces:**
- Produces: глоссарий единого языка (источник имён для кода и тестов), context map (диаграмма + таблица взаимодействий с колонками для лаб 2/4), реестр агрегатов с инвариантами и именами защищающих тестов, ADR-001…006 (решения зафиксированы, ссылки из кода), CI `./mvnw verify` на push/PR.

- [ ] **Step 1: Сохранить исходное задание и написать README проекта**

```bash
git mv README.md docs/assignment-lab1.md
```

`README.md` (новый):

```markdown
# Plant Arena — Лабораторная №1 (модульный монолит)

Турниры растений: профили, загрузка изображений с автоматической модерацией
(ONNX), закрытые турниры с приглашениями, постоянный глобальный турнир с
географическим отбором, голосование, лента.

- Исходные требования: `docs/requirements.md` (полная спецификация)
- Задание лабораторной: `docs/assignment-lab1.md`
- Дизайн и решения: `docs/specs/`, `docs/domain/adr/`
- Дорожная карта итераций: `docs/plans/2026-09-23-roadmap.md`

## Запуск

```bash
docker compose up --build
# Swagger UI: http://localhost:8080/swagger-ui/index.html
```

## Проверка (единственная команда)

```bash
./mvnw verify
```

Запускает: unit- и application-тесты (Surefire), ArchUnit-правила границ,
интеграционные/приёмочные тесты на Testcontainers PostgreSQL (Failsafe),
отчёт и gate JaCoCo (LINE ≥ 70%).

## Git workflow

- Ветки: `feat/...`, `fix/...`, `test/...`, `docs/...`, `refactor/...`
- Conventional Commits; тесты коммитируются вместе с реализацией или раньше
- `main` всегда зелёный (`./mvnw verify`); push в удалённый `main` — только по явной команде

## Архитектура

Один Maven-модуль, bounded contexts по DDD (пакеты `identity`, `media`, `plants`,
`moderation`, `tournaments`, `geo`, `feed` + технический `shared` + `config`).
Границы проверяются ArchUnit-тестами (`src/test/java/com/plantarena/architecture/`).
Одна PostgreSQL, схема на контекст, миграции Flyway по каталогам контекстов.
Подробности: `docs/domain/context-map.md`, `docs/domain/aggregates.md`.

## Процедура выделения контекста в сервис (готовность к лабе №2)

1. Создать новый Spring Boot проект и перенести пакет `<context>` целиком
   и нужные части `shared`.
2. Перенести схему и каталог миграций `db/migration/<context>` без изменений.
3. `api`-фасады превратить в REST-контроллеры — DTO уже являются контрактом.
4. У потребителей заменить `adapter.out.<context>` (in-process) на Feign-адаптер
   с тем же портом.
5. Синхронные подписки на события заменить идемпотентными HTTP-командами
   с retry jobs (лаба №2) или outbox → Kafka → inbox (лаба №4).
6. Доменные и application-тесты переносятся без изменений и остаются зелёными;
   меняются только тесты адаптеров.
```

- [ ] **Step 2: Написать `docs/domain/glossary.md`**

Скопировать таблицу глоссария из `docs/requirements.md` (раздел 4.1) без изменений, добавив шапку:

```markdown
# Глоссарий единого языка

Названия классов, методов, событий, таблиц, эндпоинтов и тестов берутся отсюда.
Новое понятие сначала сюда, затем в код. При расхождении кода с глоссарием
правится глоссарий (или код — по решению ADR), затем реализация.

| Термин (RU) | Термин в коде | Контекст-владелец | Смысл |
|---|---|---|---|
(таблица из раздела 4.1 docs/requirements.md)
```

- [ ] **Step 3: Написать `docs/domain/context-map.md`**

Включить: (а) mermaid-диаграмму контейнеров/контекстов с направлениями зависимостей; (б) таблицу допустимых зависимостей (раздел 4.3 требований); (в) таблицу взаимодействий вида:

| Инициатор | Получатель | Взаимодействие | Тип | Синхронность | Лаба №2 | Лаба №4 |
|---|---|---|---|---|---|---|
| plants | media | `MediaAsset` метаданные по assetId | запрос через `media.api` | синхронно | Feign | Feign |
| moderation | plants | подписка на `PlantSubmitted` | событие | in-process | идемпотентная HTTP-команда + retry | Kafka `plant.moderation.v1` |
| moderation | plants | `PlantModeration.recordDecision` | команда | синхронно | HTTP-команда | Kafka |
| tournaments | plants | `PlantEligibility.reserveSubmission/confirmEligibility` | команда/запрос | синхронно | Feign + saga | Kafka-команды |
| tournaments | plants | `PlantLifecycle.registerDeath` | команда | синхронно, в транзакции закрытия окна | saga-команда | Kafka `plant.lifecycle.v1` |
| tournaments | identity | `CurrentActor`, публичный профиль | запрос | синхронно | Feign | Feign |
| tournaments | geo | `ClusteringGateway` (состав эпохи) | команда/запрос | синхронно | Feign/R2DBC | Feign |
| feed | tournaments/plants/media/identity | проекция по событиям | события | in-process | события внутри tournament-service | Kafka → проекция |

(полные строки для всех пар из раздела 4.3; правила устранения циклов — дословно из требований)

- [ ] **Step 4: Написать `docs/domain/aggregates.md`**

Для каждого агрегата из таблицы раздела 5 требований: корень, состав, инварианты (дословно), команды, доменные события, имя защищающего теста. На итерации 0 — таблица-каркас из требований; итерации 1–9 дополняют колонку «защищающий тест» реальными именами (например: `PlantTest#погибшее_растение_нельзя_подать_повторно`).

- [ ] **Step 5: Написать ADR-001…006**

- `ADR-001-plant-classifier-onnx.md`: ONNX Runtime 1.30.0 + MobileNetV2 (ImageNet-1000), модель в репо с зафиксированной версией; решение = сумма вероятностей plant-классов ImageNet ≥ порога (порог из конфигурации, значение по умолчанию зафиксировать при реализации итерации 4); ошибка модели = retry, не REJECTED; детерминированный адаптер только в test/demo.
- `ADR-002-feed-own-projection.md`: feed-проекция (схема `feed`), обновляемая опубликованными событиями; sort key в SQL от server-issued seed; keyset-курсор с HMAC; обоснование (не грузить таблицу в память, эволюция в outbox/Kafka в лабе №4).
- `ADR-003-flyway-per-context.md`: каталог + история на контекст (реализовано в итерации 0, `SchemaMigrationConfig`).
- `ADR-004-no-spring-modulith.md`: только ArchUnit; правила раздела 10.2 покрыты `ContextBoundaryTest`/`LayerRulesTest`.
- `ADR-005-demo-identification.md`: `X-Demo-User-Id` только в dev/test, роли всегда из БД; отсутствие заголовка = гость; обычный профиль без адаптера идентификации — ошибка конфигурации; замена адаптера на JWT в лабе №3 без изменения `AccessPolicy`.
- `ADR-006-product-assumptions.md`: 10 предлагаемых допущений раздела 3 требований (дословно), каждое с указанием имени защищающего теста (заполняется итерациями 1–8).

- [ ] **Step 6: Написать CI `.github/workflows/ci.yml`**

```yaml
name: ci

on:
  push:
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven
      - name: ./mvnw verify (тесты, ArchUnit, Testcontainers, JaCoCo gate)
        run: ./mvnw verify
```

- [ ] **Step 7: Commit**

```bash
git add docs/ README.md .github/
git commit -m "docs: глоссарий, context map, агрегаты, ADR-001..006, README, CI"
```

---

### Task 10: Финальная проверка итерации 0 и merge

**Files:**
- Modify: при необходимости — файлы предыдущих задач (только фиксы).

**Interfaces:**
- Produces: зелёный `./mvnw verify` на ветке `feat/iteration-0-skeleton`; merge в `main` (локально); чекпоинт-отчёт для review.

- [ ] **Step 1: Полный прогон**

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS`. В логе: Surefire (ContextBoundaryTest, LayerRulesTest, ClockConfigTest), Failsafe (ApplicationStartupIT, ApiErrorHandlingIT, SchemaMigrationIT, OpenApiDocsIT, HealthEndpointIT), `jacoco:check` — правило LINE ≥ 0.70 выполнено.

- [ ] **Step 2: Проверить отчёт покрытия**

```bash
ls target/site/jacoco-merged/index.html
```

Expected: файл существует; открыть при желании и зафиксировать фактический процент (на итерации 0 основной код мал — Application, ClockConfig, SchemaMigrationConfig, shared.web — и покрыт IT).

- [ ] **Step 3: Merge в main (локально, без push)**

```bash
git checkout main
git merge --no-ff feat/iteration-0-skeleton -m "merge: итерация 0 — скелет монолита"
./mvnw -q verify
```

Expected: `BUILD SUCCESS` на main.

- [ ] **Step 4: Чекпоинт-отчёт**

Сообщить пользователю: что готово (список задач), фактическое покрытие, результаты `docker compose up --build`, ссылка на план следующей итерации (identity). Дождаться review перед итерацией 1.
