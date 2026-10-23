package com.plantarena.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Правила слоёв контекста moderation внутри moderation-service (раздел 10.2, правила 4–9).
 */
@AnalyzeClasses(packages = "com.plantarena", importOptions = ImportOption.DoNotIncludeTests.class)
public class ModerationLayerRulesTest {

    private static final String CTX = "com.plantarena.moderation";

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
            .that().resideInAPackage(CTX + "..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "com.fasterxml..",
                "java.net.http..")
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
    static void onnx_доступен_только_в_adapter_out_classifier(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage(CTX + "..")
            .and().resideOutsideOfPackage(CTX + ".adapter.out.classifier..")
            .should().dependOnClassesThat().resideInAPackage("ai.onnxruntime..")
            .check(classes);
    }

    @ArchTest
    static void shared_не_зависит_от_контекста_moderation(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage("com.plantarena.shared..")
            .should().dependOnClassesThat().resideInAPackage(CTX + "..")
            .check(classes);
    }
}
