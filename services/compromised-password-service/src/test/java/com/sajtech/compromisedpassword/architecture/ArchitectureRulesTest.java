package com.sajtech.compromisedpassword.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ArchitectureRulesTest {
    private final JavaClasses classes =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.sajtech.compromisedpassword");

    @Test
    void domainDependsOnlyOnJavaAndDomain() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("java..", "..domain..")
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnAdaptersOrFrameworks() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..infrastructure..",
                        "..interfaces..",
                        "..configuration..",
                        "org.springframework..",
                        "org.sqlite..",
                        "io.grpc..",
                        "io.opentelemetry..")
                .check(classes);
    }
}
