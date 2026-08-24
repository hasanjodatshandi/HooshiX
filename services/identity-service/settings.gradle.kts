pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal(); mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenLocal(); mavenCentral()
    }
}

rootProject.name = "identity-service"
