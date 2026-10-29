import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension

buildscript {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}
allprojects {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    extensions.getByType(CloudstreamExtension::class.java).setRepo("https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds/")
    extensions.getByType(BaseExtension::class.java).apply {
        namespace = "com.nguonc"
        compileSdkVersion(35)
        defaultConfig { minSdk = 21 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }
    dependencies {
        add("implementation", "com.github.recloudstream.cloudstream:library:-SNAPSHOT")
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}
