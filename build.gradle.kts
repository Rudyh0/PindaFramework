plugins {
    java
}

group = "nl.pinda"

// Versie: 0.1.0-b<buildnummer> op GitHub, 0.1.0-dev bij een lokale build.
val baseVersion = "0.1.0"
val buildNumber: String? = System.getenv("GITHUB_RUN_NUMBER")
version = if (buildNumber != null) "$baseVersion-b$buildNumber" else "$baseVersion-dev"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    // QR-codes voor de 2FA van het webpaneel (Paper downloadt deze bij het opstarten, zie plugin.yml)
    compileOnly("com.google.zxing:core:3.5.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("PindaFramework-${project.version}.jar")
}
