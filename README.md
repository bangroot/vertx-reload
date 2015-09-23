vertx-reload adds the auto hotswap/reload capability to vertx as an agent. It builds on the awesome work done by the teams on [HotswapAgent](http://hotswapagent.org) and [DCEVM](https://dcevm.github.io).

# Installation/Usage

## Gradle
So far, I've only been able to test and create and example for Gradle, but hopefully it's easy enough to extrapolate to others.

1. Install [DCEVM](https://dcevm.github.io)
2. Modify your build.gradle (or included scripts) to do the necessary: 
    ```groovy
repositories {
  jcenter()
}

configurations {
  agent
}

dependencies {
  agent 'com.github.bangroot:vertx-reload:1.0.0:fat@jar'
}

task copyAgent(type: Copy) {
  from configurations.agent
  into "$buildDir/agent"
}

//if you are using the gradle executions from the vert.x gradle example, add the jvmArgs to execute using the agent
task run(type: JavaExec, dependsOn: ['classes']) {
  classpath sourceSets.main.runtimeClasspath
  main = "io.vertx.core.Starter"
  jvmArgs = ["-javaagent:${new File("$buildDir/agent/vertx-reload-1.0.0-SNAPSHOT-fat.jar").absolutePath}", '-noverify', '-XXaltjvm=dcevm']
  args "run", mainVerticle
}

run.dependsOn copyAgent
```
3. Run with `./gradlew run`
