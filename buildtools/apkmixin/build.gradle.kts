plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "2.1.10" apply true
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}
repositories {
    mavenCentral()
    google()
    mavenLocal()
}
dependencies {
    compileOnly(gradleApi())
    implementation(kotlin("stdlib"))
    // noinspection UseTomlInstead
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    // noinspection UseTomlInstead
    implementation("com.android.tools.smali:smali:3.0.9")
    // noinspection UseTomlInstead
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    // noinspection UseTomlInstead
    implementation("com.huanli233:multidexlib2:3.0.9.r4")
    implementation(fileTree("./libs") {
        include("*.jar")
    })
}
gradlePlugin {
    plugins {
        create("ApkMixin") {
            id = "momoi.plugin.apkmixin"
            implementationClass = "momoi.plugin.apkmixin.MixinPlugin"
        }
    }
}