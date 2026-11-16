package com.algorist.zMyBatis

import java.nio.file.Files
import java.nio.file.Path
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
        assertFalse("historical MySQL/H2 fixture must not define the release target", compose.contains("mysql") || compose.contains("h2"))
        assertTrue(readme.contains("This is **not** a replacement for automated tests."))
        assertTrue(readme.contains("#509"))
    }

    @Test
    fun `manual checklist pins all maintained release acceptance families`() {
        val readme = source("README.md")
        val requiredIds = buildList {
            addAll((1..14).map { "M%02d".format(it) })
            addAll((1..7).map { "J%02d".format(it) })
            addAll((1..8).map { "U%02d".format(it) })
            addAll((1..6).map { "T%02d".format(it) })
            addAll(listOf("R01", "R02"))
        }

        requiredIds.forEach { id ->
            assertTrue("manual release checklist must retain case $id", readme.contains(id))
        }
        assertTrue(readme.contains("Release decision:  GO / NO-GO"))
        assertTrue(readme.contains("Wrong-datasource or wrong-schema execution is a **release blocker**"))
    }

    @Test
    fun `manual XML fixtures reuse canonical shipping and refusal shapes`() {
        val xml = source("src/main/resources/mappers/XmlReleaseValidationMapper.xml")
        val product = source("src/main/resources/mappers/ProductMapper.xml")
        val pending = source("src/main/resources/mappers/PendingDynamicContractMapper.xml")

        assertTrue(xml.contains("SELECT 7 AS result_value"))
        assertTrue(xml.contains("#{value,jdbcType=BIGINT}::BIGINT AS result_value"))
        assertTrue(xml.contains("<if test=\"value > 0\">"))
        assertTrue(xml.contains("<foreach collection=\"ids\" item=\"id\""))
        assertTrue(xml.contains("SELECT ${value} AS result_value"))
        assertTrue(xml.contains("databaseId=\"PostgreSQL\""))
        assertTrue(xml.contains("jdbcType=VARCHAR"))
        assertTrue(xml.contains("#{left,jdbcType=BIGINT}"))
        assertTrue(xml.contains("#{right,jdbcType=BIGINT}"))
        assertTrue(product.contains("<include refid=\"fixture.SharedFragments.productColumns\"/>"))
        assertTrue(pending.contains("<choose>"))
        assertTrue(pending.contains("<bind name=\"pattern\""))
        assertTrue(pending.contains("NOT release PASS cases while #509 is open"))
    }

    @Test
    fun `manual Java fixtures cover direct annotations and explicit provider refusal`() {
        val direct = source("src/main/java/fixture/AnnotationReleaseValidationMapper.java")
        val provider = source("src/main/java/fixture/ProviderReleaseValidationMapper.java")

        assertTrue(direct.contains("@Select(\"SELECT 7 AS result_value\")"))
        assertTrue(direct.contains("@Select(\"SELECT #{value,jdbcType=BIGINT}::BIGINT AS result_value\")"))
        assertTrue(direct.contains("@Select(\"SELECT ${value} AS result_value\")"))
        assertTrue(direct.contains("@Insert("))
        assertTrue(direct.contains("@Update("))
        assertTrue(direct.contains("@Delete("))
        assertTrue(provider.contains("@SelectProvider"))
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
