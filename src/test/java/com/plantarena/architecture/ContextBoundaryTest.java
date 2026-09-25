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
    static void только_config_и_acl_адаптеры_знают_несколько_контекстов(JavaClasses classes) {
        classes.stream()
            .filter(javaClass -> javaClass.getPackageName().startsWith("com.plantarena"))
            .filter(javaClass -> !javaClass.getPackageName().startsWith("com.plantarena.config"))
            .forEach(javaClass -> {
                Set<String> dependedContexts = javaClass.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass().getPackageName())
                    .map(ContextBoundaryTest::contextOf)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
                if (isAclAdapter(javaClass.getPackageName())) {
                    // ACL-адаптер (раздел 4.3, Customer–Supplier): свой контекст
                    // + upstream из ALLOWED_UPSTREAM, чужой контекст — только api
                    // (правило адаптеров выше). Больше никому несколько контекстов.
                    String own = ownContextOf(javaClass.getPackageName());
                    assertThat(dependedContexts)
                        .as("ACL-адаптер %s выходит за свой контекст и допустимый upstream",
                            javaClass.getName())
                        .isSubsetOf(allowedFor(own));
                } else {
                    assertThat(dependedContexts)
                        .as("класс %s зависит от нескольких контекстов одновременно",
                            javaClass.getName())
                        .hasSizeLessThanOrEqualTo(1);
                }
            });
    }

    /** adapter.out — выходные адаптеры: ACL к чужим контекстам живут здесь. */
    private static boolean isAclAdapter(String packageName) {
        return ownContextOf(packageName) != null
            && packageName.matches("com\\.plantarena\\.(\\w+)\\.adapter\\.out\\..*");
    }

    private static String ownContextOf(String packageName) {
        return CONTEXTS.stream()
            .filter(context -> packageName.startsWith("com.plantarena." + context + "."))
            .findFirst()
            .orElse(null);
    }

    private static Set<String> allowedFor(String ownContext) {
        Set<String> allowed = new java.util.HashSet<>(ALLOWED_UPSTREAM.get(ownContext));
        allowed.add(ownContext);
        return allowed;
    }

    private static String contextOf(String packageName) {
        return CONTEXTS.stream()
            .filter(context -> packageName.equals("com.plantarena." + context)
                || packageName.startsWith("com.plantarena." + context + "."))
            .findFirst()
            .orElse(null);
    }
}
