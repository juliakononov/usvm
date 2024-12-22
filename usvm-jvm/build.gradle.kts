@file:Suppress("PropertyName", "HasPlatformType")

plugins {
    id("usvm.kotlin-conventions")
    id("org.springframework.boot") version "3.2.0"
}

val samples by sourceSets.creating {
    java {
        srcDir("src/samples/java")
    }
}

val `sample-approximations` by sourceSets.creating {
    java {
        srcDir("src/sample-approximations/java")
    }
}

val `usvm-api` by sourceSets.creating {
    java {
        srcDir("src/usvm-api/java")
    }
}

val approximations by configurations.creating
val approximationsRepo = "org.usvm.approximations.java.stdlib"
val approximationsVersion = "0.0.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":usvm-core"))
    implementation(project(":usvm-jvm-dataflow"))

    implementation(Libs.jacodb_api_jvm)
    implementation(Libs.jacodb_core)
    implementation(Libs.jacodb_approximations)

    implementation(`usvm-api`.output)

    implementation(Libs.ksmt_runner)
    implementation(Libs.ksmt_yices)
    implementation(Libs.ksmt_cvc5)
    implementation(Libs.ksmt_symfpu)

    testImplementation(Libs.mockk)
    testImplementation(Libs.junit_jupiter_params)
    testImplementation(Libs.logback)

    testImplementation(samples.output)

    // https://mvnrepository.com/artifact/org.burningwave/core
    // Use it to export all modules to all
    testImplementation("org.burningwave:core:12.62.7")

    approximations(approximationsRepo, "approximations", approximationsVersion)
    testImplementation(approximationsRepo, "tests", approximationsVersion)
}

val springApproximationsDeps by configurations.creating

dependencies {
    springApproximationsDeps("org.springframework.boot:spring-boot-starter-test:3.2.0")
    springApproximationsDeps("org.springframework.boot:spring-boot-starter-web:3.2.0")
    springApproximationsDeps("org.springframework:spring-jcl:6.1.1")
    springApproximationsDeps("org.springframework.boot:spring-boot-starter-data-jpa:3.2.0")
}

val `usvm-apiCompileOnly`: Configuration by configurations.getting
dependencies {
    `usvm-apiCompileOnly`(Libs.jacodb_api_jvm)
}

val samplesImplementation: Configuration by configurations.getting

dependencies {
    samplesImplementation("org.projectlombok:lombok:${Versions.Samples.lombok}")
    samplesImplementation("org.slf4j:slf4j-api:${Versions.Samples.slf4j}")
    samplesImplementation("javax.validation:validation-api:${Versions.Samples.javaxValidation}")
    samplesImplementation("com.github.stephenc.findbugs:findbugs-annotations:${Versions.Samples.findBugs}")
    samplesImplementation("org.jetbrains:annotations:${Versions.Samples.jetbrainsAnnotations}")

    // Use usvm-api in samples for makeSymbolic, assume, etc.
    samplesImplementation(`usvm-api`.output)

    implementation(project(":usvm-jvm-instrumentation"))
    testImplementation(project(":usvm-jvm-instrumentation"))
}

val `sample-approximationsCompileOnly`: Configuration by configurations.getting

dependencies {
    `sample-approximationsCompileOnly`(samples.output)
    `sample-approximationsCompileOnly`(`usvm-api`.output)
    `sample-approximationsCompileOnly`(Libs.jacodb_api_jvm)
    `sample-approximationsCompileOnly`(Libs.jacodb_approximations)
}

val `usvm-api-jar` = tasks.register<Jar>("usvm-api-jar") {
    archiveBaseName.set(`usvm-api`.name)
    from(`usvm-api`.output)
}

val testSamples by configurations.creating
val testSamplesWithApproximations by configurations.creating

dependencies {
    testSamples(samples.output)
    testSamples(`usvm-api`.output)

    testSamplesWithApproximations(samples.output)
    testSamplesWithApproximations(`usvm-api`.output)
    testSamplesWithApproximations(`sample-approximations`.output)
    testSamplesWithApproximations(approximationsRepo, "tests", approximationsVersion)
}

tasks.withType<Test> {
    dependsOn(`usvm-api-jar`)
    dependsOn(testSamples, testSamplesWithApproximations)

    val usvmApiJarPath = `usvm-api-jar`.get().outputs.files.singleFile
    val usvmApproximationJarPath = approximations.resolvedConfiguration.files.single()

    environment("usvm.jvm.api.jar.path", usvmApiJarPath.absolutePath)
    environment("usvm.jvm.approximations.jar.path", usvmApproximationJarPath.absolutePath)

    environment("usvm.jvm.test.samples", testSamples.asPath)
    environment("usvm.jvm.test.samples.approximations", testSamplesWithApproximations.asPath)
}


tasks {
    register<Jar>("testJar") {
        group = "jar"
        shouldRunAfter("compileTestKotlin")
        archiveClassifier.set("test")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val contents = sourceSets.getByName("samples").output

        from(contents)
        dependsOn(getByName("compileSamplesJava"), configurations.testCompileClasspath)
        dependsOn(configurations.compileClasspath)
    }
}

tasks.getByName("compileTestKotlin").finalizedBy("testJar")

tasks.withType<Test> {
    environment(
        "usvm-test-jar",
        layout
            .buildDirectory
            .file("libs/usvm-jvm-test.jar")
            .get().asFile.absolutePath
    )
    environment(
        "usvm-jvm-instrumentation-jar",
        project(":usvm-jvm-instrumentation")
            .layout
            .buildDirectory
            .file("libs/usvm-jvm-instrumentation-1.0.jar")
            .get().asFile.absolutePath
    )
    environment(
        "usvm-jvm-collectors-jar",
        project(":usvm-jvm-instrumentation")
            .layout
            .buildDirectory
            .file("libs/usvm-jvm-instrumentation-collectors.jar")
            .get().asFile.absolutePath
    )
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
        create<MavenPublication>("maven-api") {
            artifactId = "usvm-jvm-api"
            artifact(`usvm-api-jar`)
        }
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-actuator-autoconfigure:3.2.0")
    implementation("org.springframework.boot:spring-boot-actuator:3.2.0")
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.2.0")
    implementation("org.springframework.boot:spring-boot-devtools:3.2.0")
    implementation("org.springframework.boot:spring-boot-docker-compose:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-actuator:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-cache:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-jdbc:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-json:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-logging:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-test:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-tomcat:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.0")
    implementation("org.springframework.boot:spring-boot-starter:3.2.0")
    implementation("org.springframework.boot:spring-boot-test-autoconfigure:3.2.0")
    implementation("org.springframework.boot:spring-boot-test:3.2.0")
    implementation("org.springframework.boot:spring-boot-testcontainers:3.2.0")
    implementation("org.springframework.boot:spring-boot:3.2.0")
    implementation("org.springframework.data:spring-data-commons:3.2.0")
    implementation("org.springframework.data:spring-data-jpa:3.2.0")
    implementation("org.springframework:spring-aop:6.1.1")
    implementation("org.springframework:spring-aspects:6.1.1")
    implementation("org.springframework:spring-beans:6.1.1")
    implementation("org.springframework:spring-context-support:6.1.1")
    implementation("org.springframework:spring-context:6.1.1")
    implementation("org.springframework:spring-core:6.1.1")
    implementation("org.springframework:spring-expression:6.1.1")
    implementation("org.springframework:spring-jcl:6.1.1")
    implementation("org.springframework:spring-jdbc:6.1.1")
    implementation("org.springframework:spring-orm:6.1.1")
    implementation("org.springframework:spring-test:6.1.1")
    implementation("org.springframework:spring-tx:6.1.1")
    implementation("org.springframework:spring-web:6.1.1")
    implementation("org.springframework:spring-webmvc:6.1.1")
}

tasks.register<JavaExec>("runWebBench") {
    mainClass.set("bench.WebBenchKt")
    classpath = sourceSets.test.get().runtimeClasspath

    dependsOn(`usvm-api-jar`)

    val usvmApiJarPath = `usvm-api-jar`.get().outputs.files.singleFile
    val usvmApproximationJarPath = approximations.resolvedConfiguration.files.single()
    val springApproximationDepsJarPath = springApproximationsDeps.resolvedConfiguration.files
    val absolutePaths = org.jetbrains.kotlin.utils.join(springApproximationDepsJarPath.map { it.absolutePath }, ";")

    // TODO: norm? #CM #Valya
    systemProperty("usvm.jvm.springApproximationsDeps.paths", absolutePaths)

    environment("usvm.jvm.api.jar.path", usvmApiJarPath.absolutePath)
    environment("usvm.jvm.approximations.jar.path", usvmApproximationJarPath.absolutePath)

    jvmArgs = listOf("-Xmx4g") + mutableListOf<String>().apply {
        add("-Djava.security.manager -Djava.security.policy=webExplorationPolicy.policy")
        add("-Djdk.internal.lambda.dumpProxyClasses=/home/julia/spring-petclinic/build/libs/BOOT-INF/classes")
        openPackage("java.base", "jdk.internal.misc")
        openPackage("java.base", "java.lang")
        openPackage("java.base", "java.lang.reflect")
        openPackage("java.base", "sun.security.provider")
        openPackage("java.base", "jdk.internal.event")
        openPackage("java.base", "jdk.internal.jimage")
        openPackage("java.base", "jdk.internal.jimage.decompressor")
        openPackage("java.base", "jdk.internal.jmod")
        openPackage("java.base", "jdk.internal.jtrfs")
        openPackage("java.base", "jdk.internal.loader")
        openPackage("java.base", "jdk.internal.logger")
        openPackage("java.base", "jdk.internal.math")
        openPackage("java.base", "jdk.internal.misc")
        openPackage("java.base", "jdk.internal.module")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.commons")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.signature")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.tree")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.tree.analysis")
        openPackage("java.base", "jdk.internal.org.objectweb.asm.util")
        openPackage("java.base", "jdk.internal.org.xml.sax")
        openPackage("java.base", "jdk.internal.org.xml.sax.helpers")
        openPackage("java.base", "jdk.internal.perf")
        openPackage("java.base", "jdk.internal.platform")
        openPackage("java.base", "jdk.internal.ref")
        openPackage("java.base", "jdk.internal.reflect")
        openPackage("java.base", "jdk.internal.util")
        openPackage("java.base", "jdk.internal.util.jar")
        openPackage("java.base", "jdk.internal.util.xml")
        openPackage("java.base", "jdk.internal.util.xml.impl")
        openPackage("java.base", "jdk.internal.vm")
        openPackage("java.base", "jdk.internal.vm.annotation")
        openPackage("java.base", "java.util.concurrent.atomic")
        openPackage("java.base", "java.io")
        openPackage("java.base", "java.util.zip")
        openPackage("java.base", "java.util.concurrent")
        openPackage("java.base", "sun.security.util")
        openPackage("java.base", "java.lang.invoke")
        openPackage("java.base", "java.lang.ref")
        openPackage("java.base", "java.lang.constant")
        openPackage("java.base", "java.util")
        openPackage("java.base", "java.util.concurrent.locks")
        openPackage("java.management", "javax.management")
        openPackage("java.base", "java.nio.charset")
        openPackage("java.base", "java.util.regex")
        openPackage("java.base", "java.net")
        openPackage("java.base", "sun.util.locale")
        openPackage("java.base", "java.util.stream")
        openPackage("java.base", "java.security")
        openPackage("java.base", "java.time")
        openPackage("java.base", "jdk.internal.access")
        openPackage("java.base", "sun.reflect.annotation")
        openPackage("java.base", "sun.reflect.generics.reflectiveObjects")
        openPackage("java.base", "sun.reflect.generics.factory")
        openPackage("java.base", "sun.reflect.generics.tree")
        openPackage("java.base", "sun.reflect.generics.scope")
        openPackage("java.base", "sun.invoke.util")
        openPackage("java.base", "sun.nio.cs")
        openPackage("java.base", "sun.nio.fs")
        openPackage("java.base", "java.nio")
        openPackage("java.logging", "java.util.logging")
        openPackage("java.base", "java.time.format")
        openPackage("java.base", "java.time.zone")
        openPackage("java.base", "java.time.temporal")
        openPackage("java.base", "java.text")
        openPackage("java.base", "sun.util.calendar")
        openPackage("java.base", "sun.net.www.protocol.jar")
        openPackage("java.base", "java.util.jar")
        openPackage("java.base", "java.nio.file.attribute")
        openPackage("java.base", "java.util.function")
        openPackage("java.desktop", "java.beans")
        openPackage("java.xml", "com.sun.org.apache.xerces.internal.impl.xs")
        openPackage("java.base", "java.math")
        exportPackage("java.base", "sun.util.locale")
        exportPackage("java.base", "jdk.internal.misc")
        exportPackage("java.base", "jdk.internal.reflect")
        exportPackage("java.base", "sun.nio.cs")
        exportPackage("java.xml", "com.sun.org.apache.xerces.internal.impl.xs.util")
        add("--illegal-access=warn")
    }
}

fun MutableList<String>.openPackage(module: String, pakage: String) {
    add("--add-opens")
    add("$module/$pakage=ALL-UNNAMED")
}

fun MutableList<String>.exportPackage(module: String, pakage: String) {
    add("--add-exports")
    add("$module/$pakage=ALL-UNNAMED")
}

fun JavaExec.addEnvIfExists(envName: String, path: String) {
    val file = File(path)
    if (!file.exists()) {
        println("Not found $envName at $path")
        return
    }

    environment(envName, file.absolutePath)
}
