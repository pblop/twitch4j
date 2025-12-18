import com.coditory.gradle.manifest.ManifestPluginExtension
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.freefair.gradle.plugins.lombok.LombokExtension
import io.freefair.gradle.plugins.lombok.tasks.Delombok
import me.champeau.jmh.JmhParameters
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.Sign

plugins {
	`java-library`
	signing
	id("io.freefair.lombok") version "8.14.2" apply false
	id("com.coditory.manifest") version "0.2.6" apply false
	id("me.champeau.jmh") version "0.7.3" apply false
	id("com.gradleup.shadow") version "8.3.9" apply false
	id("com.github.gmazzo.buildconfig") version "5.5.4" apply false
}

// Ensure group/version are set from properties or defaults
group = findProperty("group") ?: "com.github.twitch4j"
version = findProperty("version") ?: "0.0.1-SNAPSHOT"

allprojects {
	repositories {
		mavenCentral()
	}
}

/**
 * Enables com.coditory.manifest plugin for `publish` tasks or if `-PenableManifest` is supplied
 */
val enableManifest = project.gradle.startParameter.taskNames.any { it.startsWith("publish") }
	|| project.hasProperty("enableManifest")

// Subprojects Configuration
subprojects {
	apply(plugin = "java-library")
	apply(plugin = "maven-publish")
	apply(plugin = "signing")
	apply(plugin = "io.freefair.lombok")
	apply(plugin = "me.champeau.jmh")

	if (enableManifest) {
		apply(plugin = "com.coditory.manifest")
		configure<ManifestPluginExtension> {
			buildAttributes = false
		}
	}

	configure<LombokExtension> {
		version.set("1.18.42")
	}

	configure<JmhParameters> {
		iterations.set(4)
		fork.set(1)
	}

	// Source Compatibility
	java {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
		withSourcesJar()
		withJavadocJar()
	}

	// Dependencies
	dependencies {
		constraints {
			// Annotations
			api("org.jetbrains:annotations:26.0.2")

			// Apache Commons
			api("commons-configuration:commons-configuration:1.10")

			// Rate Limiting
			api("com.bucket4j:bucket4j_jdk8-core:8.10.1")

			// HTTP
			api("com.squareup.okhttp3:okhttp:4.12.0")

			// Credential Manager
			api("com.github.philippheuer.credentialmanager:credentialmanager:0.3.1")

			// Feign & Related
			val feignVersion = "13.6"
			api("io.github.openfeign:feign-slf4j:$feignVersion")
			api("io.github.openfeign:feign-okhttp:$feignVersion")
			api("io.github.openfeign:feign-jackson:$feignVersion")
			api("io.github.openfeign:feign-hystrix:$feignVersion")

			// WebSocket
			api("com.neovisionaries:nv-websocket-client:2.14")

			// Regex
			api("com.github.tony19:named-regexp:1.0.0")

			// Hystrix
			api("com.netflix.hystrix:hystrix-core:1.5.18")

			// Rich version declarations for Jackson
			listOf(
				"com.fasterxml.jackson.core:jackson-annotations",
				"com.fasterxml.jackson.core:jackson-core",
				"com.fasterxml.jackson.core:jackson-databind",
				"com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
			).forEach { dep ->
				add("api", dep) {
					version {
						strictly("[2.15,3-alpha[")
						prefer("2.20.1")
					}
				}
			}

			// Rich version declarations for Feign
			listOf(
				"io.github.openfeign:feign-slf4j",
				"io.github.openfeign:feign-okhttp",
				"io.github.openfeign:feign-jackson",
				"io.github.openfeign:feign-hystrix"
			).forEach { dep ->
				add("api", dep) {
					version {
						require("13.0")
					}
				}
			}
		}

		// Direct Dependencies
		api("commons-io:commons-io:2.20.0")
		api("org.apache.commons:commons-lang3:3.19.0")
		api(platform("io.github.xanthic.cache:cache-bom:0.7.1"))
		api(platform("com.github.philippheuer.events4j:events4j-bom:0.12.3"))
		api("org.slf4j:slf4j-api:2.0.17")
		api(platform("com.fasterxml.jackson:jackson-bom:2.20.1"))

		// Test Dependencies
		testImplementation(platform("org.junit:junit-bom:6.0.1"))
		testImplementation("org.junit.jupiter:junit-jupiter")
		testRuntimeOnly("org.junit.platform:junit-platform-launcher")
		testImplementation(platform("org.mockito:mockito-bom:5.20.0"))
		testImplementation("org.awaitility:awaitility:4.3.0")
		testImplementation("ch.qos.logback:logback-classic:1.3.14")
	}

	// Configure Publishing using explicit extension
	configure<PublishingExtension> {
		if (project.hasProperty("mavenRepositoryUrl")) {
			repositories {
				maven {
					name = "maven"
					url = uri(project.property("mavenRepositoryUrl")!!)
					credentials {
						username = project.property("mavenRepositoryUsername") as String?
						password = project.property("mavenRepositoryPassword") as String?
					}
				}
			}
		}
		publications {
			create<MavenPublication>("main") {
				from(components["java"])
				// Removed pom.default() as it is likely custom/invalid.
				// Standard configuration handles defaults automatically.
			}
		}
	}

	signing {
		useGpgCmd()
		// Only sign if 'publish' is in the task graph to avoid JitPack local errors
	}

	tasks {
		withType<Jar> {
			if (this is ShadowJar) {
				archiveClassifier.set("shaded")
				isEnableRelocation = true
				relocationPrefix = "com.github.twitch4j.shaded"
				dependencies {
					exclude("META-INF/versions/**/module-info.class")
				}
				manifest {
					attributes("Multi-Release" to true)
				}
			}
			if (enableManifest) {
				manifest.from(file("${layout.buildDirectory.get()}/resources/main/META-INF/MANIFEST.MF"))
			}
		}

		withType<AbstractArchiveTask>().configureEach {
			isPreserveFileTimestamps = false
			isReproducibleFileOrder = true
		}

		// Fix for the publishToMavenLocal reference
		withType<Sign>().configureEach {
			onlyIf {
				!project.gradle.taskGraph.hasTask("publishToMavenLocal")
			}
		}

		withType<JavaCompile> {
			options.encoding = "UTF-8"
		}

		compileTestJava {
			options.release.set(17)
		}

		withType<Javadoc> {
			(options as StandardJavadocDocletOptions).apply {
				links(
					"https://javadoc.io/doc/org.jetbrains/annotations/26.0.2",
					"https://javadoc.io/doc/commons-configuration/commons-configuration/1.10",
					"https://javadoc.io/doc/com.bucket4j/bucket4j_jdk8-core/8.10.1",
					"https://javadoc.io/doc/com.github.philippheuer.events4j/events4j-core/0.12.3",
					"https://javadoc.io/doc/io.github.openfeign/feign-core/13.6",
					"https://javadoc.io/doc/org.slf4j/slf4j-api/2.0.17",
					"https://twitch4j.github.io/javadoc"
				)
				locale = "en"
				tags = listOf("apiNote:a:API Note:", "implSpec:a:Implementation Requirements:", "implNote:a:Implementation Note:")
			}
		}

		val delombok by getting(Delombok::class)
		javadoc {
			dependsOn(delombok)
			source(delombok)
			(options as StandardJavadocDocletOptions).apply {
				title = "${project.name} (v${project.version})"
				windowTitle = "${project.name} (v${project.version})"
				encoding = "UTF-8"
				overview = "../buildSrc/overview-single.html"
				addStringOption("Xdoclint:none", "-quiet")
				if (JavaVersion.current().isJava9Compatible) {
					addBooleanOption("html5", true)
				}
			}
		}

		test {
			useJUnitPlatform {
				includeTags("unittest")
				excludeTags("integration")
			}
		}
	}
}

// Root project tasks
tasks.register<Javadoc>("aggregateJavadoc") {
	enabled = JavaVersion.current().isJava9Compatible
	group = JavaBasePlugin.DOCUMENTATION_GROUP
	(options as StandardJavadocDocletOptions).apply {
		title = "${rootProject.name} (v${project.version})"
		windowTitle = "${rootProject.name} (v${project.version})"
		encoding = "UTF-8"
		overview = file("${rootDir}/buildSrc/overview-general.html").absolutePath
		addStringOption("Xdoclint:none", "-quiet")
		if (JavaVersion.current().isJava9Compatible) {
			addBooleanOption("html5", true)
		}
	}

	source(subprojects.map { it.tasks.javadoc.get().source })
	classpath = files(subprojects.map { it.tasks.javadoc.get().classpath })
	setDestinationDir(file("${rootDir}/docs/static/javadoc"))
}
