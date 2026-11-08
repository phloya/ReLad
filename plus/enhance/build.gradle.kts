import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// Re:Lad scan enhancement: upscales and denoises pages with a neural network on the phone's GPU
// (ncnn + Vulkan, falls back to the CPU). See plus/DECISIONS.md, "Улучшение сканов".
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

// ncnn (BSD-3-Clause, Tencent) prebuilt for Android with Vulkan, downloaded once and checked by hash.
// Not committed: 40 MB of static libraries for all ABIs.
// 20260526, not the newer 20261020: that one is built with a newer NDK and does not link with
// NDK 29, the version AGP uses (missing std::__hash_memory in libc++).
val ncnnVersion = "20260526"
val ncnnSha256 = "26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804"
val ncnnDir = layout.buildDirectory.dir("ncnn")

abstract class DownloadNcnn : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) { "ncnn archive hash mismatch: $actual" }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = out.resolve(entry.name).canonicalFile
                check(target.path.startsWith(out.canonicalPath)) { "Bad path in ncnn archive: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.also {
                        it.parentFile.mkdirs()
                    }.writeBytes(zip.readBytes())
                }
            }
        }
    }
}

val downloadNcnn = tasks.register<DownloadNcnn>("downloadNcnn") {
    url.set("https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip")
    sha256.set(ncnnSha256)
    outputDir.set(ncnnDir)
}

android {
    namespace = "plus.enhance"

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DNCNN_ROOT=${ncnnDir.get().asFile.path}/ncnn-$ncnnVersion-android-vulkan",
                    "-DANDROID_STL=c++_static",
                )
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(downloadNcnn)
}

dependencies {
    implementation(projects.plus.core)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
