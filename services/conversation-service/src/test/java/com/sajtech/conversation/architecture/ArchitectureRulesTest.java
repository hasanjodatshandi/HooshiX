package com.sajtech.conversation.architecture;

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
          .importPackages("com.sajtech.conversation");

  @Test
  void applicationAndDomainRemainFrameworkIndependent() {
    noClasses()
        .that()
        .resideInAnyPackage("..application..", "..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..infrastructure..", "..interfaces..", "..configuration..", "org.springframework..")
        .allowEmptyShould(true)
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
        .allowEmptyShould(true)
        .check(classes);
  }
}
