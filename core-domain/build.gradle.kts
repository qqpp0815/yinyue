plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // 开源中文繁简转换（纯 Java 实现，含词级转换）
    implementation("com.github.houbb:opencc4j:1.8.0")

    testImplementation("junit:junit:4.13.2")
}
