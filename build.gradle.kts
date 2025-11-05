group = LibraryConstants.group
version = LibraryConstants.versionName

buildscript {
  repositories {
    google()
    mavenCentral()
    maven("https://repo.repsy.io/mvn/chrynan/public")
  }
  dependencies {
    classpath("com.android.tools.build:gradle:8.12.3")
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
  }
}

plugins {
  id("org.jetbrains.dokka") version "2.1.0"
}

allprojects {
  repositories {
    google()
    mavenCentral()
    maven("https://repo.repsy.io/mvn/chrynan/public")
  }
}
