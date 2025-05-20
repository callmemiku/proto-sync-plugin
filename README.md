# miku's proto sync helper
Plugin syncs proto files from repository

## Features
- Plugin automatically pulls most recent versions of proto files mentioned in configuration on each build 
- Plugin allows to push to remote with `commitChangeRequest` task, changes are only tracked on proto files in build/resources/main/proto-sync directory
- Plugin resolves imports, so you don't have to include all supporting and shared proto files
- Plugin allows to set rules to how to fetch proto files on proto repository, for example to divide production files from development using rules as in example below. Rules support kind of pattern matching: if rule starts with * plugin will think that all branches that end with it are pass and if rules ends with * branch will pass if it's name start with said prefix
- Plugin allows to switch to ssh connection. HTTPS link will be automatically converted to ssh format if none provided under `SSH_REPOSITORY_URL` property. All additional info contained in `sshWhereabouts` property, see example below.
## Installation
In plugins section of your `build.gradle(.kts)` add following:
```kotlin
    id("io.github.callmemiku.proto-sync") version "1.0.0"
```

## Usage
1. Configure plugin in your `build.gradle(.kts)` file:

```kotlin
import ru.miku.proto.sync.plugin.SshConfiguration.*

protoSync {
    repository = "URL to repository with your proto files"
    //values in list are equal to proto files names, case is ignored
    services = listOf("service name 1", "service name 2")
    //tells how deep to clone your repo
    depth = 3
    //rules are relation of current branch of project to branch in proto repo
    //any branches starting with 'release/' will look at main branch as well as master branch
    //any branches ending with 'test' will look at test branch
    rules = mapOf("master" to "main", "release/*" to "main", "*test" to "test")
    //all other branches will look at dev branch
    defaultBranch = "dev"
    //optional parameter which allows to add prefix to branches created with commitChangeRequest task
    autoBranchPrefix = "test-prefix"
    //optional parameter, contains coordinates of artifact containing key. following example for jar containing id_ed25519 file in root dir
    keyStorage = mapOf<SshConfiguration, String>(
        FILE_TYPE to "jar", //supports jar and raw, zip in plans
        FILE_NAME_IN_ARCHIVE to "id_ed25519", //can be omitted if download is raw
        NEXUS_URL to "https://nexus-host/repository/maven-releases/ru/miku/key-storage/1.0.0/key-storage-1.0.0.jar",
        //currently supports only basic auth, if no props provided, will try to access unauthorized
        //will try to get these props from system props and then from env
        USER_ENV to "NEXUS_USER",
        PASSWORD_ENV to "NEXUS_PASSWORD",
        //if URL provided here, plugin won't try to automatically convert
        //in otherwise plugin will notify you with conversion results, so it's possible to track possible problems
        SSH_REPOSITORY_URL to "ssh://git@repository-host:4444/project/proto-storage.git"
    )
}
```

2. Build your project, plugin will automatically clone mentioned proto files and add them to source set
