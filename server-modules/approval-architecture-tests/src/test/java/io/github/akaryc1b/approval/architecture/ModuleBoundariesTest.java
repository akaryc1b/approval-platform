package io.github.akaryc1b.approval.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.github.akaryc1b.approval")
class ModuleBoundariesTest {

    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_INDEPENDENT = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "org.flowable..",
            "cn.dev33..",
            "org.dromara.."
        );

    @ArchTest
    static final ArchRule APPLICATION_IS_ENGINE_AND_HOST_INDEPENDENT = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "org.flowable..",
            "cn.dev33..",
            "org.dromara.."
        );

    @ArchTest
    static final ArchRule ENGINE_SPI_DOES_NOT_DEPEND_ON_FLOWABLE = noClasses()
        .that().resideInAPackage("io.github.akaryc1b.approval.engine")
        .should().dependOnClassesThat().resideInAnyPackage("org.flowable..");

    @ArchTest
    static final ArchRule CONNECTOR_SPI_IS_FRAMEWORK_INDEPENDENT = noClasses()
        .that().resideInAPackage("io.github.akaryc1b.approval.connector..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "org.flowable..",
            "cn.dev33..",
            "org.dromara.."
        );

    @ArchTest
    static final ArchRule INTEGRATION_CORE_IS_FRAMEWORK_INDEPENDENT = noClasses()
        .that().resideInAPackage("io.github.akaryc1b.approval.integration..")
        .and().resideOutsideOfPackage("io.github.akaryc1b.approval.integration.jdbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "org.flowable..",
            "cn.dev33..",
            "org.dromara.."
        );

    @ArchTest
    static final ArchRule JDBC_INTEGRATION_DOES_NOT_DEPEND_ON_ENGINE_OR_HOST = noClasses()
        .that().resideInAPackage("io.github.akaryc1b.approval.integration.jdbc..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.flowable..",
            "cn.dev33..",
            "org.dromara.."
        );
}
