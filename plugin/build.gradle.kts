import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "io.locklane"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2.6.1")
        bundledPlugin("org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.JUnit5)
    }

    testImplementation(kotlin("test"))
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testRuntimeOnly("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "252"
        }
    }
}

val syncResolver by tasks.registering(Sync::class) {
    from("${project.rootDir}/../resolver/src/locklane_resolver") {
        include("**/*.py")
        exclude("__pycache__/**")
    }
    into(layout.buildDirectory.dir("generated-resources/bundled_resolver/locklane_resolver"))
}

val generateResolverManifest by tasks.registering {
    dependsOn(syncResolver)
    val outputDir = layout.buildDirectory.dir("generated-resources/bundled_resolver")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("locklane_resolver")
        val manifest = outputDir.get().asFile.resolve("manifest.txt")
        val files = dir.walkTopDown()
            .filter { it.isFile && it.extension == "py" }
            .map { it.relativeTo(dir).path }
            .sorted()
            .joinToString("\n")
        manifest.writeText(files)
    }
}

sourceSets.main {
    resources.srcDir(generateResolverManifest.map { layout.buildDirectory.dir("generated-resources") })
}

tasks {
    runIde {
        jvmArgs("-Xmx2g")
    }

    test {
        useJUnitPlatform {
            excludeTags("integration")
        }
    }

    val integrationTest by registering(Test::class) {
        group = "verification"
        description = "Runs integration tests requiring Python + resolver"
        notCompatibleWithConfigurationCache("references test task jvmArgumentProviders")
        val mainTest = test.get()
        testClassesDirs = mainTest.testClassesDirs
        classpath = mainTest.classpath
        jvmArgumentProviders.addAll(mainTest.jvmArgumentProviders)
        executable = mainTest.executable
        dependsOn("prepareTest")
        useJUnitPlatform {
            includeTags("integration")
        }
        systemProperty(
            "locklane.resolver.src",
            System.getProperty("locklane.resolver.src")
                ?: "${project.rootDir}/../resolver/src",
        )
    }
}
