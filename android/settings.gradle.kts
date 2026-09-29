pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()     // Razorpay's SDK is here
    }
}
rootProject.name = "CampusPrint"
include(":app")
include(":verifier")   // CampusPay Verifier: the Xerox center's phone passes on "money received" messages
