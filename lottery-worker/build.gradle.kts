plugins {
    kotlin("jvm") version "2.4.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.firebase:firebase-admin:9.11.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("com.example.legion_events.worker.MainKt")
}

tasks.startScripts {
    doLast {
        // Let Java expand the installed jars within Windows cmd's command length limit.
        val script = windowsScript.readText()
        windowsScript.writeText(script.replace(Regex("(?m)^set CLASSPATH=[^\\r\\n]*")) {
            "set CLASSPATH=%APP_HOME%\\lib\\*"
        })
    }
}

tasks.test {
    inputs.property("firestoreEmulatorHost", providers.environmentVariable("FIRESTORE_EMULATOR_HOST").orElse(""))
}
