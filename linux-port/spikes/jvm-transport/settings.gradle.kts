pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "relay-jvm-transport-spike"
include(":jvm")
if (providers.gradleProperty("androidProbe").isPresent) include(":android")
