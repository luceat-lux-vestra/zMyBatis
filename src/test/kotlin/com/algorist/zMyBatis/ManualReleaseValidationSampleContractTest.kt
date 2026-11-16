package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualReleaseValidationSampleContractTest {

    private val root = repositoryRoot()
    private val sample = root.resolve("samples/manual-release-validation")

    @Test
    fun `manual release sample remains minimal PostgreSQL MyBatis 3_6_0 fixture`() {
        val pom = source("pom.xml")
        val compose = source("compose.yaml")
        val readme = source("README.md")

        assertTrue(pom.contains("<artifactId>mybatis</artifactId>"))
        assertTrue(pom.contains("<version>3.6.0</version>"))
        assertFalse("manual sample must not become an unrelated Spring Boot app", pom.contains("spring-boot"))
        assertTrue(compose.contains("postgres:17-alpine"))
        assertTrue(compose.contains("POSTGRES_DB: zmybatis"))
        assertFalse(
            "historical MySQL/H2 fixture must not define the release target",
            compose.contains("mysql") || compose.contains("h2"),
        )
        assertTrue(readme.contains("This is **not** a replacement for automated tests."))
        assertTrue(readme.contains("#509"))
    }

    @Test
    fun `manual Java fixtures compile against the maintained MyBatis runtime`() {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "manual release sample validation requires a full JDK"
        }
        Class.forName("org.apache.ibatis.annotations.Select")
        val testRuntimeClasspath = System.getProperty("java.class.path")
            .takeIf { it.isNotBlank() }
            ?: error("test runtime classpath is unavailable")
        val javaFiles = Files.walk(sample.resolve("src/main/java")).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".java") }
                .map(Path::toString)
                .sorted()
                .toList()
        }
        val output = Files.createTempDirectory("zmybatis-manual-sample-javac")
        try {
            val args = buildList {
                add("-classpath")
                add(testRuntimeClasspath)
                add("-d")
                add(output.toString())
                addAll(javaFiles)
            }
            assertEquals(
                "manual release Java fixtures must compile against the maintained MyBatis runtime",
                0,
                compiler.run(null, null, null, *args.toTypedArray()),
            )
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `manual mapper fixtures are well formed XML without resolving external DTDs`() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

        Files.walk(sample.resolve("src/main/resources/mappers")).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".xml") }
                .sorted()
                .forEach { path ->
                    Files.newInputStream(path).use { input ->
                        factory.newDocumentBuilder().parse(input)
                    }
                }
        }
    }

    @Test
    fun `manual checklist pins all maintained release acceptance families`() {
        val readme = source("README.md")
        val requiredIds = buildList {
            addAll((1..14).map { "M%02d".format(it) })
            addAll((1..7).map { "J%02d".format(it) })
            addAll((1..8).map { "U%02d".format(it) })
            addAll((1..7).map { "T%02d".format(it) })
            addAll(listOf("R01", "R02", "R03", "R04", "R05"))
        }

        requiredIds.forEach { id ->
            assertTrue("manual release checklist must retain case $id", readme.contains(id))
        }
        assertTrue(readme.contains("#509 disposition:  RESOLVED / BLOCKED"))
        assertTrue(readme.contains("#510 disposition:  RESOLVED / BLOCKED"))
        assertTrue(readme.contains("Release decision:  GO / NO-GO"))
        assertTrue(readme.contains("Wrong-datasource or wrong-schema execution is a **release blocker**"))
        assertTrue(readme.contains("stable UUID keeps the same target valid"))
    }

    @Test
    fun `manual XML fixtures reuse canonical shipping and refusal shapes`() {
        val xml = source("src/main/resources/mappers/XmlReleaseValidationMapper.xml")
        val product = source("src/main/resources/mappers/ProductMapper.xml")
        val pendingChoose = source("src/main/resources/mappers/PendingChooseMapper.xml")
        val pendingBind = source("src/main/resources/mappers/PendingBindMapper.xml")
        val pendingResultMap = source("src/main/resources/mappers/PendingResultMapMapper.xml")
        val pendingResultType = source("src/main/resources/mappers/PendingResultTypeMapper.xml")
        val pendingParameterType = source("src/main/resources/mappers/PendingParameterTypeMapper.xml")

        assertTrue(xml.contains("SELECT 7 AS result_value"))
        assertTrue(xml.contains("#{value,jdbcType=BIGINT}::BIGINT AS result_value"))
        assertTrue(xml.contains("<if test=\"value > 0\">"))
        assertTrue(xml.contains("<foreach collection=\"ids\" item=\"id\""))
        assertTrue(xml.contains("SELECT \${value} AS result_value"))
        assertTrue(xml.contains("databaseId=\"PostgreSQL\""))
        assertTrue(xml.contains("jdbcType=VARCHAR"))
        assertTrue(xml.contains("#{left,jdbcType=BIGINT}"))
        assertTrue(xml.contains("#{right,jdbcType=BIGINT}"))
        assertTrue(product.contains("<include refid=\"fixture.SharedFragments.productColumns\"/>"))
        assertFalse(
            "passing XML fixture must stay inside current class-loading preflight",
            xml.contains("resultType=") ||
                xml.contains("resultMap=") ||
                xml.contains("parameterType="),
        )
        assertFalse(
            "passing Product fixture must stay inside current class-loading preflight",
            product.contains("<resultMap") ||
                product.contains("resultMap=") ||
                product.contains("resultType=") ||
                product.contains("parameterType="),
        )
        listOf(pendingChoose, pendingBind).forEach { pending ->
            assertFalse(
                "dynamic-tag reproduction must not be masked by class-loading refusal",
                pending.contains("resultType=") ||
                    pending.contains("resultMap=") ||
                    pending.contains("parameterType="),
            )
        }
        assertTrue(pendingChoose.contains("<choose>"))
        assertTrue(pendingChoose.contains("R01: NOT a release PASS case while #509 is open"))
        assertTrue(pendingBind.contains("<bind name=\"pattern\""))
        assertTrue(pendingBind.contains("R02: NOT a release PASS case while #509 is open"))
        assertTrue(pendingResultMap.contains("<resultMap"))
        assertTrue(pendingResultMap.contains("resultMap=\"ProductResultMap\""))
        assertTrue(pendingResultMap.contains("R03: NOT a release PASS case while #510 is open"))
        assertTrue(pendingResultType.contains("resultType=\"fixture.Product\""))
        assertTrue(pendingResultType.contains("R04: NOT a release PASS case while #510 is open"))
        assertTrue(pendingParameterType.contains("parameterType=\"long\""))
        assertTrue(pendingParameterType.contains("R05: NOT a release PASS case while #510 is open"))
    }

    @Test
    fun `manual Java fixtures cover direct annotations and explicit provider refusal`() {
        val direct = source("src/main/java/fixture/AnnotationReleaseValidationMapper.java")
        val provider = source("src/main/java/fixture/ProviderReleaseValidationMapper.java")

        assertTrue(direct.contains("@Select(\"SELECT 7 AS result_value\")"))
        assertTrue(
            direct.contains("@Select(\"SELECT #{value,jdbcType=BIGINT}::BIGINT AS result_value\")"),
        )
        assertTrue(direct.contains("@Select(\"SELECT \${value} AS result_value\")"))
        assertTrue(direct.contains("@Insert("))
        assertTrue(direct.contains("@Update("))
        assertTrue(direct.contains("@Delete("))
        assertTrue(provider.contains("@SelectProvider"))
        assertTrue(
            source("README.md").contains("foreach item bindings are MyBatis additional parameters"),
        )
    }

    private fun source(relativePath: String): String =
        Files.readString(sample.resolve(relativePath))

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
