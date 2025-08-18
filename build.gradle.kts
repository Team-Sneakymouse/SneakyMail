plugins {
	kotlin("jvm") version "2.1.0"
	kotlin("plugin.serialization") version "2.1.0"
}

repositories {
	maven {
		url = uri("https://plugins.gradle.org/m2/")
	}
	maven {
		name = "papermc"
		url = uri("https://repo.papermc.io/repository/maven-public/")
	}
	maven {
		url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
	}
	mavenCentral()
}

dependencies {
	compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
	compileOnly("me.clip:placeholderapi:2.11.6")
//	compileOnly(files("libs/SneakyPocketbase-1.0.jar"))
	compileOnly(files("C:\\Users\\DaniDipp\\Downloads\\1.21.4\\SneakyPocketbase-1.0.jar"))
}

tasks.jar {
	manifest {
		attributes["Main-Class"] = "com.danidipp.sneakymail"
	}
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

configure<JavaPluginExtension> {
	sourceSets {
		main {
			java.srcDir("src/main/kotlin")
			resources.srcDir(file("src/resources"))
		}
	}
}
