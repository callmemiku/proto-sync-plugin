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
import java.util.jar.JarFile

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
                sshKeyWhereabouts = config.sshKeyWhereabouts
            }
            project.tasks.register('commitChangeRequest', ChangeRequestTask) {
                group = 'proto'
                description = 'Prepares and pushes branch to proto repository'
                repository = config.repository
                output = calculatedOutput
                prefix = config.autoBranchPrefix
                sshKeyWhereabouts = config.sshKeyWhereabouts
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

enum SshConfiguration {
    NEXUS_URL,
    FILE_TYPE,
    FILE_NAME_IN_ARCHIVE,
    USER_ENV,
    PASSWORD_ENV,
    KEY_PATH
}

class Configuration {
    String repository
    List<String> services
    Integer depth
    String defaultBranch
    Map<String, String> rules
    String autoBranchPrefix
    Map<SshConfiguration, String> sshKeyWhereabouts
}

class CloneCommand {
    String repository
    private String repositorySSH
    String repositoryPath
    Integer depth
    String branch
    private Mode mode

    CloneCommand(
            String repository,
            String repositoryPath,
            Integer depth,
            String branch
    ) {
        this.repository = repository
        this.repositorySSH = convertToSSH(repository)
        this.repositoryPath = repositoryPath
        this.depth = depth
        this.branch = branch
        if (branch == null) mode = Mode.NO_BRANCH else mode = Mode.WITH_BRANCH
    }

    List<String> cmd() {
        if (mode == Mode.WITH_BRANCH) {
            ['git', 'clone', "--depth=$depth", '--branch', branch, '--single-branch', repository, repositoryPath]
        } else {
            ['git', 'clone', "--depth=$depth", repository, repositoryPath]
        }
    }

    List<String> cmdSSH() {
        String depthCMD = "--depth=$depth".toString()
        if (mode == Mode.WITH_BRANCH) {
            ['git', 'clone', depthCMD, '--branch', branch, '--single-branch', repositorySSH, repositoryPath]
        } else {
            ['git', 'clone', depthCMD, repositorySSH, repositoryPath]
        }
    }

    private enum Mode {
        WITH_BRANCH,
        NO_BRANCH
    }

    private String convertToSSH(String repository) {
        if (repository.startsWith("git")) return repository
        def pattern = ~/https?:\/\/(.*?)(?:\/|:)(.*?)(?:\.git)?$/
        def matcher = repository.toLowerCase() =~ pattern
        if (matcher.matches()) {
            def domain = matcher[0][1]
            def repoPath = matcher[0][2]
            return "git@${domain}:${repoPath}.git"
        } else {
            throw new IllegalArgumentException("${Utils.error()} Not a valid HTTP(S) Git URL: $repository")
        }
    }
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

    @Input
    Map<SshConfiguration, String> sshKeyWhereabouts

    @Internal
    File temp = project.file("${project.buildDir}/temp")

    @Internal
    File keyTemp = project.file("${project.buildDir}/ssh")

    @Input
    String defaultBranch

    @Input
    Map<String, String> rules

    @TaskAction
    void execute() {
        if (defaultBranch == null) throw new IllegalStateException("[proto-sync::FATAL] No default branch provided, please provide in configuration under defaultBranch parameter!")
        if (rules == null || rules.isEmpty()) println Utils.invalidConf("No branching rules provided, always using default branch!")
        temp.parentFile.mkdirs()
        keyTemp.parentFile.mkdirs()
        def currentBranch = System.getProperty("BRANCH") ?: System.getenv("BRANCH")
        if (Utils.isStringNotOK(currentBranch)) println Utils.invalidConf("No project branch name provided via properties. Example: -DBRANCH=NAME gradle ... . Using default ($defaultBranch).")
        def branch
        String rulesBranch
        if ((rulesBranch = findInRules(rules, currentBranch)) == null) {
            println "${Utils.info()} Using default branch: $defaultBranch to clone..."
            branch = defaultBranch
        } else {
            println "${Utils.info()} Cloning branch: $rulesBranch..."
            branch = rulesBranch
        }
        sshKeyWhereabouts.put(SshConfiguration.KEY_PATH, keyTemp.absolutePath)
        Utils.clone(repository, branch, depth, temp.absolutePath, sshKeyWhereabouts)
        println Utils.success("Successfully cloned repository.")
        println "${Utils.info()} Now filtering only files requested..."
        List<Path> alreadyFound = []
        def filesToLook = requested.collect { (it + ".proto").toUpperCase() }.unique()
        if (!filesToLook.isEmpty()) println "${Utils.info()} Initially looking for ${filesToLook.toString()}"
        else println Utils.invalidConf("No services in configuration, filtering skipped.")
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
                        println "${Utils.info()} File ${it.name} created."
                        target.write(it.text)
                        println "${Utils.info()} Protofile ${it.name} is ready to use."
                        found << name
                    }
                }
            }
            filesToLook = filesToLook.findAll { !found.contains(it) }
            if (!filesToLook.isEmpty()) println "${Utils.error()} Following services were NOT found: ${filesToLook.toString()}"
            filesToLook.clear()
            filesToLook.addAll(additionallyFound)
            if (!filesToLook.isEmpty()) println "${Utils.info()} Found additional dependency protos: ${filesToLook.toString()}"
        }
        println Utils.success("Done filtering cloned files.")
    }

    private String findInRules(Map<String, String> rules, String branch) {
        if (rules == null || rules.isEmpty() || branch == null) return null
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

class Utils {

    static boolean isStringNotOK(String patient) {
        patient == null || patient.isEmpty() || patient.isBlank() || patient == 'null'
    }

    static String info() {
        "${yellow()}[proto-sync::INFO]${reset()}"
    }

    static String invalidConf(String msg) {
        "${blue()}[proto-sync::POSSIBLE INVALID CONFIGURATION] $msg${reset()}"
    }
    
    static String error() {
        "${red()}[proto-sync::ERROR]${reset()}"
    }
    
    static String success(String msg) {
        "${green()}[proto-sync::SUCCESS] $msg${reset()}"
    }
    
    private static String green() { '\u001B[32m' }

    private static String red() { '\u001B[31m' }

    private static String yellow() { '\u001B[33m' }

    private static String blue() { '\u001B[34m' }

    private static String cyan() { '\u001B[36m' }

    static String reset() { '\u001B[0m' }

    static void runProcess(List<String> command, File runAt = null) {
        def proc = runAt ? command.execute(null, runAt) : command.execute()
        runProcess(proc)
    }

    static void runProcess(Process proc) {
        def _ = new StringBuffer()
        def out = new StringBuffer()
        proc.consumeProcessOutput(_, out)
        def exitCode = proc.waitFor()
        if (out.length() > 0) println "${cyan()}[proto-sync::EXTERNAL] git:\n\t> ${def buf = out.toString(); buf.substring(0, buf.length() - 1).replace("\n", "\n\t> ")}${reset()}"
        if (exitCode != 0) throw new IllegalStateException("[proto-sync::FATAL] Non zero exit code from git process, aborting...")
    }

    static void runProcess(ProcessBuilder pb, File runAt = null) {
        if (runAt) pb.directory(runAt)
        runProcess(pb.start())
    }

    static void clone(
            String repository,
            String branch = null,
            Integer depth,
            String repositoryPath,
            Map<SshConfiguration, String> sshKeyWhereabouts,
            File runAt = null
    ) {
        def command = new CloneCommand(repository, repositoryPath, depth, branch)
        try {
            println "${info()} Cloning by HTTPS..."
            runProcess(command.cmd(), runAt)
        } catch (Exception ignored) {
            if (!containsNotNull(sshKeyWhereabouts, SshConfiguration.NEXUS_URL)) {
                println invalidConf("No key URL provided, not trying ssh, aborting...")
                throw ignored
            }
            println "${info()} Cloning by HTTPS failed, trying ssh..."
            def key = getKeyFromNexus(sshKeyWhereabouts)
            def keyPath = sanitizePath(key)
            sshKeyWhereabouts.put(SshConfiguration.KEY_PATH, keyPath)
            def pb = processBuilder(command.cmdSSH(),
                ['GIT_SSH_COMMAND' : "ssh -i $keyPath".toString()]
            )
            runProcess(pb, runAt)
        }
    }

    static ProcessBuilder processBuilder(List<String> cmd, Map<String, String> env) {
        def pb = new ProcessBuilder(cmd)
        pb.environment().putAll(env)
        pb
    }

    static String sanitizePath(File key) {
        def path = key.absolutePath
        if (path.contains("\\")) path = path.replaceAll("\\\\", "/")
        path
    }

    static boolean containsNotNull(Map<SshConfiguration, String> map, SshConfiguration... key) {
        key.every {
            map.get(it) != null
        }
    }

    static String safeGetProperty(Map<SshConfiguration, String> props, SshConfiguration key) {
        def value = props.get(key)
        if (isStringNotOK(value)) throw new IllegalStateException("[proto-sync::FATAL] Invalid value in property ${key.name()}: ${value}")
        value
    }

    static File getKeyFromNexus(Map<SshConfiguration, String> sshKeyWhereabouts) {
        def keyFileName = safeGetProperty(sshKeyWhereabouts, SshConfiguration.FILE_NAME_IN_ARCHIVE)
        FileType mode
        switch (safeGetProperty(sshKeyWhereabouts, SshConfiguration.FILE_TYPE).toLowerCase()) {
            case 'jar': mode = FileType.JAR
                break
            default: mode = FileType.RAW
        }
        def link = safeGetProperty(sshKeyWhereabouts, SshConfiguration.NEXUS_URL)
        def connection = new URL(link).openConnection()
        if (containsNotNull(sshKeyWhereabouts, SshConfiguration.USER_ENV, SshConfiguration.PASSWORD_ENV)) {
            def userKey = safeGetProperty(sshKeyWhereabouts, SshConfiguration.USER_ENV)
            def passwordKey = safeGetProperty(sshKeyWhereabouts, SshConfiguration.PASSWORD_ENV)
            def user = System.getProperty(userKey) ?: System.getenv(userKey)
            def password = System.getProperty(passwordKey) ?: System.getenv(passwordKey)
            if (user == null) throw new IllegalStateException("[proto-sync::FATAL] No property exists on provided property: $userKey.")
            if (password == null) throw new IllegalStateException("[proto-sync::FATAL] No property exists on provided property: $passwordKey.")
            connection.setRequestProperty("Authorization", "Basic " + "$user:$password".bytes.encodeBase64().toString())
        } else println invalidConf("No auth credentials provided, going in unauthorized.")
        def path = safeGetProperty(sshKeyWhereabouts, SshConfiguration.KEY_PATH)
        def download = Paths.get(path).resolve("download${mode.ext}").normalize().toFile()
        println success("Successfully downloaded ${download.name}.")
        download.parentFile.mkdirs()
        download.withOutputStream { out ->
            connection.inputStream.withStream { input ->
                out << input
            }
        }
        def output = Paths.get(path).resolve("ssh-key").normalize().toFile()
        output.parentFile.mkdirs()
        output.createNewFile()
        switch (mode) {
            case FileType.JAR:
                def jar = new JarFile(download)
                def key = jar.getJarEntry(keyFileName)
                def is = jar.getInputStream(key)
                def os = new FileOutputStream(output)
                byte[] buffer = new byte[4096]
                int len
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len)
                }
                jar.close()
                break
            default: output.write(download.text)
        }
        println success("Successfully processed key file, stored as ${Paths.get(path).parent.parent.relativize(output.toPath())}.")
        return output
    }

    private enum FileType {
        JAR('.jar'),
        RAW('');

        final String ext

        FileType(String ext) {
            this.ext = ext
        }
    }
}

class CleanupTask extends DefaultTask {

    @Internal
    File temp = project.file("${project.buildDir}/temp")

    @Internal
    File key = project.file("${project.buildDir}/ssh")

    @TaskAction
    void execute() {
        def git = new File(temp, ".git")
        if (git.exists()) git.deleteDir()
        if (temp.exists()) {
            project.delete(temp)
            println "${Utils.info()} Temp directory cleaned."
        }
        if (key.exists()) {
            project.delete(key)
            println "${Utils.info()} ssh directory cleaned."
        }
    }
}

class ChangeRequestTask extends DefaultTask {

    @Input
    String repository

    @Input
    String output

    @Input
    String prefix

    @Input
    Map<SshConfiguration, String> sshKeyWhereabouts

    @TaskAction
    void execute() {
        def source = new File(output)
        def tempRepo = new File(source, "temp-repo")
        File keyTemp = project.file("${project.buildDir}/key")
        sshKeyWhereabouts.put(SshConfiguration.KEY_PATH, keyTemp.absolutePath)
        def git = new File(tempRepo, ".git")
        if (git.exists()) git.deleteDir()
        if (tempRepo.exists()) tempRepo.deleteDir()
        def branchName = "${prefix ? "$prefix-" : ""}${System.getProperty("user.name") ?: "UNKNOWN_USERNAME"}-at-${Instant.now().toEpochMilli()}".toString()
        Utils.clone(repository, null, 1, tempRepo.absolutePath, sshKeyWhereabouts)
        Utils.runProcess(["git", "checkout", "-b", branchName], tempRepo)
        source.eachFileRecurse { file ->
            if (!file.isFile()) return
            def rel = source.toPath().relativize(file.toPath()).toString()
            def targetFile = new File(tempRepo, rel)
            targetFile.parentFile.mkdirs()
            file.withInputStream { is -> targetFile.withOutputStream { os -> os << is } }
        }
        Utils.runProcess(["git", "add", "."], tempRepo)
        Utils.runProcess(["git", "commit", "-m", branchName], tempRepo)
        def cmd = ["git", "push", "--set-upstream", "origin", branchName]
        def pb = Utils.processBuilder(
                cmd,
                ['GIT_SSH_COMMAND' : "ssh -i ${sshKeyWhereabouts.get(SshConfiguration.KEY_PATH)}".toString()]
        )
        Utils.runProcess(pb, tempRepo)
        if (git.exists()) git.deleteDir()
        if (tempRepo.exists()) tempRepo.deleteDir()
        println Utils.success("Successfully pushed branch $branchName to origin.")
    }
}