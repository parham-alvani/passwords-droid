import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure-JVM modules. Deliberately without the Android plugin so the compiler
 * makes it impossible to reach for a `Context` from the parser or the sync
 * planner -- and so their tests run in seconds rather than on an emulator.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(JVM_TOOLCHAIN)
                compilerOptions {
                    allWarningsAsErrors.set(true)
                }
            }

            tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
                useJUnit()
            }
        }
    }
}
