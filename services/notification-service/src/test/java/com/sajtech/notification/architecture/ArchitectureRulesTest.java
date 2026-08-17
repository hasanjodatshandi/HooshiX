package com.sajtech.notification.architecture;

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
          .importPackages("com.sajtech.notification");

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
            "org.jooq..",
            "io.grpc..",
            "io.opentelemetry..")
        .check(classes);
  }

  @Test
  void interfacesDependOnlyInward() {
    noClasses()
        .that()
        .resideInAPackage("..interfaces..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infrastructure..", "..configuration..")
        .check(classes);
  }

  @Test
  void infrastructureDoesNotDependOnInterfaces() {
    noClasses()
        .that()
        .resideInAPackage("..infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..interfaces..")
        .check(classes);
  }
}
