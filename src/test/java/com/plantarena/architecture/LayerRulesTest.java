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
