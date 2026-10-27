import java.util.zip.ZipFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.serialization") version "2.3.21"
	`maven-publish`
}

group = "io.github.team-sneakymouse"

version = providers.exec {
	workingDir(rootDir)
	commandLine("git", "show", "-s", "--format=%ct:%h", "--abbrev=12", "HEAD")
}.standardOutput.asText.map { commit ->
	val (timestamp, hash) = commit.trim().split(":", limit = 2)
	val date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)
		.format(Instant.ofEpochSecond(timestamp.toLong()))
	"$date-$hash"
}.get()

repositories {
	mavenCentral()
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
	maven("https://maven.sneakyrp.com/releases")
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
	testImplementation(kotlin("test"))
	compileOnly("io.github.team-sneakymouse:sneakydb-api:2026.10.24-4a58c0cac70a")
	testImplementation("io.github.team-sneakymouse:sneakydb-api:2026.10.24-4a58c0cac70a")
	testImplementation("io.github.team-sneakymouse:sneakypocketbase-api:2026.10.09-70effb404392")
	compileOnly("io.papermc.paper:paper-api:26.2.build.117-stable")
	compileOnly("me.clip:placeholderapi:2.11.6")
	compileOnly("io.github.team-sneakymouse:sneakypocketbase-api:2026.10.09-70effb404392")
}

tasks.processResources {
	inputs.property("version", project.version.toString())
	filesMatching("paper-plugin.yml") {
		expand("version" to project.version.toString())
	}
}

tasks.jar {
	archiveBaseName.set(rootProject.name)
	manifest {
		attributes["Main-Class"] = "com.danidipp.sneakymail"
	}
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

configure<JavaPluginExtension> {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
	sourceSets {
		main {
			java.srcDir("src/main/kotlin")
			resources.srcDir(file("src/resources"))
		}
	}
	withSourcesJar()
}

val verifyPrivateKotlinRuntime by tasks.registering {
	dependsOn(tasks.jar)
	doLast {
		val jarFile = tasks.jar.get().archiveFile.get().asFile
		val entries = zipTree(jarFile)
		val required = listOf(
			"kotlin/jvm/functions/Function1.class",
			"kotlinx/serialization/json/JsonKt.class"
		)
		required.forEach { path ->
			check(!entries.matching { include(path) }.isEmpty) {
				"$path must be bundled in ${jarFile.name} to keep Kotlin private to this plugin's classloader"
			}
		}
	}
}

tasks.check {
	dependsOn(verifyPrivateKotlinRuntime)
	dependsOn("verifyPublicApiArtifact")
}

tasks.test {
	useJUnitPlatform()
}

val apiJar by tasks.registering(Jar::class) {
 dependsOn(tasks.classes)
 archiveBaseName.set("sneakymail-api")
 from(sourceSets.main.get().output) { include("com/danidipp/sneakymail/api/**") }
}

publishing.publications.create<MavenPublication>("api") {
 artifactId = "sneakymail-api"
 artifact(apiJar)
 pom {
  name.set("SneakyMail API")
  description.set("JDK-only durable mail admission contract; compileOnly, never shade")
 }
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			artifactId = rootProject.name
			from(components["java"])
			pom {
				name.set("SneakyMail")
				description.set("Paper plugin for sending and receiving mail and items")
				url.set("https://github.com/Team-Sneakymouse/SneakyMail")
				properties.put("sneakyrp.deployment.type", "paper-plugin")
				scm {
					url.set("https://github.com/Team-Sneakymouse/SneakyMail")
					connection.set("scm:git:https://github.com/Team-Sneakymouse/SneakyMail.git")
				}
			}
		}
	}
	repositories {
		maven {
			name = "sneakyrp"
			url = uri("https://maven.sneakyrp.com/releases")
			credentials(PasswordCredentials::class)
			authentication {
				create<org.gradle.authentication.http.BasicAuthentication>("basic")
			}
		}
	}
}

val verifyPublicApiArtifact by tasks.registering {
 dependsOn(apiJar, tasks.jar, "generatePomFileForApiPublication")
 doLast {
  val expected = setOf("SneakyMailApi", "MailAcceptance", "SneakyMailProvider")
    .map { "com/danidipp/sneakymail/api/$it.class" }.toSet()
  ZipFile(apiJar.get().archiveFile.get().asFile).use { zip ->
   val classes = zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toSet()
   check(classes == expected) { "API JAR contains implementation or missing contract: $classes" }
  }
  ZipFile(tasks.jar.get().archiveFile.get().asFile).use { zip ->
   val entries = zip.entries().asSequence().map { it.name }.toList()
   expected.forEach { check(entries.count { entry -> entry == it } == 1) }
   check(entries.none { it.startsWith("com/sneakydb/") || it.startsWith("com/danidipp/sneakypocketbase/") })
  }
  val pom = layout.buildDirectory.file("publications/api/pom-default.xml").get().asFile.readText()
  check("<artifactId>sneakymail-api</artifactId>" in pom)
  check("<dependencies>" !in pom && "sneakyrp.deployment.type" !in pom)
 }
}

tasks.withType<PublishToMavenRepository>().configureEach {
	dependsOn(tasks.check)
}
