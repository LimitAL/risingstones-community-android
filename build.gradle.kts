import com.android.build.api.dsl.LibraryExtension
import java.io.File
import java.net.URI
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@DisableCachingByDefault(
    because = "Validates the current release-signing environment without persisting secrets.",
)
abstract class VerifyReleaseConfigurationTask : DefaultTask() {
    @get:Input
    abstract val releaseVersion: Property<String>

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<String>

    @get:Internal
    abstract val licenseFile: RegularFileProperty

    @get:Input
    abstract val publicationMetadata: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val normalizedReleaseVersion = releaseVersion.get().trim()
        require(
            normalizedReleaseVersion.isNotEmpty() &&
                !normalizedReleaseVersion.endsWith("-SNAPSHOT"),
        ) {
            "risingStonesVersion must be a non-SNAPSHOT release version"
        }

        require(appVersionName.get().trim() == normalizedReleaseVersion) {
            "risingStonesAppVersionName must match risingStonesVersion for a public release"
        }
        require(appVersionCode.get().toIntOrNull()?.let { it > 0 } == true) {
            "risingStonesAppVersionCode must be a positive integer"
        }

        require(licenseFile.asFile.get().isFile) {
            "LICENSE must be added before a public release"
        }

        val normalizedMetadata = publicationMetadata.get().mapValues { (_, value) -> value.trim() }
        val missingMetadata = normalizedMetadata.filterValues(String::isBlank).keys
        require(missingMetadata.isEmpty()) {
            "Public Maven metadata is incomplete. Missing Gradle properties: " +
                missingMetadata.sorted().joinToString()
        }
        normalizedMetadata.forEach { (key, value) ->
            require('\n' !in value && '\r' !in value) {
                "$key must be a single-line value"
            }
            require(
                listOf(
                    "example.com",
                    "example.org",
                    "example.net",
                    ".invalid",
                    "localhost",
                    "changeme",
                    "todo",
                ).none {
                    value.contains(it, ignoreCase = true)
                },
            ) {
                "$key still contains a placeholder value"
            }
        }
        listOf(
            "risingStonesProjectUrl",
            "risingStonesLicenseUrl",
            "risingStonesScmUrl",
        ).forEach { key ->
            val uri = runCatching { URI(normalizedMetadata.getValue(key)) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
                "$key must be an absolute HTTPS URL"
            }
        }
        listOf(
            "risingStonesScmConnection",
            "risingStonesScmDeveloperConnection",
        ).forEach { key ->
            require(normalizedMetadata.getValue(key).startsWith("scm:git:")) {
                "$key must use an scm:git: connection"
            }
        }
        require(
            normalizedMetadata.getValue("risingStonesDeveloperEmail")
                .matches(Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")),
        ) {
            "risingStonesDeveloperEmail must be a public contact email address"
        }

        val signingInputs = listOf(
            "RISINGSTONES_RELEASE_STORE_FILE",
            "RISINGSTONES_RELEASE_STORE_PASSWORD",
            "RISINGSTONES_RELEASE_KEY_ALIAS",
            "RISINGSTONES_RELEASE_KEY_PASSWORD",
        ).associateWith(System::getenv)
        val missingSigningInputs = signingInputs
            .filterValues { it.isNullOrBlank() }
            .keys
        require(missingSigningInputs.isEmpty()) {
            "Public release signing is incomplete. Missing: ${missingSigningInputs.joinToString()}"
        }

        val storePath = requireNotNull(signingInputs["RISINGSTONES_RELEASE_STORE_FILE"])
        require(File(storePath).isAbsolute && File(storePath).isFile) {
            "RISINGSTONES_RELEASE_STORE_FILE must point to an existing absolute file"
        }
    }
}

@DisableCachingByDefault(
    because = "Validates the generated release manifest rather than producing an artifact.",
)
abstract class VerifyReleaseManifestSecurityTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(manifestFile.asFile.get())
        val application = document.getElementsByTagName("application").item(0) as? Element
            ?: error("Generated release manifest has no application element")
        fun Element.androidAttribute(name: String): String =
            getAttributeNS(AndroidNamespace, name)

        val requiredApplicationAttributes = mapOf(
            "allowBackup" to "false",
            "dataExtractionRules" to "@xml/data_extraction_rules",
            "fullBackupContent" to "@xml/backup_rules",
            "usesCleartextTraffic" to "false",
        )
        requiredApplicationAttributes.forEach { (name, expected) ->
            require(application.androidAttribute(name) == expected) {
                "Release manifest application attribute android:$name must be $expected"
            }
        }
        require(application.androidAttribute("debuggable") != "true") {
            "Release manifest must not be debuggable"
        }

        val shareProviders = buildList {
            val providers = document.getElementsByTagName("provider")
            for (index in 0 until providers.length) {
                val provider = providers.item(index) as Element
                if (provider.androidAttribute("name") == "top.cxmeow.risingstones.app.PngShareFileProvider") {
                    add(provider)
                }
            }
        }
        require(shareProviders.size == 1) {
            "Release manifest must contain exactly one PNG share FileProvider"
        }
        val shareProvider = shareProviders.single()
        val requiredShareProviderAttributes = mapOf(
            "authorities" to "top.cxmeow.risingstones.share",
            "exported" to "false",
            "grantUriPermissions" to "true",
        )
        requiredShareProviderAttributes.forEach { (name, expected) ->
            require(shareProvider.androidAttribute(name) == expected) {
                "Release PNG share provider android:$name must be $expected"
            }
        }
        val sharePathMetadata = shareProvider.getElementsByTagName("meta-data")
        require((0 until sharePathMetadata.length).any { index ->
            val metadata = sharePathMetadata.item(index) as Element
            metadata.androidAttribute("name") == "android.support.FILE_PROVIDER_PATHS" &&
                metadata.androidAttribute("resource") == "@xml/share_paths"
        }) {
            "Release PNG share provider must use the exact private share_paths resource"
        }

        val exportedComponents = buildList {
            listOf("activity", "activity-alias", "service", "receiver", "provider").forEach { tag ->
                val elements = document.getElementsByTagName(tag)
                for (index in 0 until elements.length) {
                    val element = elements.item(index) as Element
                    if (element.androidAttribute("exported") == "true") {
                        add(
                            Triple(
                                tag,
                                element.androidAttribute("name"),
                                element.androidAttribute("permission"),
                            ),
                        )
                    }
                }
            }
        }
        require(
            Triple(
                "activity",
                "top.cxmeow.risingstones.app.MainActivity",
                "",
            ) in exportedComponents,
        ) {
            "The standalone launcher activity is missing from the release manifest"
        }
        exportedComponents.forEach { (tag, name, permission) ->
            val isLauncher = tag == "activity" &&
                name == "top.cxmeow.risingstones.app.MainActivity" &&
                permission.isEmpty()
            val isProtectedProfileInstaller = tag == "receiver" &&
                name == "androidx.profileinstaller.ProfileInstallReceiver" &&
                permission == "android.permission.DUMP"
            require(isLauncher || isProtectedProfileInstaller) {
                "Unreviewed exported release component: $tag $name permission=$permission"
            }
        }
    }

    private companion object {
        const val AndroidNamespace = "http://schemas.android.com/apk/res/android"
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "top.cxmeow.risingstones"
    version = providers.gradleProperty("risingStonesVersion")
        .getOrElse("0.1.0-SNAPSHOT")
}

val publicLibraryProjects = subprojects.filter { it.name != "app" }
val localBuildRepository = layout.buildDirectory.dir("repository")
val githubPackagesRepositoryUrl = providers.gradleProperty("risingStonesGitHubPackagesUrl")
    .orElse("https://maven.pkg.github.com/LimitAL/risingstones-community-android")
val githubPackagesUsername = providers.environmentVariable("GITHUB_ACTOR")
val githubPackagesToken = providers.environmentVariable("GITHUB_TOKEN")
val publicReleaseVersion = providers.gradleProperty("risingStonesVersion")
    .orElse("0.1.0-SNAPSHOT")
val publicAppVersionName = providers.gradleProperty("risingStonesAppVersionName")
    .orElse(publicReleaseVersion)
val publicAppVersionCode = providers.gradleProperty("risingStonesAppVersionCode")
    .orElse("1")
val publicProjectUrl = providers.gradleProperty("risingStonesProjectUrl")
val publicLicenseName = providers.gradleProperty("risingStonesLicenseName")
val publicLicenseUrl = providers.gradleProperty("risingStonesLicenseUrl")
val publicDeveloperName = providers.gradleProperty("risingStonesDeveloperName")
val publicDeveloperEmail = providers.gradleProperty("risingStonesDeveloperEmail")
val publicScmConnection = providers.gradleProperty("risingStonesScmConnection")
val publicScmDeveloperConnection = providers.gradleProperty("risingStonesScmDeveloperConnection")
val publicScmUrl = providers.gradleProperty("risingStonesScmUrl")
val publicPomMetadata = mapOf(
    "risingStonesProjectUrl" to publicProjectUrl,
    "risingStonesLicenseName" to publicLicenseName,
    "risingStonesLicenseUrl" to publicLicenseUrl,
    "risingStonesDeveloperName" to publicDeveloperName,
    "risingStonesDeveloperEmail" to publicDeveloperEmail,
    "risingStonesScmConnection" to publicScmConnection,
    "risingStonesScmDeveloperConnection" to publicScmDeveloperConnection,
    "risingStonesScmUrl" to publicScmUrl,
)

tasks.register<VerifyReleaseConfigurationTask>("verifyReleaseConfiguration") {
    group = "verification"
    description = "Rejects an incomplete or unsafe public release configuration."
    releaseVersion.set(publicReleaseVersion)
    appVersionName.set(publicAppVersionName)
    appVersionCode.set(publicAppVersionCode)
    licenseFile.set(layout.projectDirectory.file("LICENSE"))
    publicPomMetadata.forEach { (key, value) ->
        publicationMetadata.put(key, value.orElse(""))
    }
}

tasks.register<VerifyReleaseManifestSecurityTask>("verifyReleaseManifestSecurity") {
    group = "verification"
    description = "Verifies backup, cleartext, debug, and exported-component release policy."
    dependsOn(":app:processReleaseManifest")
    manifestFile.set(
        project(":app").layout.buildDirectory.file(
            "intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
        ),
    )
}

subprojects {
    plugins.withId("com.android.library") {
        pluginManager.apply("maven-publish")

        val javadocJar = tasks.register<Jar>("javadocJar") {
            group = "documentation"
            description = "Packages a documentation pointer for Maven consumers."
            archiveClassifier.set("javadoc")
            from(rootProject.layout.projectDirectory.file("README.md"))
        }

        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "localBuild"
                    url = localBuildRepository.get().asFile.toURI()
                }
                maven {
                    name = "GitHubPackages"
                    url = uri(githubPackagesRepositoryUrl.get())
                    credentials {
                        username = githubPackagesUsername.orNull
                        password = githubPackagesToken.orNull
                    }
                }
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        artifactId = project.name
                        pom {
                            name.set("Rising Stones Android ${project.name}")
                            description.set("Reusable official Rising Stones Android module: ${project.name}")
                            if (publicPomMetadata.values.all { it.isPresent && it.get().isNotBlank() }) {
                                url.set(publicProjectUrl)
                                licenses {
                                    license {
                                        name.set(publicLicenseName)
                                        url.set(publicLicenseUrl)
                                        distribution.set("repo")
                                    }
                                }
                                developers {
                                    developer {
                                        name.set(publicDeveloperName)
                                        email.set(publicDeveloperEmail)
                                    }
                                }
                                scm {
                                    connection.set(publicScmConnection)
                                    developerConnection.set(publicScmDeveloperConnection)
                                    url.set(publicScmUrl)
                                    tag.set("HEAD")
                                }
                            }
                        }
                        artifact(javadocJar)
                    }
                }
            }
        }
    }
}

val publishPublicLibrariesToLocalRepository =
    tasks.register("publishPublicLibrariesToLocalRepository") {
        group = "publishing"
        description = "Publishes every reusable library into build/repository."
        dependsOn(
            publicLibraryProjects.map {
                "${it.path}:publishReleasePublicationToLocalBuildRepository"
            },
        )
    }

val publishPublicLibrariesToGitHubPackagesRepository =
    tasks.register("publishPublicLibrariesToGitHubPackagesRepository") {
        group = "publishing"
        description = "Publishes every reusable library to GitHub Packages."
        dependsOn(
            publicLibraryProjects.map {
                "${it.path}:publishReleasePublicationToGitHubPackagesRepository"
            },
        )
    }
