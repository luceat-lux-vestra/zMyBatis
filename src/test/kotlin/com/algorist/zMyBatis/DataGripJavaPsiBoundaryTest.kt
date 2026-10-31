package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataGripJavaPsiBoundaryTest {
    @Test
    fun `java psi is an optional capability outside the DataGrip safe path`() {
        val pluginXml = source("src/main/resources/META-INF/plugin.xml")
        val javaDescriptor = source("src/main/resources/META-INF/zMyBatis-withJava.xml")
        val analyzer = source("src/main/kotlin/com/algorist/zMyBatis/MyBatisContextAnalyzer.kt")
        val admission = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapActionContextAdmissionCoordinator.kt",
        )
        val selection = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapPreparationSourceSelectionAdapter.kt",
        )

        assertTrue(
            pluginXml.contains(
                "<depends optional=\"true\" config-file=\"zMyBatis-withJava.xml\">com.intellij.java</depends>",
            ),
        )
        assertTrue(pluginXml.contains("interface=\"com.algorist.zMyBatis.host.JavaPsiCapability\""))
        assertTrue(pluginXml.contains("dynamic=\"true\""))
        assertTrue(
            javaDescriptor.contains(
                "implementation=\"com.algorist.zMyBatis.host.IntellijJavaPsiCapability\"",
            ),
        )

        listOf(analyzer, admission, selection).forEach { hostNeutralSource ->
            listOf(
                "PsiJavaFile",
                "PsiMethod",
                "JavaPsiFacade",
                "JavaAnnotationSourceCaptureAdapter",
                "XmlMapperMethodCaptureAdapter",
            ).forEach { javaOnlySymbol ->
                assertFalse(
                    "DataGrip-safe shipping path must not link Java-only symbol: $javaOnlySymbol",
                    hostNeutralSource.contains(javaOnlySymbol),
                )
            }
        }
    }

    @Test
    fun `missing java capability preserves XML statement-only fallback`() {
        val selection = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapPreparationSourceSelectionAdapter.kt",
        )
        val coordinator = source(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapActionContextAdmissionCoordinator.kt",
        )

        assertTrue(selection.contains("JavaPsiCapabilities.current()"))
        assertTrue(selection.contains("XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE"))
        assertTrue(coordinator.contains("XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE"))
        assertTrue(coordinator.contains("this == XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE"))
    }

    @Test
    fun `java implementation owns java psi and existing capture adapters`() {
        val implementation = source(
            "src/main/kotlin/com/algorist/zMyBatis/host/IntellijJavaPsiCapability.kt",
        )

        assertTrue(implementation.contains("PsiJavaFile"))
        assertTrue(implementation.contains("PsiMethod"))
        assertTrue(implementation.contains("DumbService.isDumb(project)"))
        assertTrue(implementation.contains("JavaAnnotationSourceCaptureAdapter.capture"))
        assertTrue(implementation.contains("XmlMapperMethodCaptureAdapter.capture"))
    }

    private fun source(relativePath: String): String =
        Files.readString(repositoryRoot().resolve(relativePath))

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("Repository root not found")
        }
    }
}
