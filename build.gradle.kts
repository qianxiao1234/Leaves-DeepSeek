plugins {
    java
}

group = "com.leavesdeepseek"
version = "0.1.0"
description = "由 DeepSeek 驱动的 LeavesMC AI 伙伴假人插件"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.leavesmc.org/snapshots/") { name = "leavesmc" }
}

dependencies {
    // leaves-api 已内置 Bukkit / Paper API，无需再单独声明 paper-api
    // 编译目标对齐 Leaves 最新正式版 1.21.8；如服务端为其他 1.21.x 版本，改成对应版本即可
    compileOnly("org.leavesmc.leaves:leaves-api:1.21.8-R0.1-SNAPSHOT")
    // AI 躯壳的 WebSocket 服务端（规格要求 shade 进 jar；不用 Netty）
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
}

java {
    // 构建使用 JDK 25；产物通过 --release 21 保持对 Minecraft 1.21.x 服务端的兼容
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val pluginVersion = project.version.toString()
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveBaseName.set("LeavesDeepSeek")
    // 只把 Java-WebSocket 打进插件 jar（避免把服务端 API / slf4j 一起塞进去）
    from(configurations.runtimeClasspath.get()
        .filter { it.name.contains("Java-WebSocket") }
        .map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        "META-INF/MANIFEST.MF", "module-info.class"
    )
}

tasks.register<Copy>("deployPlugin") {
    group = "leaves"
    description = "构建插件并复制到 run/plugins 开发服务器目录"
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(layout.projectDirectory.dir("run/plugins"))
}
