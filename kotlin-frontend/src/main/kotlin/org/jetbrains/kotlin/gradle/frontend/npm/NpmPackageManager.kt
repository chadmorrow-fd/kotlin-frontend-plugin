package org.jetbrains.kotlin.gradle.frontend.npm

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.internal.file.AbstractFileCollection
import org.jetbrains.kotlin.gradle.frontend.Dependency
import org.jetbrains.kotlin.gradle.frontend.dependencies.DependencyExtension
import org.jetbrains.kotlin.gradle.frontend.dependencies.GeneratePackagesJsonTask
import org.jetbrains.kotlin.gradle.frontend.dependencies.PackageManager
import org.jetbrains.kotlin.gradle.frontend.dependencies.UnpackGradleDependenciesTask
import org.jetbrains.kotlin.gradle.frontend.util.NodeJsDownloadTask
import java.io.File

class NpmPackageManager(val project: Project) : PackageManager {
    private val packageJsonFile: File
        get() = project.buildDir.resolve("package.json")

    private val requiredDependencies = mutableListOf<Dependency>()
    private var tasksDefined = false

    override fun require(dependencies: List<Dependency>) {
        requiredDependencies.addAll(dependencies)

        defineTasks()
    }

    override fun install(project: Project) {
        listOf(
            UnpackGradleDependenciesTask::class.java,
            GeneratePackagesJsonTask::class.java,
            NpmInstallTask::class.java,
            NpmIndexTask::class.java,
            NpmDependenciesTask::class.java
        ).flatMap { project.tasks.withType(it) }
            .filterNot { it.state.executed || it.state.skipped || it.state.upToDate }
            .forEach { t ->
                t.logger.lifecycle(":${t.name} (configure)")
                t.execute()
            }
    }

    override fun apply() {
        project.extensions.create("npm", DependencyExtension::class.java)
        injectDependencies()
        project.afterEvaluate {
            defineTasks()
        }
    }

    private fun withConfiguration(name: String, block: (Configuration) -> Unit) {
        project.configurations.findByName(name)?.let(block) ?: project.configurations.whenObjectAdded { configuration ->
            if (configuration.name == name) {
                block(configuration)
            }
        }
    }

    private fun injectDependencies() {
        withConfiguration("compile") { configuration ->
            configuration.dependencies.add(DefaultSelfResolvingDependency(object : AbstractFileCollection() {
                override fun getFiles(): MutableSet<File> {
                    return project.tasks.filterIsInstance<NpmDependenciesTask>().flatMap { it.results }.toMutableSet()
                }

                override fun getDisplayName(): String {
                    return "npm-dependencies"
                }
            }))
        }
    }

    private fun defineTasks() {
        if (!tasksDefined) {
            if (hasDependencies() || project.projectDir.resolve("package.json.d").exists() || requiredDependencies.isNotEmpty()) {
                val unpack =
                    project.tasks.create("npm-preunpack", UnpackGradleDependenciesTask::class.java) { task ->
                        task.dependenciesProvider = { requiredDependencies }
                    }
                val configure =
                    project.tasks.create("npm-configure", GeneratePackagesJsonTask::class.java) { task ->
                        task.description = "Generate package.json and prepare for npm"
                        task.group = NpmGroup

                        task.dependenciesProvider = { requiredDependencies }
                        task.packageJsonFile = packageJsonFile
                        task.npmrcFile = packageJsonFile.resolveSibling(".npmrc")
                    }
                val install = project.tasks.create("npm-install", NpmInstallTask::class.java) { task ->
                    task.description = "Install npm packages"
                    task.group = NpmGroup

                    task.packageJsonFile = packageJsonFile
                }
                val index = project.tasks.create("npm-index", NpmIndexTask::class.java)
                val setup = project.tasks.create("npm-deps", NpmDependenciesTask::class.java)
                val npmAll = project.tasks.create("npm") { task ->
                    task.description = "Configure npm and install packages"
                    task.group = NpmGroup
                }

                if (hasDependencies()) {
                    project.tasks.withType(NodeJsDownloadTask::class.java)?.let { configure.dependsOn(it) }
                    configure.dependsOn(unpack)
                    install.dependsOn(configure)
                    index.dependsOn(install)
                    setup.dependsOn(index)
                    npmAll.dependsOn(setup)
                    project.tasks.getByName("packages").dependsOn(npmAll)
                }

                tasksDefined = true
            }
        }
    }

    override fun hasDependencies(): Boolean {
        val npm = project.extensions.findByName("npm") as DependencyExtension?
        return npm != null && (npm.dependencies.isNotEmpty() || npm.developmentDependencies.isNotEmpty())
    }

    companion object {
        val NpmGroup = "NPM"
    }
}
