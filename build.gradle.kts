plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

allprojects {
    group = "com.douman.wire_ktor"
    version = System.getenv("PUBLISH_VERSION")
}