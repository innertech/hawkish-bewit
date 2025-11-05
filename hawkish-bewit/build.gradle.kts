plugins {
  kotlin("multiplatform")
  id("com.android.library")
  id("org.jetbrains.dokka")
  id("com.adarshr.test-logger") version "4.0.0"
  id("signing")
  id("maven-publish")
}

group = LibraryConstants.group
version = LibraryConstants.versionName

val signingRequired: String by project

repositories {
  mavenCentral()
  maven("https://repo.repsy.io/mvn/chrynan/public")
}

kotlin {
  jvmToolchain(21)

  androidTarget {
    publishLibraryVariants("release")
  }

  jvm {
    compilations.all {
      compileTaskProvider.configure {
        compilerOptions {
          jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
      }
    }
    testRuns["test"].executionTask.configure {
      useJUnitPlatform()
    }
  }

  js {
    browser {
      testTask {
        // failing tests on JS for now
        enabled = false
        useKarma {
          useChromeHeadless()
        }
      }
    }
//      nodejs()
  }
  iosX64()
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    all {
      languageSettings {
        languageVersion = "2.2"
        apiVersion = "2.1"
        progressiveMode = true
//        optIn("kotlin.contracts.ExperimentalContracts")
        optIn("kotlin.time.ExperimentalTime")
      }
    }
    val commonMain by getting {
      dependencies {
        api("com.chrynan.uri:uri-core:0.4.0")
        api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

        implementation(kotlin("stdlib-jdk8"))

        implementation("com.squareup.okio:okio:3.16.2")
      }
    }

    val jsMain by getting {
      dependencies {
        implementation("com.squareup.okio:okio-nodefilesystem:3.16.2")
      }
    }

    val jsTest by getting {
      dependencies {
        // tests on JS, workaround webpack > 5 not including node polyfills by default
        //  https://github.com/square/okio/issues/1163
        implementation(devNpm("node-polyfill-webpack-plugin", "^2.0.1"))
      }
    }

    val jvmTest by getting {
      dependencies {
        api("org.junit.jupiter:junit-jupiter-api:5.8.2")
        implementation("com.mercateo:test-clock:1.0.4")
        implementation("io.strikt:strikt-core:0.35.1")
        runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation("com.squareup.okio:okio-fakefilesystem:3.16.2")
      }
    }
  }
}

android {
  namespace = "tech.inner.hawk.bewit"
  compileSdk = 36

  defaultConfig {
    minSdk = 23
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    getByName("release") {
      proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  publishing {
    multipleVariants {
      allVariants()
      withSourcesJar()
      withJavadocJar()
    }
  }

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
  }

  sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
  sourceSets["main"].java.srcDirs("src/androidMain/kotlin")
  sourceSets["main"].res.srcDirs("src/androidMain/res")

  sourceSets["test"].java.srcDirs("src/androidTest/kotlin")
  sourceSets["test"].res.srcDirs("src/androidTest/res")
}

tasks.withType<Jar> { duplicatesStrategy = DuplicatesStrategy.INHERIT }

//val dokkaHtml by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
//  dependsOn(dokkaHtml)
  archiveClassifier.set("javadoc")
//  from(dokkaHtml.outputDirectory)
}

tasks.withType<PublishToMavenRepository>().configureEach {
  if (signingRequired.toBoolean()) {
    mustRunAfter(tasks.withType<Sign>())
  }
}

publishing {
  publications.withType<MavenPublication> {
    artifact(javadocJar.get())
    pom {
      name.set(project.name)
      description.set("Signed URLs using Hawk Bewits")
      url.set("https://github.com/innertech/hawkish-bewit")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
        }
      }
      developers {
        developer {
          id.set("rocketraman")
          name.set("Raman Gupta")
          email.set("rocketraman@gmail.com")
        }
      }
      scm {
        connection.set("scm:git:git@github.com:innertech/hawkish-bewit.git")
        developerConnection.set("scm:git:ssh://github.com:innertech/hawkish-bewit.git")
        url.set("https://github.com/innertech/hawkish-bewit")
      }
    }
  }
  repositories {
    maven {
      // https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#getting-started-for-maven-api-like-plugins
      name = "sonatype"
      url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2")
      credentials {
        username = project.findProperty("sonatypeUser") as? String
        password = project.findProperty("sonatypePassword") as? String
      }
    }
    maven {
      name = "GitHub"
      url = uri("https://maven.pkg.github.com/innertech/hawkish-bewit")
      credentials {
        username = project.findProperty("githubRepoUser") as? String
        password = project.findProperty("githubRepoToken") as? String
      }
    }
  }
}

if (signingRequired.toBoolean()) {
  signing {
    useGpgCmd()
    sign(publishing.publications)
  }
}
