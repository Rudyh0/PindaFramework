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
    // WorldEdit en WorldGuard (optioneel op de server: de 1v1-arena als WorldGuard-regio)
    maven("https://maven.enginehub.org/repo/") {
        name = "enginehub"
    }
    // PlaceholderAPI (optioneel op de server; alleen nodig om te compileren)
    maven("https://repo.extendedclip.com/releases/") {
        name = "placeholderapi"
        content { includeGroup("me.clip") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    // QR-codes voor de 2FA van het webpaneel (Paper downloadt deze bij het opstarten, zie plugin.yml)
    compileOnly("com.google.zxing:core:3.5.3")
    // %pinda_...% placeholders voor andere plugins, als PlaceholderAPI op de server staat
    compileOnly("me.clip:placeholderapi:2.11.6")
    // De 1v1-arena als WorldGuard-regio, en /setarena gebied met de WorldEdit-selectie
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.19")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.4")
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
