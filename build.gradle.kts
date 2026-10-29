
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
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

openApiValidate {
    inputSpec.set(contractFile.asFile.absolutePath)
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(contractFile.asFile.absolutePath)
    configFile.set(layout.projectDirectory.file("shared/generators/kotlin.json").asFile.absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    globalProperties.set(mapOf("models" to "", "modelDocs" to "false", "modelTests" to "false"))
    // UUIDs remain JSON strings at this boundary, as they do in TypeScript.
    typeMappings.set(mapOf("UUID" to "kotlin.String"))
}

val generateContractModels = tasks.named<GenerateTask>("openApiGenerate") {
    inputs.file("shared/generators/kotlin.json")
    // Removed schemas must not leave stale models in the generated source set.
    doFirst { delete(layout.buildDirectory.dir("generated/openapi")) }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateContractModels.map { "${it.outputDir.get()}/src/main/kotlin" })
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
    implementation(ktorLibs.server.cors)
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
