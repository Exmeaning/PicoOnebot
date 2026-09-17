plugins {
    application
    alias(libs.plugins.jetbrains.kotlin.jvm)
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
dependencies {
    implementation("org.ow2.asm:asm:9.8")
}
application {
    mainClass.set("mo.moi.MainKt")
}
// Main.kt 里用的是相对路径 ./buildtools/gen-dep/raw.jar → ./app/libs/source.jar,必须从仓库根运行
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
