package momoi.plugin.apkmixin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixinPluginTest {

    abstract class FakeR8Task : DefaultTask() {
        @get:OutputDirectory
        abstract val outputDex: DirectoryProperty
    }

    abstract class FakeMergeDexTask : DefaultTask() {
        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty
    }

    @Test
    fun compareBuildToolsVersionSatisfiesStrictComparatorTransitivityAndNumericPrecedence() {
        val samples = listOf(
            "",
            "android-4.4W",
            "android-5.0",
            "5_custom",
            "9",
            "9.0.0",
            "9.0.0-alpha1",
            "9.0.0-alpha10",
            "9.0.0-alpha2",
            "9.0.0-rc1",
            "33.0.2",
            "34.0.0",
            "35",
            "35.0",
            "35.0.0",
            "35.0.0-rc2",
            "35.0.0-rc10",
            "35.0.0-1",
            "35.0.0-5_custom",
            "35.0.0-9",
            "35.0.0-35",
            "35.0.0-50000000000000000000",
            "35.foo",
            "36.0.0",
            "100.0.0",
            "0035.0.0",
            "custom",
            "z-99.0.0",
        )

        // Reflexivity & Antisymmetry
        for (a in samples) {
            assertEquals(0, MixinPlugin.compareBuildToolsVersion(a, a), "Reflexivity failed for '$a'")
            for (b in samples) {
                val ab = Integer.signum(MixinPlugin.compareBuildToolsVersion(a, b))
                val ba = Integer.signum(MixinPlugin.compareBuildToolsVersion(b, a))
                assertEquals(-ab, ba, "Antisymmetry failed for '$a' vs '$b'")
            }
        }

        // Full Transitivity across all N^3 triples (would catch cycles like "9" < "35" < "5_custom" < "9")
        for (a in samples) {
            for (b in samples) {
                val ab = MixinPlugin.compareBuildToolsVersion(a, b)
                if (ab >= 0) continue
                for (c in samples) {
                    val bc = MixinPlugin.compareBuildToolsVersion(b, c)
                    if (bc >= 0) continue
                    val ac = MixinPlugin.compareBuildToolsVersion(a, c)
                    assertTrue(
                        ac < 0,
                        "Transitivity violated: '$a' < '$b' and '$b' < '$c' but compare('$a', '$c') = $ac"
                    )
                }
            }
        }

        // Key ordering expectations (descending selects newest stable numeric version first)
        assertTrue(MixinPlugin.compareBuildToolsVersion("android-4.4W", "35.0.0") < 0)
        assertTrue(MixinPlugin.compareBuildToolsVersion("35.0.0", "36.0.0") < 0)
        assertTrue(MixinPlugin.compareBuildToolsVersion("5_custom", "9") < 0)
        assertTrue(MixinPlugin.compareBuildToolsVersion("9", "35") < 0)
        assertTrue(MixinPlugin.compareBuildToolsVersion("35.0.0-rc2", "35.0.0-rc10") < 0)
        assertTrue(MixinPlugin.compareBuildToolsVersion("35.0.0-rc10", "35.0.0") < 0)

        val sortedDesc = samples.sortedWith { a, b -> MixinPlugin.compareBuildToolsVersion(b, a) }
        assertEquals("100.0.0", sortedDesc.first())
        assertTrue(
            sortedDesc.indexOf("36.0.0") < sortedDesc.indexOf("35.0.0") &&
                sortedDesc.indexOf("35.0.0") < sortedDesc.indexOf("android-4.4W"),
            "Numeric build-tools must rank strictly above non-numeric dirs: $sortedDesc"
        )
    }

    @Test
    fun mixinApkTaskWiresToMergeDexReleaseWhenR8IsAbsentOrDisabled() {
        val tempDir = Files.createTempDirectory("mixin-plugin-mergedex-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
            project.plugins.apply(MixinPlugin::class.java)
            project.extensions.getByType(ApkMixinExtension::class.java).targetApk = "source.apk"
            val customMergeDir = File(tempDir, "build/intermediates/dex/release/mergeDexRelease")
            project.tasks.register("mergeDexRelease", FakeMergeDexTask::class.java) {
                it.outputDir.set(customMergeDir)
            }

            assertEquals("mergeDexRelease", MixinPlugin.resolveDexTaskName(project, "Release"))
            val mixinTask = project.tasks.getByName("MixinApk") as MixinApkTask
            val deps = mixinTask.taskDependencies.getDependencies(mixinTask).map { it.name }.toSet()
            assertTrue("mergeDexRelease" in deps, "Expected MixinApk to depend on mergeDexRelease, got $deps")
            assertEquals(customMergeDir.canonicalFile, mixinTask.mixinAppDexDir.get().asFile.canonicalFile)

            // Disabled minifyReleaseWithR8 must still fall back to mergeDexRelease
            project.tasks.register("minifyReleaseWithR8", FakeR8Task::class.java) {
                it.enabled = false
                it.outputDex.set(File(tempDir, "build/intermediates/dex/release/minifyReleaseWithR8"))
            }
            assertEquals("mergeDexRelease", MixinPlugin.resolveDexTaskName(project, "Release"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun mixinApkTaskWiresToMinifyReleaseWithR8WhenR8IsEnabled() {
        val tempDir = Files.createTempDirectory("mixin-plugin-r8-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
            project.plugins.apply(MixinPlugin::class.java)
            project.extensions.getByType(ApkMixinExtension::class.java).targetApk = "source.apk"
            val r8OutDir = File(tempDir, "build/intermediates/dex/release/minifyReleaseWithR8")
            project.tasks.register("minifyReleaseWithR8", FakeR8Task::class.java) {
                it.outputDex.set(r8OutDir)
            }

            assertEquals("minifyReleaseWithR8", MixinPlugin.resolveDexTaskName(project, "Release"))
            val mixinTask = project.tasks.getByName("MixinApk") as MixinApkTask
            val deps = mixinTask.taskDependencies.getDependencies(mixinTask).map { it.name }.toSet()
            assertTrue("minifyReleaseWithR8" in deps, "Expected MixinApk to depend on minifyReleaseWithR8, got $deps")
            assertEquals(r8OutDir.canonicalFile, mixinTask.mixinAppDexDir.get().asFile.canonicalFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
