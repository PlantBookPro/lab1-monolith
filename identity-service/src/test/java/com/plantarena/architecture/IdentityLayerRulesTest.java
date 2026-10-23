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
 * Правила слоёв контекста identity внутри identity-service (раздел 10.2, правила 4–9).
 */
@AnalyzeClasses(packages = "com.plantarena", importOptions = ImportOption.DoNotIncludeTests.class)
public class IdentityLayerRulesTest {

    private static final String CTX = "com.plantarena.identity";

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
    static void контроллеры_не_обращаются_к_домену_и_репозиториям(JavaClasses classes) {
        // Раздел 13: доменные исключения (Throwable) переводятся в ProblemDetail-подобное
        // тело advice-классами adapter.in.web — это не доступ к доменной логике.
        // Запрещено всё из domain, кроме исключений, и весь adapter.out.persistence.
        DescribedPredicate<JavaClass> domainExceptExceptions =
            JavaClass.Predicates.resideInAnyPackage(CTX + "..domain..")
                .and(new DescribedPredicate<JavaClass>("не являются исключениями") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return !javaClass.isAssignableTo(Throwable.class);
                    }
                });
        DescribedPredicate<JavaClass> forbidden =
            domainExceptExceptions.or(JavaClass.Predicates.resideInAnyPackage(
                CTX + ".adapter.out.persistence.."));
        noClasses()
            .that().resideInAPackage(CTX + ".adapter.in.web..")
            .should().dependOnClassesThat(forbidden)
            .check(classes);

        noClasses()
            .that().resideInAPackage(CTX + ".adapter.in.web..")
            .should().dependOnClassesThat().areAssignableTo(JdbcClient.class)
            .orShould().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
            .check(classes);
    }

    @ArchTest
    static void shared_не_зависит_от_контекста_identity(JavaClasses classes) {
        noClasses()
            .that().resideInAPackage("com.plantarena.shared..")
            .should().dependOnClassesThat().resideInAPackage(CTX + "..")
            .check(classes);
    }
}
