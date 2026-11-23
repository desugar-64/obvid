// Copyright 2026, Serhii Yaremych (desugar-64) and the Obvid project contributors
// SPDX-License-Identifier: Apache-2.0

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.net.URLClassLoader
import java.util.Properties
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  `maven-publish`
}

group = "dev.serhiiyaremych"
version = "0.1.0"

android {
  namespace = "dev.serhiiyaremych.obvid"
  compileSdk { version = release(37) { minorApiLevel = 1 } }
  ndkVersion = "30.0.16248370"
  defaultConfig {
    minSdk = 24
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
    ndk { abiFilters += "arm64-v8a" }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
  }
  buildTypes {
    // Release, plus the test and benchmark surface (`ObvidTesting`, `DropShadowDrawHook`, the Rust crate's
    // `testing` feature). :benchmark selects it; apps get `release`, which has none of it.
    create("benchmark") {
      initWith(getByName("release"))
      matchingFallbacks += "release"
      consumerProguardFiles("testing-consumer-rules.pro")
    }
    getByName("debug") { consumerProguardFiles("testing-consumer-rules.pro") }
  }
  // The shipping `.so` (no test exports) in release; the `testing` one, and the Kotlin of `src/testing`, in debug
  // (the :obvid device tests) and benchmark.
  sourceSets.getByName("release").jniLibs.directories.add("build/rustJniLibs/plain")
  for (buildType in listOf("debug", "benchmark")) {
    sourceSets.getByName(buildType) {
      jniLibs.directories.add("build/rustJniLibs/testing")
      kotlin.directories.add("src/testing/kotlin")
    }
  }
  // Shapes, Compose's reference bitmap and mask helpers shared with :benchmark and :sample.
  sourceSets.getByName("androidTest").kotlin.directories.add("../fixtures/src/main/kotlin")
  publishing {
    singleVariant("release") {
      withSourcesJar()
      withJavadocJar()
    }
  }
}

kotlin {
  jvmToolchain(17)
  explicitApi()
}

// Kotlin's ABI dump engine (the one behind `kotlin { abiValidation() }`, which does not see AGP's built-in Kotlin
// compilations yet), run on the release classes.
val abiTools = configurations.create("abiTools") { isCanBeConsumed = false }

dependencies {
  abiTools("org.jetbrains.kotlin:abi-tools:${libs.versions.kotlin.get()}")
  // The public API takes `Modifier`, `Shape` and `Shadow`: consumers compile against these.
  api(platform(libs.androidx.compose.bom))
  api(libs.androidx.compose.ui)
  api(libs.androidx.compose.ui.graphics)
  // `@RestrictTo` on the test and benchmark surface; declared, not taken from Compose's transitive dependencies.
  implementation(libs.androidx.annotation)
  // For the store's maps (`androidx.collection`); the version Compose already brings at runtime.
  implementation(libs.androidx.collection)

  // On-device exactness tests (Compose's shadow bitmap vs Canvas mask + the blur port).
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.graphics)
  androidTestImplementation(libs.androidx.compose.foundation)
  androidTestImplementation(libs.androidx.compose.material3)
  androidTestImplementation(libs.androidx.graphics.shapes)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.junit)
  // Screen tests (createAndroidComposeRule, captureToImage); the manifest brings ComponentActivity.
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.espresso.core)
}

// Always a release Rust build: benchmarks measure optimized code in every Gradle variant. Two builds of the crate:
// "plain" (the shipping exports) and "testing" (Cargo feature `testing`, in its own target dir so that switching
// features does not rebuild the other one).
//
// Gradle runs `cargo build --release --target aarch64-linux-android` itself, linking with the NDK's clang for minSdk
// (the crate is `no_std` with no C dependencies, so nothing else from the NDK is needed). `rust/rust-toolchain.toml`
// pins the compiler and the Android target; rustup installs both on first use. Cargo is looked up in `cargo.dir`
// (local.properties), then `$CARGO_HOME/bin`, `~/.cargo/bin` and `PATH`, so a build started from the IDE does not
// depend on the shell's `PATH`.
//
// Cargo builds the `.so` with symbols and line tables and a GNU build ID (`rust/.cargo/config.toml`); the packaged copy
// in `build/rustJniLibs/<flavor>` is that file through `llvm-objcopy --strip-all`, which keeps the build ID and
// `.eh_frame`. The unstripped plain (release) library is `build/outputs/native-debug-symbols/arm64-v8a/libobvid.so`,
// the testing one `build/intermediates/rustUnstripped/testing/arm64-v8a/libobvid.so`. To symbolize a crash:
// `adb logcat -d | $NDK/ndk-stack -sym build/outputs/native-debug-symbols/arm64-v8a` (or `-i tombstone_NN`); the
// build ID that `llvm-readelf -n` prints for that file must match the one in the tombstone's frames.
val ndkBin: Provider<String> =
  androidComponents.sdkComponents.ndkDirectory.map { ndk ->
    val prebuilt = ndk.dir("toolchains/llvm/prebuilt").asFile
    val hosts = prebuilt.listFiles()?.filter { it.isDirectory }.orEmpty()
    hosts.singleOrNull()?.resolve("bin")?.absolutePath
      ?: throw GradleException("Expected one host toolchain in $prebuilt, found ${hosts.map { it.name }}")
  }
val isWindows = System.getProperty("os.name").startsWith("Windows")
val ndkLinker: Provider<String> =
  ndkBin.map { "$it/aarch64-linux-android${android.defaultConfig.minSdk}-clang" + if (isWindows) ".cmd" else "" }
val cargoExecutable: Provider<String> =
  providers.of(CargoLocation::class) {
    val localProperties = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    parameters.cargoDir.set(
      localProperties.asText.map { text -> Properties().apply { load(text.reader()) }.getProperty("cargo.dir") ?: "" }
    )
  }

// One cargo at a time. On a fresh rustup, each cargo run installs the pinned toolchain on first use, and two parallel
// installs collide in `~/.rustup/downloads` ("could not rename 'downloaded' file"), failing the first build.
val cargoLock = gradle.sharedServices.registerIfAbsent("obvidCargo", CargoLock::class) { maxParallelUsages.set(1) }

fun cargoBuild(name: String, flavor: String, vararg features: String): TaskProvider<StripLibrary> {
  val crateDir = layout.projectDirectory.dir("rust")
  val unstrippedDir =
    if (flavor == "plain") layout.buildDirectory.dir("outputs/native-debug-symbols")
    else layout.buildDirectory.dir("intermediates/rustUnstripped/$flavor")
  val build =
    tasks.register<CargoBuild>("${name}Unstripped") {
      usesService(cargoLock)
      sources.from(
        crateDir.dir("src"), crateDir.file("Cargo.toml"), crateDir.file("Cargo.lock"),
        crateDir.file(".cargo/config.toml"), crateDir.file("rust-toolchain.toml"),
      )
      this.crateDir.set(crateDir)
      targetDir.set(if (flavor == "plain") crateDir.dir("target") else crateDir.dir("target/$flavor"))
      cargo.set(cargoExecutable)
      linker.set(ndkLinker)
      this.features.set(features.toList())
      library.set(unstrippedDir.map { it.file("arm64-v8a/libobvid.so") })
    }
  val codegen =
    tasks.register<CheckBox3Codegen>("${name}Codegen") {
      library.set(build.flatMap { it.library })
      llvmBin.set(ndkBin)
      report.set(layout.buildDirectory.file("intermediates/rustCodegen/$flavor/box3-steps.txt"))
    }
  return tasks.register<StripLibrary>(name) {
    dependsOn(codegen)
    library.set(build.flatMap { it.library })
    llvmBin.set(ndkBin)
    stripped.set(layout.buildDirectory.file("rustJniLibs/$flavor/arm64-v8a/libobvid.so"))
  }
}

val cargoBuildPlain = cargoBuild("cargoBuildPlain", "plain")
val cargoBuildTesting = cargoBuild("cargoBuildTesting", "testing", "--features", "testing")

tasks.configureEach {
  when (name) {
    "preReleaseBuild" -> dependsOn(cargoBuildPlain)
    "preDebugBuild", "preBenchmarkBuild" -> dependsOn(cargoBuildTesting)
  }
}

// The public API of the release build, in Kotlin's ABI dump format: `api/obvid.api`. `checkKotlinAbi` (part of `check`)
// fails when the API differs from it; `updateKotlinAbi` rewrites it.
val dumpKotlinAbi =
  tasks.register<DumpKotlinAbi>("dumpKotlinAbi") {
    toolClasspath.from(abiTools)
    dump.set(layout.buildDirectory.file("intermediates/kotlinAbi/obvid.api"))
  }
androidComponents.onVariants(androidComponents.selector().withName("release")) { variant ->
  variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
    .use(dumpKotlinAbi)
    .toGet(ScopedArtifact.CLASSES, DumpKotlinAbi::jars, DumpKotlinAbi::directories)
}
val referenceAbi = layout.projectDirectory.file("api/obvid.api")
tasks.register<Copy>("updateKotlinAbi") {
  from(dumpKotlinAbi.flatMap { it.dump })
  into(referenceAbi.asFile.parentFile)
}
val checkKotlinAbi =
  tasks.register<CheckKotlinAbi>("checkKotlinAbi") {
    actual.set(dumpKotlinAbi.flatMap { it.dump })
    reference.set(referenceAbi)
  }
tasks.named("check") { dependsOn(checkKotlinAbi) }

// The release AAR must ship the baseline profile (`src/main/baseline-prof.txt`, which AGP packs at the AAR's root) and
// the stripped Rust library; part of `check`.
val verifyReleaseAar =
  tasks.register<VerifyAar>("verifyReleaseAar") {
    requiredEntries.set(listOf("baseline-prof.txt", "jni/arm64-v8a/libobvid.so"))
    report.set(layout.buildDirectory.file("intermediates/verifyReleaseAar/entries.txt"))
  }
androidComponents.onVariants(androidComponents.selector().withName("release")) { variant ->
  verifyReleaseAar.configure { aar.set(variant.artifacts.get(SingleArtifact.AAR)) }
}
tasks.named("check") { dependsOn(verifyReleaseAar) }

// The first executable `cargo` in `cargo.dir` (local.properties), `$CARGO_HOME/bin`, `~/.cargo/bin`, then `PATH`.
// Absent when there is none; `CargoBuild` fails with an install hint then.
abstract class CargoLocation : ValueSource<String, CargoLocation.Params> {
  interface Params : ValueSourceParameters {
    val cargoDir: Property<String>
  }

  override fun obtain(): String? {
    val exe = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
    val dirs = buildList {
      parameters.cargoDir.orNull?.takeIf { it.isNotBlank() }?.let(::add)
      System.getenv("CARGO_HOME")?.let { add("$it/bin") }
      add("${System.getProperty("user.home")}/.cargo/bin")
      System.getenv("PATH")?.split(File.pathSeparator)?.let(::addAll)
    }
    return dirs.map { File(it, exe) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
  }
}

// No state: its `maxParallelUsages` of 1 is what keeps the `CargoBuild` tasks from running at the same time.
abstract class CargoLock : BuildService<BuildServiceParameters.None>

// `cargo build --release --target aarch64-linux-android [features]`, then a copy of the built `.so` to `library`.
abstract class CargoBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
  @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
  @get:Internal abstract val crateDir: DirectoryProperty
  @get:Internal abstract val targetDir: DirectoryProperty
  @get:Input @get:Optional abstract val cargo: Property<String>
  @get:Input abstract val linker: Property<String>
  @get:Input abstract val features: ListProperty<String>
  @get:OutputFile abstract val library: RegularFileProperty

  private companion object {
    const val INSTALL_RUST =
      "install rustup from https://rustup.rs; the toolchain in obvid/rust/rust-toolchain.toml installs itself"
  }

  @TaskAction
  fun build() {
    val cargo = cargo.orNull ?: throw GradleException(
      "cargo not found (looked in cargo.dir from local.properties, \$CARGO_HOME/bin, ~/.cargo/bin and PATH): " +
        "$INSTALL_RUST. If cargo is installed elsewhere, set cargo.dir=<its bin directory> in local.properties."
    )
    val target = "aarch64-linux-android"
    val result = exec.exec {
      workingDir(crateDir)
      executable(cargo)
      args("build", "--release", "--target", target, *features.get().toTypedArray())
      // rustup's proxies and the toolchain they select are found next to `cargo`, also when the IDE's PATH lacks it.
      environment("PATH", File(cargo).parent + File.pathSeparator + (System.getenv("PATH") ?: ""))
      environment("CARGO_TARGET_DIR", targetDir.get().asFile.absolutePath)
      environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.get())
      isIgnoreExitValue = true
    }
    if (result.exitValue != 0) {
      throw GradleException(
        "cargo build failed (exit ${result.exitValue}); see the output above. If the target is missing or the " +
          "toolchain does not match obvid/rust/rust-toolchain.toml: $INSTALL_RUST ($cargo may not be rustup's)."
      )
    }
    targetDir.get().file("$target/release/libobvid.so").asFile.copyTo(library.get().asFile, overwrite = true)
  }
}

abstract class DumpKotlinAbi @Inject constructor(
  private val fs: FileSystemOperations,
  private val archives: ArchiveOperations,
) : DefaultTask() {
  @get:Classpath abstract val toolClasspath: ConfigurableFileCollection
  @get:Classpath abstract val jars: ListProperty<RegularFile>
  @get:Classpath abstract val directories: ListProperty<Directory>
  @get:OutputFile abstract val dump: RegularFileProperty

  @TaskAction
  fun dump() {
    val urls = toolClasspath.files.map { it.toURI().toURL() }.toTypedArray()
    URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
      val toolsClass = loader.loadClass("org.jetbrains.kotlin.abi.tools.AbiTools")
      val filtersClass = loader.loadClass("org.jetbrains.kotlin.abi.tools.AbiFilters")
      val companion = toolsClass.getField("Companion").get(null)
      val tools = companion.javaClass.getMethod("getInstance", ClassLoader::class.java).invoke(companion, loader)
      val none = emptySet<String>()
      val filters = filtersClass.getConstructor(Set::class.java, Set::class.java, Set::class.java, Set::class.java)
        .newInstance(none, none, none, none)
      // The dump reads `.class` files: the directories' own, and the jars' unpacked into the task's temporary dir.
      val unpacked = temporaryDir.also { it.deleteRecursively() }
      for ((i, jar) in jars.get().withIndex()) {
        fs.copy { from(archives.zipTree(jar)); into(unpacked.resolve("$i")) }
      }
      val classes = (directories.get().map { it.asFile } + unpacked)
        .flatMap { root -> root.walk().filter { it.isFile && it.name.endsWith(".class") }.toList() }
        .sortedBy { it.path }
      val out = StringBuilder()
      toolsClass.getMethod("printJvmDump", Appendable::class.java, Iterable::class.java, filtersClass)
        .invoke(tools, out, classes, filters)
      dump.get().asFile.writeText(out.toString())
    }
  }
}

abstract class VerifyAar : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aar: RegularFileProperty
  @get:Input abstract val requiredEntries: ListProperty<String>
  @get:OutputFile abstract val report: RegularFileProperty

  @TaskAction
  fun verify() {
    val file = aar.get().asFile
    val entries = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toSortedSet() }
    val missing = requiredEntries.get().filterNot { it in entries }
    if (missing.isNotEmpty()) throw GradleException("$file lacks ${missing.joinToString()}")
    report.get().asFile.writeText(entries.joinToString("\n", postfix = "\n"))
  }
}

abstract class CheckKotlinAbi : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val actual: RegularFileProperty
  @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val reference: RegularFileProperty

  @TaskAction
  fun check() {
    val reference = reference.get().asFile
    if (!reference.isFile) throw GradleException("No API dump at $reference; run ./gradlew :obvid:updateKotlinAbi.")
    val expected = reference.readLines()
    val current = actual.get().asFile.readLines()
    if (expected == current) return
    val diff = expected.filterNot { it in current }.map { "- $it" } + current.filterNot { it in expected }.map { "+ $it" }
    throw GradleException(
      "The public API differs from $reference (- dump, + now):\n" + diff.joinToString("\n") +
        "\nIf the change is intended, run ./gradlew :obvid:updateKotlinAbi and commit api/obvid.api."
    )
  }
}

// `llvm-objcopy --strip-all`: drops symbols and debug info, keeps the GNU build ID and `.eh_frame`.
abstract class StripLibrary @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val library: RegularFileProperty
  @get:Input abstract val llvmBin: Property<String>
  @get:OutputFile abstract val stripped: RegularFileProperty

  @TaskAction
  fun strip() {
    exec.exec {
      commandLine(
        "${llvmBin.get()}/llvm-objcopy", "--strip-all",
        library.get().asFile.absolutePath, stripped.get().asFile.absolutePath,
      )
    }
  }
}

// The blur's hot loop, `neon::box3::steps` (`#[inline(never)]`, one symbol per `<EVEN, WIDE>`, 4 in all), must keep
// its NEON widening multiply-accumulate: 8 `smlal`/`smlal2` per step. LLVM can instead scalarize it (lanes moved to
// general registers, widened to 64 bits, one scalar `mul` per lane), which passes every test and runs about 2x slower.
// This fails the build when an instantiation in the unstripped `.so` does not have exactly 8 `smlal`/`smlal2`, or has
// a scalarization marker: a lane-to-scalar move (`fmov xN, dM`, `mov xN, vM.d[i]`) or an `sshll`/`sshll2` to `.2d`.
abstract class CheckBox3Codegen : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val library: RegularFileProperty
  @get:Input abstract val llvmBin: Property<String>
  @get:OutputFile abstract val report: RegularFileProperty

  @TaskAction
  fun check() {
    val so = library.get().asFile.absolutePath
    fun tool(name: String, vararg args: String): List<String> {
      val process = ProcessBuilder("${llvmBin.get()}/$name", *args, so).redirectErrorStream(true).start()
      val out = process.inputStream.bufferedReader().readText()
      if (process.waitFor() != 0) throw GradleException("$name failed on $so:\n$out")
      return out.lines()
    }

    // `llvm-nm` lines are "<address> <type> <name>"; the demangled name (e.g. `steps::<true, false>`) is for messages.
    fun steps(pattern: String, vararg flags: String): Map<String, String> =
      tool("llvm-nm", "--defined-only", *flags).filter { pattern in it }
        .associate { it.substringBefore(' ') to it.split(' ', limit = 3)[2] }
    val mangled = steps("box35steps")
    val names = steps("box3::steps", "--demangle")
    if (mangled.size != 4) throw GradleException("Expected 4 box3::steps symbols in $so, found ${names.values}")

    // `llvm-objdump` prints "<address> <symbol>:" before each function, then "<address>: <mnemonic> <operands>".
    val header = Regex("""^([0-9a-f]+) <.+>:$""")
    val insn = Regex("""^\s*[0-9a-f]+:\s+(\S+)\s*(.*)$""")
    val code = mutableMapOf<String, MutableList<String>>()
    var body = mutableListOf<String>()
    val symbols = "--disassemble-symbols=" + mangled.values.joinToString(",")
    for (line in tool("llvm-objdump", "-d", "--no-show-raw-insn", symbols)) {
      val h = header.find(line)
      if (h != null) body = code.getOrPut(names.getValue(h.groupValues[1])) { mutableListOf() }
      else insn.find(line)?.let { body += "${it.groupValues[1]} ${it.groupValues[2]}".trim() }
    }

    val laneMove = Regex("""^(fmov x\d+, d\d+|u?mov x\d+, v\d+\.d\[\d\])$""")
    val widenTo2d = Regex("""^sshll2? v\d+\.2d,""")
    val lines = mutableListOf<String>()
    val failures = mutableListOf<String>()
    for (fn in names.values.sorted()) {
      val insns = code[fn].orEmpty()
      val smlal = insns.count { it.startsWith("smlal ") || it.startsWith("smlal2 ") }
      val moves = insns.count { laneMove.matches(it) }
      val sshll = insns.count { widenTo2d.containsMatchIn(it) }
      val line = "$fn: $smlal smlal/smlal2, $moves lane-to-scalar moves, $sshll sshll to .2d"
      lines += line
      if (smlal != 8 || moves != 0 || sshll != 0) failures += "  $line (expected 8, 0, 0)"
    }
    if (failures.isNotEmpty()) {
      throw GradleException(
        "box3::steps codegen check failed in $so\n" + failures.joinToString("\n") +
          "\nThe NEON widening multiply-accumulate in the blur's hot loop was scalarized (about 2x slower on " +
          "device); see the comment on `weight` in obvid/rust/src/neon/box3.rs."
      )
    }
    report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
  }
}

publishing {
  // `publishAllPublicationsToPagesRepository` writes a Maven repository to build/maven-repo; the release workflow
  // fills it with the gh-pages branch's `maven/` first, so that the metadata keeps the earlier versions.
  repositories {
    maven {
      name = "pages"
      url = uri(layout.buildDirectory.dir("maven-repo"))
    }
  }
  publications {
    register<MavenPublication>("release") {
      afterEvaluate { from(components["release"]) }
      pom {
        name.set("Obvid")
        description.set("Fast drop-in replacements for Jetpack Compose's dropShadow and innerShadow, with identical pixels.")
        url.set("https://github.com/desugar-64/obvid")
        licenses {
          license {
            name.set("Apache-2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
          }
        }
        developers {
          developer {
            id.set("desugar-64")
            name.set("Serhii Yaremych")
          }
        }
        scm {
          url.set("https://github.com/desugar-64/obvid")
          connection.set("scm:git:https://github.com/desugar-64/obvid.git")
          developerConnection.set("scm:git:ssh://git@github.com/desugar-64/obvid.git")
        }
      }
    }
  }
}
