import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * AGP 9 compiles Kotlin itself (built-in Kotlin), so the `org.jetbrains.kotlin.android`
 * plugin must NOT be applied and the `kotlin { }` extension is off limits --
 * touching it fails with "Using kotlin.sourceSets DSL ... is not allowed with
 * built-in Kotlin". Compiler options are therefore set on the tasks directly.
 *
 * AGP 9 also moved the `defaultConfig { }` / `lint { }` lambda overloads onto the
 * concrete Application/Library extensions, so from [CommonExtension] we go
 * through their getters.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.compileSdk = AndroidSdk.COMPILE
    commonExtension.defaultConfig.minSdk = AndroidSdk.MIN

    commonExtension.compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    commonExtension.lint.apply {
        warningsAsErrors = true
        abortOnError = true
        // Dependency freshness is Dependabot's job, not the build's.
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
        // Sideloaded to one arm64 phone; ChromeOS x86_64 is out of scope.
        disable += "ChromeOsAbiSupport"
        // Fires on a permissive trust manager inside JGit's own jar, which
        // backs its `http.sslVerify=false` option. The git transport here is
        // SSH only and never sets that config, so the class is unreachable.
        disable += "TrustAllX509TrustManager"
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(JVM_TOOLCHAIN))
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
}
