package com.sajtech.webbff.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.*;

@Tag("architecture")
class ArchitectureRulesTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.sajtech.webbff");

  @Test
  void applicationDoesNotDependOnAdaptersOrFrameworks() {
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.sajtech.webbff..infrastructure..",
            "com.sajtech.webbff..interfaces..",
            "com.sajtech.webbff..configuration..",
            "org.springframework..",
            "io.grpc..",
            "io.lettuce..",
            "jakarta.servlet..")
        .check(classes);
  }

  @Test
  void interfacesDependOnlyInward() {
    noClasses()
        .that()
        .resideInAPackage("com.sajtech.webbff..interfaces..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.sajtech.webbff..infrastructure..")
        .check(classes);
  }

  @Test
  void infrastructureDoesNotDependOnInterfaces() {
    noClasses()
        .that()
        .resideInAPackage("com.sajtech.webbff..infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.sajtech.webbff..interfaces..")
        .check(classes);
  }
}
