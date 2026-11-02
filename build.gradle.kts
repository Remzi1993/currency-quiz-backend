
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    // Use the task types without the plugin's deprecated Configuration.setVisible setup.
    alias(libs.plugins.openapi.generator) apply false
}

group = "info.remzi.currencyquiz"
version = "1.0.0"

application {
    mainClass = "info.remzi.currencyquiz.MainKt"
}

kotlin {
    jvmToolchain(25)
}

val contractFile = layout.projectDirectory.file("shared/openapi/currency-quiz.v1.yaml")
check(contractFile.asFile.isFile) {
    "Missing shared API contract. Run git submodule update --init --recursive."
}

tasks.register<ValidateTask>("openApiValidate") {
    inputSpec.set(contractFile)
}

val generateContractModels = tasks.register<GenerateTask>("openApiGenerate") {
    generatorName.set("kotlin")
    inputSpec.set(contractFile)
    configFile.set(layout.projectDirectory.file("shared/generators/kotlin.json"))
    outputDir.set(layout.buildDirectory.dir("generated/openapi"))
    globalProperties.set(mapOf("models" to "", "modelDocs" to "false", "modelTests" to "false"))
    // UUIDs remain JSON strings at this boundary, as they do in TypeScript.
    typeMappings.set(mapOf("UUID" to "kotlin.String"))
    // Generate directly from the validated contract; avoid the upstream inline-array normalizer bug.
    openapiNormalizer.set(mapOf("DISABLE_ALL" to "true"))
    dependsOn("openApiValidate")
    inputs.file("shared/generators/kotlin.json")
    // Removed schemas must not leave stale models in the generated source set.
    doFirst { delete(layout.buildDirectory.dir("generated/openapi")) }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateContractModels.flatMap { it.outputDir.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") { dependsOn(generateContractModels) }
tasks.named("test") { dependsOn("openApiValidate") }
tasks.named("check") { dependsOn("openApiValidate") }

tasks.processResources {
    from(contractFile) { rename { "documentation.yaml" } }
}

dependencies {
    implementation(libs.dotenv.kotlin)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.requestValidation)
    implementation(ktorLibs.server.rateLimit)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.swagger)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.openapi.validator)
}
