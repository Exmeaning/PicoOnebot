pluginManagement {
    // ApkMixin:编译期 dex 注入插件(源自 celeryia/QQPro,GPL-3.0,已 vendored 到 buildtools/)
    includeBuild("buildtools/apkmixin")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PicoOnebot"
include(":app")
include(":apkmixin-annotation")
project(":apkmixin-annotation").projectDir = file("buildtools/apkmixin-annotation")
include(":gen-dep")
project(":gen-dep").projectDir = file("buildtools/gen-dep")
