# miku's proto sync helper
Plugin syncs proto files from repository

## Features
- Plugin automatically pulls most recent versions of proto files mentioned in configuration on each build 
- Plugin allows to push to remote with `commitChangeRequest` task, changes are only tracked on proto files in build/resources/main/proto-sync directory
- Plugin resolves imports, so you don't have to include all supporting and shared proto files
- Plugin allows to set rules to how to fetch proto files on proto repository, for example to divide production files from development using rules as in example below. Rules support kind of pattern matching: if rule starts with * plugin will think that all branches that end with it are pass and if rules ends with * branch will pass if it's name start with said prefix

## Installation
In plugins section of your `build.gradle(.kts)` add following:
```kotlin
    id("io.github.callmemiku.proto-sync") version "1.0.0"
```

## Usage
1. Configure plugin in your `build.gradle(.kts)` file:

```kotlin
protoSync {
    repository = "URL to repository with your proto files"
    //values in list are equal to proto files names, case is ignored
    services = listOf("service name 1", "service name 2")
    //tells how deep to clone your repo
    depth = 3
    //rules are relation of current branch of project to branch in proto repo
    rules = mapOf("master" to "main")
    //default branch to go if there's no rule
    defaultBranch = "dev"
    //optional parameter which allows to add prefix to branches created with commitChangeRequest task
    autoBranchPrefix = "test-prefix"
}
```

2. Build your project, plugin will automatically clone mentioned proto files and add them to source set
