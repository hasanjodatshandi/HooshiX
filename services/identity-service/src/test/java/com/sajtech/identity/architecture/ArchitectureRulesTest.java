package com.sajtech.identity.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.*;

@Tag("architecture")
class ArchitectureRulesTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.sajtech.identity");

  @Test
  void domainDependsOnlyOnJavaAndDomain() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("java..", "com.sajtech.identity..domain..")
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
            "com.sajtech.identity..infrastructure..",
            "com.sajtech.identity..interfaces..",
            "com.sajtech.identity..configuration..",
            "org.springframework..",
            "org.jooq..",
            "io.grpc..",
            "io.lettuce..",
            "org.bouncycastle..")
        .check(classes);
  }

  @Test
  void interfacesDependOnlyInward() {
    noClasses()
        .that()
        .resideInAPackage("com.sajtech.identity..interfaces..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.sajtech.identity..infrastructure..", "com.sajtech.identity..configuration..")
        .check(classes);
  }

  @Test
  void infrastructureDoesNotDependOnInterfaces() {
    noClasses()
        .that()
        .resideInAPackage("com.sajtech.identity..infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.sajtech.identity..interfaces..")
        .check(classes);
  }
}
