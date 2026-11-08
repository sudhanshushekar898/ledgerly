package dev.ledgerly;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Module and layering rules from docs/ARCHITECTURE.md and ADR-001. Never weaken these to make code compile. */
class ArchitectureTest {

    private static final List<String> MODULES = List.of("identity", "ledger", "wallet", "outbox", "notification");
    private static final String[] INTERNAL_LAYERS = {"api", "service", "domain", "repository", "config", "infra"};

    private static JavaClasses main;

    @BeforeAll
    static void importClasses() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.ledgerly");
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching("dev.ledgerly.(*)..").should().beFreeOfCycles()
                .allowEmptyShould(true).check(main);
    }

    @Test
    void sharedKernelDependsOnNoModule() {
        classes().that().resideInAPackage("dev.ledgerly.shared..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        MODULES.stream().map(m -> "dev.ledgerly." + m + "..").toArray(String[]::new))
                .allowEmptyShould(true).check(main);
    }

    @Test
    void controllersDoNotUseRepositories() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().onlyDependOnClassesThat().haveSimpleNameNotEndingWith("Repository")
                .allowEmptyShould(true).check(main);
    }

    /** Other modules may only use a module's root-package facade, never its internal layers. */
    @Test
    void modulesExposeOnlyTheirRootPackage() {
        for (String module : MODULES) {
            noClasses().that().resideOutsideOfPackage("dev.ledgerly." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            Arrays.stream(INTERNAL_LAYERS).map(l -> "dev.ledgerly." + module + "." + l + "..")
                                    .toArray(String[]::new))
                    .because(module + " must be used through its public facade")
                    .allowEmptyShould(true).check(main);
        }
    }

    /** ADR-002: only the ledger module may write balances, so nothing else may touch its repositories. */
    @Test
    void onlyLedgerTouchesLedgerRepositories() {
        noClasses().that().resideOutsideOfPackage("dev.ledgerly.ledger..")
                .should().dependOnClassesThat().resideInAPackage("dev.ledgerly.ledger.repository..")
                .allowEmptyShould(true).check(main);
    }

    @Test
    void noFieldInjection() {
        fields().should().notBeAnnotatedWith(Autowired.class)
                .because("constructor injection only")
                .allowEmptyShould(true).check(main);
    }

    @Test
    void moneyIsNeverFloatingPoint() {
        fields().that().haveNameMatching("(?i).*(amount|balance|limit).*")
                .should().notHaveRawType(double.class).andShould().notHaveRawType(float.class)
                .andShould().notHaveRawType(Double.class).andShould().notHaveRawType(Float.class)
                .because("money is BigDecimal (ADR-002)")
                .allowEmptyShould(true).check(main);
    }
}
