package com.plantarena.geo.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Правила слоёв контекста geo внутри geo-service. Исключение против
 * классического правила «домен не знает об инфраструктуре»: домену разрешён
 * reactor.core — реактивный порт ClusterSnapshotRepository возвращает
 * Mono/Flux, и это осознанный компромисс полностью реактивного сервиса
 * (Task 10, WebFlux + R2DBC).
 */
@AnalyzeClasses(packages = "com.plantarena", importOptions = ImportOption.DoNotIncludeTests.class)
public class GeoLayerRulesTest {

    private static final String CTX = "com.plantarena.geo";

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
    static void домен_не_знает_об_инфраструктуре_кроме_reactor(JavaClasses classes) {
        // Исключение: reactor.core разрешён домену — реактивный порт
        // ClusterSnapshotRepository (Mono/Flux) живёт в пакете domain.
        noClasses()
            .that().resideInAPackage(CTX + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "com.fasterxml..",
                "java.net.http..")
            .because("домен изолирован; reactor.core — задокументированное исключение")
            .check(classes);
    }

    @ArchTest
    static void jdbc_и_jpa_полностью_отсутствуют(JavaClasses classes) {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                "jakarta.persistence..", "org.hibernate..", "org.springframework.jdbc..")
            .check(classes);
    }

    @ArchTest
    static void r2dbc_доступен_только_в_adapter_out_persistence(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage(CTX + "..")
            .and().resideOutsideOfPackage(CTX + ".adapter.out.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.r2dbc..", "org.springframework.data..")
            .check(classes);
    }

    @ArchTest
    static void контроллеры_не_обращаются_к_репозиториям(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage(CTX + ".adapter.in.web..")
            .should().dependOnClassesThat().resideInAPackage(CTX + ".adapter.out.persistence..")
            .check(classes);
    }

    @ArchTest
    static void shared_не_зависит_от_контекста_geo(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage("com.plantarena.shared..")
            .should().dependOnClassesThat().resideInAPackage(CTX + "..")
            .check(classes);
    }
}
