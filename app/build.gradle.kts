import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    id("com.google.gms.google-services")
}

// Attach Mockito explicitly for local JVM tests on Java 21 and newer.
val mockitoAgent = configurations.create("mockitoAgent")

/** Resolves the test agent at execution time and tracks it as a classpath input. */
class MockitoAgentArguments(@get:Classpath val classpath: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-javaagent:${classpath.singleFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(MockitoAgentArguments(mockitoAgent))
}

android {
    namespace = "com.example.legion_events"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.legion_events"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dokka {
    dokkaPublications.html {
        moduleName.set("Legion Events")
        // Local HTML output; handwritten documentation stays in doc/.
        outputDirectory.set(rootProject.layout.projectDirectory.dir("doc/api"))
        suppressInheritedMembers.set(true)
    }
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(
            setOf(VisibilityModifier.Public, VisibilityModifier.Protected, VisibilityModifier.Internal, VisibilityModifier.Private),
        )
        reportUndocumented.set(true)
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    mockitoAgent(libs.mockito.core) { isTransitive = false }
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
