plugins {
    application
    java
}

repositories { mavenCentral() }

val lucene = "9.11.1"

dependencies {
    implementation("org.apache.lucene:lucene-core:$lucene")
    implementation("org.apache.lucene:lucene-analysis-common:$lucene")
    implementation("org.apache.lucene:lucene-queryparser:$lucene")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // Decision carries an Instant; without this, findAndRegisterModules() finds
    // nothing and every publish fails at serialisation time.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("io.javalin:javalin:6.3.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // Steps 7-10. Declared now only to warm the dependency cache so nothing
    // downloads on hackathon wifi.
    implementation(platform("software.amazon.awssdk:bom:2.28.16"))
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:sns")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20240826-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

application {
    mainClass = "labs.augmentor.auditor.App"
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
    // The tests exercise the worker end to end, run log and all. Without this the
    // demo timeline fills with entries from fixtures that are indistinguishable
    // from a real run going wrong.
    systemProperty("auditor.runlog", "off")
}
