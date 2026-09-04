pluginManagement {
    repositories {
        if (System.getenv("MOBILE_AGENT_USE_HUAWEI_MIRROR") == "1") {
            maven("https://repo.huaweicloud.com/repository/gradle-plugin/")
            maven("https://repo.huaweicloud.com/repository/maven/")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("MOBILE_AGENT_USE_HUAWEI_MIRROR") == "1") {
            maven("https://repo.huaweicloud.com/repository/maven/")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileAgent"
include(":app")
