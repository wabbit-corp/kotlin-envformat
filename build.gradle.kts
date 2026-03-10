import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

repositories {
    google()

    mavenCentral()

    maven("https://jitpack.io")
}

group = "one.wabbit"
version = "0.0.1"

plugins {
    id("com.android.kotlin.multiplatform.library")

    kotlin("multiplatform")

    kotlin("plugin.serialization")

    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("maven-publish")

    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    coordinates("one.wabbit", "kotlin-envformat", "0.0.1")
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("kotlin-envformat")
        description.set("kotlin-envformat")
        url.set("https://github.com/wabbit-corp/kotlin-envformat")
        licenses {
            license {
                name.set("GNU Affero General Public License v3.0 or later")
                url.set("https://spdx.org/licenses/AGPL-3.0-or-later.html")
            }
        }
        developers {
            developer {
                id.set("wabbit-corp")
                name.set("Wabbit Consulting Corporation")

                email.set("wabbit@wabbit.one")

            }
        }
        scm {
            url.set("https://github.com/wabbit-corp/kotlin-envformat")
            connection.set("scm:git:git://github.com/wabbit-corp/kotlin-envformat.git")
            developerConnection.set("scm:git:ssh://git@github.com/wabbit-corp/kotlin-envformat.git")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")

    }
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            jvmArgs("-ea")
        }
    }

    androidLibrary {
        namespace = "one.wabbit.envformat"
        compileSdk = 34
        minSdk = 26
    }

    iosArm64()

    iosSimulatorArm64()

    macosArm64("hostNative")

    targets.withType(KotlinNativeTarget::class.java).configureEach {
        binaries.framework {
            baseName = "Envformat"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

            }

        }

        val commonTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test:2.3.10")

            }

        }

    }
}

tasks.register("printVersion") {
    doLast {
        println(project.version.toString())
    }
}

tasks.register("assertReleaseVersion") {
    doLast {
        val versionString = project.version.toString()
        require(!versionString.endsWith("+dev-SNAPSHOT")) {
            "Release publishing requires a non-snapshot version, got $versionString"
        }
        val refType = System.getenv("GITHUB_REF_TYPE") ?: ""
        val refName = System.getenv("GITHUB_REF_NAME") ?: ""
        if (refType == "tag" && refName.isNotBlank()) {
            val expectedTag = "v$versionString"
            require(refName == expectedTag) {
                "Git tag $refName does not match project version $versionString"
            }
        }
    }
}

tasks.register("assertSnapshotVersion") {
    doLast {
        val versionString = project.version.toString()
        require(versionString.endsWith("+dev-SNAPSHOT")) {
            "Snapshot publishing requires a +dev-SNAPSHOT version, got $versionString"
        }
        require((System.getenv("GITHUB_REF_TYPE") ?: "") != "tag") {
            "Snapshot publishing must not run from a tag ref"
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("-ea")
}

dokka {
    moduleName.set("kotlin-envformat")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }

    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/wabbit-corp/kotlin-envformat/tree/master/src")
            remoteLineSuffix.set("#L")
        }

    }

    pluginsConfiguration.html {
        footerMessage.set("(c) Wabbit Consulting Corporation")
    }
}
