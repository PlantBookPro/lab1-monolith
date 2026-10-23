package com.plantarena.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Правила слоёв контекста feed внутри feed-service (Task 14, WebFlux + JdbcClient).
 * Домен — обычный блокирующий JDBC-контекст (без reactor): реактивность живёт
 * только в adapter.in.web (Mono/Blocking.call) и shared.webflux.
 */
@AnalyzeClasses(packages = "com.plantarena", importOptions = ImportOption.DoNotIncludeTests.class)
public class FeedLayerRulesTest {

    private static final String CTX = "com.plantarena.feed";

    @ArchTest
    static void слои_внутри_контекста_соблюдают_иерархию(JavaClasses classes) {
        ArchRule domainIsolated = noClasses()
            .that().resideInAPackage(CTX + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                CTX + ".application..", CTX + ".adapter..", CTX + ".api..");
        domainIsolated.check(classes);

        ArchRule applicationIsolated = noClasses()
            .that().resideInAPackage(CTX + ".application..")
            .should().dependOnClassesThat().resideInAPackage(CTX + ".adapter..");
        applicationIsolated.check(classes);

        ArchRule apiIsolated = noClasses()
            .that().resideInAPackage(CTX + ".api..")
            .should().dependOnClassesThat().resideInAnyPackage(CTX + ".domain..", CTX + ".adapter..");
        apiIsolated.check(classes);
    }

    @ArchTest
    static void домен_не_знает_об_инфраструктуре(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage(CTX + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "com.fasterxml..",
                "java.net.http..")
            .because("домен feed — обычные value objects/порты без reactor (WebFlux только в adapter)")
            .check(classes);
    }

    @ArchTest
    static void jpa_полностью_отсутствует(JavaClasses classes) {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                "jakarta.persistence..", "org.springframework.data..", "org.hibernate..")
            .check(classes);
    }

    @ArchTest
    static void jdbc_доступен_только_в_adapter_out_persistence(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage(CTX + "..")
            .and().resideOutsideOfPackage(CTX + ".adapter.out.persistence..")
            .should().dependOnClassesThat().areAssignableTo(JdbcClient.class)
            .orShould().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
            .check(classes);
    }

    @ArchTest
    static void контроллеры_не_обращаются_к_домену_и_репозиториям(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage(CTX + ".adapter.in.web..")
            .should().dependOnClassesThat().resideInAnyPackage(
                CTX + ".domain..", CTX + ".adapter.out.persistence..")
            .check(classes);

        noClasses()
            .that().resideInAPackage(CTX + ".adapter.in.web..")
            .should().dependOnClassesThat().areAssignableTo(JdbcClient.class)
            .orShould().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
            .check(classes);
    }

    @ArchTest
    static void shared_не_зависит_от_контекста_feed(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage("com.plantarena.shared..")
            .should().dependOnClassesThat().resideInAPackage(CTX + "..")
            .check(classes);
    }
}
