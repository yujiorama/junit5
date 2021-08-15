import com.gradle.enterprise.gradleplugin.internal.extension.BuildScanExtensionWithHiddenFeatures

plugins {
	alias(libs.plugins.gradle.enterprise)
	alias(libs.plugins.test.distribution)
	alias(libs.plugins.common.custom.user.data)
}

val gradleEnterpriseServer = "https://ge.junit.org"
val isCiServer = System.getenv("CI") != null
val junitBuildCacheUsername: String? by extra
val junitBuildCachePassword: String? by extra

gradleEnterprise {
	buildScan {
		isCaptureTaskInputFiles = true
		isUploadInBackground = !isCiServer

		fun accessKeysAreMissingOrBlank() = System.getenv("GRADLE_ENTERPRISE_ACCESS_KEY").isNullOrBlank()

		if (gradle.startParameter.isBuildScan || (isCiServer && accessKeysAreMissingOrBlank())) {
			termsOfServiceUrl = "https://gradle.com/terms-of-service"
		} else {
			server = gradleEnterpriseServer
			publishAlways()
			this as BuildScanExtensionWithHiddenFeatures
			publishIfAuthenticated()
		}

		if (isCiServer) {
			publishAlways()
			termsOfServiceAgree = "yes"
		}

		obfuscation {
			if (isCiServer) {
				username { "github" }
			} else {
				hostname { null }
				ipAddresses { emptyList() }
			}
		}

		val enableTestDistribution = providers.gradleProperty("enableTestDistribution")
			.forUseAtConfigurationTime()
			.map(String::toBoolean)
			.getOrElse(false)
		if (enableTestDistribution) {
			tag("test-distribution")
		}
	}
}

buildCache {
	local {
		isEnabled = !isCiServer
	}
	remote<HttpBuildCache> {
		url = uri("$gradleEnterpriseServer/cache/")
		isPush = isCiServer && !junitBuildCacheUsername.isNullOrEmpty() && !junitBuildCachePassword.isNullOrEmpty()
		credentials {
			username = junitBuildCacheUsername?.ifEmpty { null }
			password = junitBuildCachePassword?.ifEmpty { null }
		}
	}
}

val javaVersion = JavaVersion.current()
require(javaVersion.isJava11Compatible) {
	"The JUnit 5 build requires Java 11 or higher. Currently executing with Java ${javaVersion.majorVersion}."
}

rootProject.name = "junit5"

include("documentation")
include("junit-jupiter")
include("junit-jupiter-api")
include("junit-jupiter-engine")
include("junit-jupiter-migrationsupport")
include("junit-jupiter-params")
include("junit-platform-commons")
include("junit-platform-console")
include("junit-platform-console-standalone")
include("junit-platform-engine")
include("junit-platform-jfr")
include("junit-platform-launcher")
include("junit-platform-reporting")
include("junit-platform-runner")
include("junit-platform-suite")
include("junit-platform-suite-api")
include("junit-platform-suite-commons")
include("junit-platform-suite-engine")
include("junit-platform-testkit")
include("junit-vintage-engine")
include("platform-tests")
include("platform-tooling-support-tests")
include("junit-bom")

// check that every subproject has a custom build file
// based on the project name
rootProject.children.forEach { project ->
	project.buildFileName = "${project.name}.gradle"
	if (!project.buildFile.isFile) {
		project.buildFileName = "${project.name}.gradle.kts"
	}
	require(project.buildFile.isFile) {
		"${project.buildFile} must exist"
	}
}

enableFeaturePreview("VERSION_CATALOGS")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
