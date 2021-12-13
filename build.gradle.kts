plugins {
    kotlin("jvm") version "1.5.31"
    kotlin("plugin.serialization") version "1.5.31"
    `maven-publish`
}

// name = "TradingViewScraper"
group = "net.perfectdreams.tradingviewscraper"
version = "0.0.7-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.ktor:ktor-client-core:1.6.6")
    implementation("io.ktor:ktor-client-websockets:1.6.6")
    implementation("io.ktor:ktor-client-cio:1.6.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.1")
    implementation("io.github.microutils:kotlin-logging:2.1.16")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}

publishing {
    repositories {
        maven {
            name = "PerfectDreams"
            url = uri("https://repo.perfectdreams.net/")

            credentials {
                username = System.getProperty("USERNAME") ?: System.getenv("USERNAME")
                password = System.getProperty("PASSWORD") ?: System.getenv("PASSWORD")
            }
        }
    }
    publications {
        register("PerfectDreams", MavenPublication::class.java) {
            from(components["java"])
        }
    }
}