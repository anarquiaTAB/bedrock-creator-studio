plugins {
    // VERIFIQUE a versão estável mais recente do Kotlin antes de compilar.
    kotlin("jvm") version "2.0.21"
}

kotlin {
    // O núcleo usa apenas java.nio / java.util.zip, disponíveis no Android 8.0 (API 26)+.
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
