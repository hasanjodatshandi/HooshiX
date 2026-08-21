package com.sajtech.authorization.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.*;

@Tag("architecture")
class ArchitectureRulesTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.sajtech.authorization");

  @Test
  void applicationDoesNotDependOnAdaptersOrFrameworks() {
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.sajtech.authorization..infrastructure..",
            "com.sajtech.authorization..interfaces..",
            "com.sajtech.authorization..configuration..",
            "org.springframework..",
            "org.jooq..",
            "io.grpc..",
            "io.lettuce..")
        .check(classes);
  }

  @Test
  void interfacesDoNotDependOnInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("com.sajtech.authorization..interfaces..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.sajtech.authorization..infrastructure..")
        .check(classes);
  }

  @Test
  void infrastructureDoesNotDependOnInterfaces() {
    noClasses()
        .that()
        .resideInAPackage("com.sajtech.authorization..infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.sajtech.authorization..interfaces..")
        .check(classes);
  }
}
