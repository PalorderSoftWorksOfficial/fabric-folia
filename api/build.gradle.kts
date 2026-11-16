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

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.palordersoftworks.fabricfolia.api")
    }
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
            pom {
                name.set("Fabric-Folia API")
                description.set("Compile-time API for region-aware Fabric mods running on Fabric-Folia.")
            }
        }
    }
}
