plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(libs.junit)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}
