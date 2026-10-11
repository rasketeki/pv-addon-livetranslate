plugins {
    id("net.fabricmc.fabric-loom") version "1.17.13"
    kotlin("jvm") version "2.4.0"
}

val minecraftVersion: String by project
val loaderVersion: String by project
val fabricVersion: String by project
val fabricKotlinVersion: String by project
val plasmovoiceVersion: String by project
val modVersion: String by project
val mavenGroup: String by project
val archivesBaseName: String by project

version = modVersion
group = mavenGroup
base { archivesName.set(archivesBaseName) }

repositories {
    mavenCentral()
    maven("https://repo.plasmoverse.com/releases")
    maven("https://repo.plasmoverse.com/snapshots")
}

dependencies {
    // Since 26.1 Minecraft ships unobfuscated — no mappings needed.
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // Plasmo Voice client API — compile-time only.
    // Players just need the normal Plasmo Voice mod installed at runtime.
    compileOnly("su.plo.voice.api:client:$plasmovoiceVersion")

    // In-process speech-to-text: whisper.cpp via JNI. The native library
    // (Windows/Linux/macOS) ships inside this jar, so there is no external
    // server and no bundled .exe. `include(...)` nests it in the mod jar
    // (Fabric jar-in-jar) so it's on the runtime classpath. ModelStore downloads
    // and verifies the weights automatically on first use.
    val whisperJni = "io.github.givimad:whisper-jni:1.7.1"
    implementation(whisperJni)
    include(whisperJni)

    // Embedded translation runtime, including its native libraries.
    val llama = "net.ladenthin:llama:5.1.0"
    implementation(llama) { exclude(group = "ch.qos.logback") }
    include(llama)
    // Fabric does not expose ordinary transitive Maven dependencies at runtime.
    include("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    include("com.fasterxml.jackson.core:jackson-core:2.22.2")
    include("com.fasterxml.jackson.core:jackson-annotations:2.22")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// Optional real-model smoke check; never downloads models as part of normal tests.
tasks.register<JavaExec>("translationSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.livetranslate.TranslationSmoke")
    args(layout.buildDirectory.dir("smoke-models").get().asFile.absolutePath)
}

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

kotlin {
    jvmToolchain(25)
}
