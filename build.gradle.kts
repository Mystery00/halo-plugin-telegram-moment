import com.github.gradle.node.NodeExtension
import com.github.gradle.node.pnpm.task.PnpmTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.node-gradle.node") version "7.0.2"
    id("run.halo.plugin.devtools") version "0.0.7"
}

group = "run.halo.starter"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
    maven("https://s01.oss.sonatype.org/content/repositories/releases")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.spring.io/milestone")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation(platform("run.halo.tools.platform:plugin:2.11.0-SNAPSHOT"))
    compileOnly("run.halo.app:api")

    testImplementation("run.halo.app:api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

node {
    nodeProjectDir.set(file("${project.projectDir}/ui"))
}

tasks.register("buildFrontend", PnpmTask::class.java) {
    args.add("build")
}

tasks.getByName<JavaCompile>("compileJava").dependsOn(tasks.getByName("buildFrontend"))

halo {
    version = "2.12.0"
}
