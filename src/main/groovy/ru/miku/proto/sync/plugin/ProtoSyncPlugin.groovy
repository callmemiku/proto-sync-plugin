package ru.miku.proto.sync.plugin

import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction

import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

class ProtoSyncPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create('protoSync', Configuration)
        def config = project.protoSync
        def calculatedOutput = Paths.get(project.buildDir.absolutePath, "resources", "main", "proto-sync").toFile().absolutePath
        project.afterEvaluate {
            project.tasks.register('syncProtoFromRepository', SyncProtoTask) {
                group = 'proto'
                description = 'Syncs proto using provided repository'
                repository = config.repository
                output = calculatedOutput
                requested = config.services
                depth = config.depth ?: 3
                rules = config.rules
                defaultBranch = config.defaultBranch
            }
            project.tasks.register('commitChangeRequest', ChangeRequestTask) {
                group = 'proto'
                description = 'Prepares and pushes branch to proto repository'
                repository = config.repository
                output = calculatedOutput
                prefix = config.autoBranchPrefix
            }
            project.tasks.register('cleanAfterMyself', CleanupTask) {
                group = 'proto'
                description = 'cleans junk files'
            }
            project.tasks.register('cleanBeforeMyself', CleanupTask) {
                group = 'proto'
                description = 'cleans junk files'
            }
            project.tasks.named('clean').configure {
                dependsOn 'cleanBeforeMyself'
            }
            project.tasks.named('cleanAfterMyself').configure {
                dependsOn 'syncProtoFromRepository'
            }
            project.tasks.named('extractIncludeProto').configure {
                dependsOn 'cleanAfterMyself'
            }
        }
        project.pluginManager.withPlugin('com.google.protobuf') {
            SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
            def mainSourceSet = sourceSets.getByName('main')
            SourceDirectorySet protoSrcSet = ((ExtensionAware) mainSourceSet).extensions.getByName('proto')
            def extraProtoDir = project.layout.buildDirectory.dir('resources/main/proto-sync').get().asFile
            protoSrcSet.srcDir(extraProtoDir)
            project.tasks.named('generateProto').configure { generateProtoTask ->
                generateProtoTask.dependsOn('processResources')
            }
        }
    }
}

class Configuration {
    String repository
    List<String> services
    Integer depth
    String defaultBranch
    Map<String, String> rules
    String autoBranchPrefix
}

class SyncProtoTask extends DefaultTask {

    @Input
    String repository

    @Input
    String output

    @Input
    List<String> requested

    @Input
    Integer depth

    @Internal
    File temp = project.file("${project.buildDir}/temp")

    @Input
    String defaultBranch

    @Input
    Map<String, String> rules

    @TaskAction
    void execute() {
        if (defaultBranch == null) throw new IllegalStateException("No default branch provided, please provide in configuration under defaultBranch parameter!")
        if (rules == null || rules.isEmpty()) println "[INFO] No branching rules provided, always using default branch!"
        def currentBranch = System.getProperty("BRANCH")
        if (currentBranch == null) throw new IllegalStateException("No branch name provided via properties. Example: -DBRANCH=NAME gradle build ... ")
        String rulesBranch
        if ((rulesBranch = findInRules(rules, currentBranch)) == null) {
            ['git', 'clone', "--depth=$depth", '--branch', defaultBranch, '--single-branch', repository, temp.absolutePath].execute().waitFor()
            println "[INFO] Using default branch: $defaultBranch."
        } else {
            ['git', 'clone', "--depth=$depth", '--branch', rulesBranch, '--single-branch', repository, temp.absolutePath].execute().waitFor()
            println "[INFO] Cloned branch: $rulesBranch."
        }
        println "[INFO] Now filtering only files requested..."
        List<Path> alreadyFound = []
        def filesToLook = requested.collect { (it + ".proto").toUpperCase() }.unique()
        println "[INFO] Initially looking for ${filesToLook.toString()}"
        while (!filesToLook.isEmpty()) {
            def additionallyFound = []
            def found = []
            temp.eachFileRecurse(FileType.FILES) {
                def name = it.name.toUpperCase()
                if (name.contains(".PROTO") && filesToLook.contains(name)) {
                    def relativePath = temp.toPath().relativize(it.toPath())
                    if (!alreadyFound.contains(relativePath)) {
                        alreadyFound << relativePath
                        it.readLines().findAll {
                            it.contains("import ") && !it.contains("google")
                        }.each {
                            additionallyFound << it.replace("import \"", "")
                                    .replace("\";", "")
                                    .split("/")
                                    .last()
                                    .toUpperCase()
                        }

                        def target = Paths.get(output).resolve(relativePath).normalize().toFile()
                        def protoDir = new File(target.parent)
                        if (protoDir.exists() && !protoDir.isDirectory()) {
                            protoDir.delete()
                            protoDir.mkdirs()
                        } else {
                            protoDir.mkdirs()
                        }
                        println "[INFO] File ${it.name} created."
                        target.write(it.text)
                        println "[INFO] Protofile ${it.name} is ready to use."
                        found << name
                    }
                }
            }
            filesToLook = filesToLook.findAll { !found.contains(it) }
            if (!filesToLook.isEmpty()) println "[INFO] Following services were NOT found: ${filesToLook.toString()}"
            filesToLook.clear()
            filesToLook.addAll(additionallyFound)
            if (!filesToLook.isEmpty()) println "[INFO] Found additional dependency protos: ${filesToLook.toString()}"
        }
        println "[SUCCESS] Done filtering cloned files."
    }

    private String findInRules(Map<String, String> rules, String branch) {
        if (rules == null || rules.isEmpty()) return null
        if (rules.containsKey(branch)) return rules.get(branch)
        return rules.findAll {
            it.key.contains("*")
        }.find {
            def key = it.key.replace("*", "")
            if (it.key.startsWith("*")) {
                branch.endsWith(key)
            } else if (it.key.endsWith("*")) {
                branch.startsWith(key)
            } else false
        }?.value
    }
}

class CleanupTask extends DefaultTask {

    @Internal
    File temp = project.file("${project.buildDir}/temp")

    @TaskAction
    void execute() {
        def git = new File(temp, ".git")
        if (git.exists()) git.deleteDir()
        temp.deleteDir()
        println "cleaned!"
    }
}

class ChangeRequestTask extends DefaultTask {

    @Input
    String repository

    @Input
    String output

    @Input
    String prefix

    @TaskAction
    void execute() {
        def source = new File(output)
        def tempRepo = new File(source, "temp-repo")
        def git = new File(tempRepo, ".git")
        if (git.exists()) git.deleteDir()
        if (tempRepo.exists()) tempRepo.deleteDir()
        def branchName = "${prefix ? "$prefix-" : ""}${System.getProperty("user.name") ?: "UNKNOWN_USERNAME"}-at-${Instant.now().toEpochMilli()}"
        ["git", "clone", "--depth=1", repository, tempRepo.absolutePath].execute().waitFor()
        ["git", "checkout", "-b", branchName].execute(null, tempRepo).waitFor()
        source.eachFileRecurse { file ->
            if (!file.isFile()) return
            def rel = source.toPath().relativize(file.toPath()).toString()
            def targetFile = new File(tempRepo, rel)
            targetFile.parentFile.mkdirs()
            file.withInputStream { is -> targetFile.withOutputStream { os -> os << is } }
        }
        ["git", "add", "."].execute(null, tempRepo).waitFor()
        ["git", "commit", "-m", branchName].execute(null, tempRepo).waitFor()
        ["git", "push", "--set-upstream", "origin", branchName].execute(null, tempRepo).waitFor()
        if (git.exists()) git.deleteDir()
        if (tempRepo.exists()) tempRepo.deleteDir()
        println "Successfully pushed branch $branchName to origin."
    }
}