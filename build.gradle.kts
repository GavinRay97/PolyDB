plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.allopen") version "2.2.0"
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    // MUST enforce Kotlin version before Quarkus BOM to override Quarkus default
    implementation(enforcedPlatform("org.jetbrains.kotlin:kotlin-bom:2.2.0"))
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))

    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-cache")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-jdbc-mariadb")
    implementation("io.quarkus:quarkus-jdbc-mysql")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-smallrye-openapi")

    implementation("io.quarkiverse.mcp:quarkus-mcp-server-sse:1.3.1")
    implementation("com.github.victools:jsonschema-module-jackson:4.38.0")

    // Add "libs/documentdb-jdbc-1.4.5-all.jar" for MongoDB/DocumentDB support
    implementation(files("libs/documentdb-jdbc-1.4.5-all.jar"))

    implementation("org.apache.calcite:calcite-core:1.40.0")
    implementation("org.apache.calcite:calcite-server:1.40.0")

    implementation("org.jooq:jooq:3.20.5")
    implementation("com.zaxxer:HikariCP:6.3.0")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Caffeine cache Kotlin extension method library
    implementation("com.sksamuel.aedile:aedile-core:2.1.2")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.quarkus:quarkus-jdbc-h2")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("org.hsqldb:hsqldb:2.7.4")
}

group = "io.github.gavinray97"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.quarkusDev.configure {
    jvmArgs =
        listOf(
            "-Djava.net.preferIPv4Stack=true",
            "-Djava.net.preferIPv4Addresses=true",
        )
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")

    // WSL2/Windows networking configuration - force IPv4
    systemProperty("java.net.preferIPv4Stack", "true")
    systemProperty("java.net.preferIPv4Addresses", "true")

    // Show test output in console
    testLogging {
        events("passed", "skipped", "failed", "standard_out", "standard_error")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {

    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
        javaParameters = true

        freeCompilerArgs.addAll(
            listOf(
                "-Xcontext-parameters",
                "-Xcontext-sensitive-resolution",
                "-Xnested-type-aliases",
                "-Wextra",
            ),
        )
    }
}

tasks.quarkusDev {
    compilerOptions {
        compiler("kotlin").args(
            listOf(
                "-Xcontext-parameters",
                "-Xcontext-sensitive-resolution",
                "-Xnested-type-aliases",
                "-Wextra",
            ),
        )
    }
}
