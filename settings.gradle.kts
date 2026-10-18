pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral(); google() }
}
rootProject.name = "MetricMe"
include(":domain", ":contracts", ":backend")
if (!providers.gradleProperty("backendOnly").isPresent) include(":app")
