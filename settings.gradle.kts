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
        mavenCentral()
        maven {
           name = "GitHubPackages"
           setUrl("https://maven.pkg.github.com/FairyDevicesRD/thinklet.app.sdk")
           credentials {
               val properties = java.util.Properties()
               properties.load(file("github.properties").inputStream())
               username = properties.getProperty("username") ?: ""
               password = properties.getProperty("token") ?: ""
           }
       }
    }
    versionCatalogs {
        create("thinkletLibs") {
            from(files("gradle/thinklet.versions.toml"))
        }
    }
}

rootProject.name = "MealLogger"
include(":app")
include(":thinklet-xfe")
