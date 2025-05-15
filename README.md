# miku's proto sync helper
Plugin syncs proto files from repository

## Features
- Plugin automatically pulls most recent versions of proto files mentioned in configuration on each build 
- Plugin allows to push to remote with `commitChangeRequest` task, changes are only tracked on proto files in build/resources/main/proto-sync directory
- Plugin resolves imports, so you don't have to include all supporting and shared proto files

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
}
```

2. Build your project, plugin will automatically clone mentioned proto files and add them to source set
