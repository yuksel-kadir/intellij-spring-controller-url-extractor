plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.1.0"
  id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.springurlextractor"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
  intellijPlatform {
    create("IC", "2025.1")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

    // Add necessary plugin dependencies for Java PSI support
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "251"
      untilBuild = "252.*"
    }

    changeNotes = """
      <h3>Version 1.0</h3>
      <ul>
        <li>Initial release</li>
        <li>Extract complete URLs from Spring controller methods</li>
        <li>Automatic context path detection from application.yml and application.properties</li>
        <li>Support for all Spring mapping annotations</li>
        <li>Right-click context menu and keyboard shortcut support</li>
      </ul>
    """.trimIndent()
  }
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
  }
}