plugins {
    id("java-library")
    id("maven-publish")
}

base {
    archivesName.set("fabric-folia-api")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    compileOnly(libs.jsr305)
    testImplementation(libs.junitJupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "fabric-folia-api"
        }
    }
}
