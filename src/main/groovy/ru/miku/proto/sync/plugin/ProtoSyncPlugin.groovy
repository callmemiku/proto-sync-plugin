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

import java.nio.file.Paths

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
                depth = config.depth
            }
            project.tasks.register('commitChangeRequest', ChangeRequestTask) {
                group = 'proto'
                description = 'Prepares and pushes branch to proto repository'
                repository = config.repository
                output = calculatedOutput
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

    @TaskAction
    void execute() {
        project.exec {
            commandLine('git', 'clone', "--depth=$depth", repository, temp.absolutePath)
        }
        sleep(1000)
        println "Pulled successfully."
        println "Now filtering only files requested..."
        def filesToLook = requested.collect { (it + ".proto").toUpperCase() }
        println "Initially looking for ${filesToLook.toString()}"
        while (!filesToLook.isEmpty()) {
            def additionallyFound = []
            def found = []
            temp.eachFileRecurse(FileType.FILES) {
                def name = it.name.toUpperCase()
                if (name.contains(".PROTO") && filesToLook.contains(name)) {
                    it.readLines().findAll {
                        it.contains("import ") && !it.contains("google")
                    }.each {
                        additionallyFound << it.replace("import \"", "")
                        .replace("\";", "").toUpperCase()
                    }
                    def relativePath = temp.toPath().relativize(it.toPath())
                    def target = Paths.get(output).resolve(relativePath).normalize().toFile()
                    def protoDir = new File(target.parent)
                    if (protoDir.exists() && !protoDir.isDirectory()) {
                        protoDir.delete()
                        protoDir.mkdirs()
                    } else {
                        protoDir.mkdirs()
                    }
                    println "File ${it.name} created."
                    target.write(it.text)
                    println "Protofile ${it.name} is ready to use."
                    found << name
                }
            }
            filesToLook = filesToLook.findAll { !found.contains(it) }
            if (!filesToLook.isEmpty()) println "Following services were NOT found: ${filesToLook.toString()}"
            filesToLook.clear()
            filesToLook.addAll(additionallyFound)
            if (!filesToLook.isEmpty()) println "Found additional dependency protos: ${filesToLook.toString()}"
        }
        println "Done filtering cloned files."
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

    @TaskAction
    void execute() {

    }
}