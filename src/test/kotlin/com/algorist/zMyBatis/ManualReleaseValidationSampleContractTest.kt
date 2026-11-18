package com.algorist.zMyBatis

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
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
        assertTrue(readme.contains("This fixture is **not** a replacement for automated tests."))
        assertTrue(readme.contains("PreparedExecutionPlan"))
        assertTrue(readme.contains("DatabaseSessionPreparedBackend"))
        assertTrue(readme.contains("NativeConsoleSqlTextBackend"))
        assertTrue(readme.contains("no PostgreSQL-BIGINT literalization fallback"))
    }

    @Test
    fun `manual Java fixtures compile against the maintained MyBatis runtime`() {
        val javaFiles = Files.walk(sample.resolve("src/main/java")).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".java") }
                .map(Path::toString)
                .sorted()
                .toList()
        }
        val output = Files.createTempDirectory("zmybatis-manual-sample-javac")
        try {
            val command = buildList {
                add(javacExecutable())
                add("-classpath")
                add(myBatisClasspathEntry().toString())
                add("-d")
                add(output.toString())
                addAll(javaFiles)
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(
                "manual release Java fixtures must compile against the maintained MyBatis runtime: $diagnostics",
                0,
                process.waitFor(),
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
            addAll((1..5).map { "R%02d".format(it) })
        }

        requiredIds.forEach { id ->
            assertTrue("manual release checklist must retain case $id", readme.contains(id))
        }

        assertTrue(readme.contains("#509 disposition:  RESOLVED / BLOCKED"))
        assertTrue(readme.contains("#510 disposition:  RESOLVED / BLOCKED"))
        assertTrue(readme.contains("Release decision:  GO / NO-GO"))
        assertTrue(readme.contains("Wrong-datasource or wrong-schema execution is a **release blocker**"))
        assertTrue(readme.contains("stable UUID keeps the same target valid"))
        assertTrue(readme.contains("exact final P5 candidate ZIP"))
    }

    @Test
    fun `manual fixture encodes prepared-native routing rather than retired materializer refusals`() {
        val readme = source("README.md")
        val xml = source("src/main/resources/mappers/XmlReleaseValidationMapper.xml")
        val product = source("src/main/resources/mappers/ProductMapper.xml")

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

        assertTrue(readme.contains("M06 | `<foreach>` additional bindings"))
        assertTrue(readme.contains("both execute prepared"))
        assertTrue(readme.contains("M09 | VARCHAR prepared binding"))
        assertTrue(readme.contains("this is now supported"))
        assertTrue(readme.contains("M10 | two BIGINT bindings"))
        assertTrue(readme.contains("two ordered bindings execute prepared"))

        listOf(
            "PostgreSqlBigintExecutionMaterializer",
            "one-binding envelope",
            "must **Execution Refused** before native execution",
            "unsupported VARCHAR binding",
            "unsupported two-BIGINT materialization",
        ).forEach { retired ->
            assertFalse(
                "manual PASS contract must not retain retired materializer-era authority: $retired",
                readme.contains(retired),
            )
        }
    }

    @Test
    fun `historical R01 to R05 reproductions are promoted to parity acceptance fixtures`() {
        val readme = source("README.md")
        val choose = source("src/main/resources/mappers/ParityChooseMapper.xml")
        val bind = source("src/main/resources/mappers/ParityBindMapper.xml")
        val resultMap = source("src/main/resources/mappers/ParityResultMapMapper.xml")
        val resultType = source("src/main/resources/mappers/ParityResultTypeMapper.xml")
        val parameterType = source("src/main/resources/mappers/ParityParameterTypeMapper.xml")

        assertTrue(choose.contains("namespace=\"fixture.ParityChooseMapper\""))
        assertTrue(choose.contains("<choose>"))
        assertTrue(choose.contains("R01: P5 parity acceptance case"))

        assertTrue(bind.contains("namespace=\"fixture.ParityBindMapper\""))
        assertTrue(bind.contains("<bind name=\"pattern\""))
        assertTrue(bind.contains("SELECT '${pattern}' AS result_value"))
        assertTrue(bind.contains("R02: P5 parity acceptance case"))

        assertTrue(resultMap.contains("<resultMap"))
        assertTrue(resultMap.contains("resultMap=\"ProductResultMap\""))
        assertTrue(resultMap.contains("R03: P5 parity acceptance case"))

        assertTrue(resultType.contains("resultType=\"fixture.Product\""))
        assertTrue(resultType.contains("R04: P5 parity acceptance case"))

        assertTrue(parameterType.contains("parameterType=\"long\""))
        assertTrue(parameterType.contains("#{id,jdbcType=BIGINT}"))
        assertTrue(parameterType.contains("R05: P5 parity acceptance case"))

        assertTrue(readme.contains("ParityChooseMapper.chooseParity"))
        assertTrue(readme.contains("ParityBindMapper.bindParity"))
        assertTrue(readme.contains("ParityResultMapMapper.findAllWithResultMap"))
        assertTrue(readme.contains("ParityResultTypeMapper.findAllWithResultType"))
        assertTrue(readme.contains("ParityParameterTypeMapper.findByIdWithParameterType"))

        val pendingPaths = listOf(
            "PendingChooseMapper.java",
            "PendingBindMapper.java",
            "PendingResultMapMapper.java",
            "PendingResultTypeMapper.java",
            "PendingParameterTypeMapper.java",
            "PendingChooseMapper.xml",
            "PendingBindMapper.xml",
            "PendingResultMapMapper.xml",
            "PendingResultTypeMapper.xml",
            "PendingParameterTypeMapper.xml",
        )
        Files.walk(sample).use { paths ->
            val names = paths.filter { path -> Files.isRegularFile(path) }
                .map { it.fileName.toString() }
                .toList()
                .toSet()
            pendingPaths.forEach { pending ->
                assertFalse("promoted parity fixture must not retain pending name: $pending", pending in names)
            }
        }
    }

    @Test
    fun `manual Java fixtures cover direct annotations and explicit provider refusal`() {
        val direct = source("src/main/java/fixture/AnnotationReleaseValidationMapper.java")
        val provider = source("src/main/java/fixture/ProviderReleaseValidationMapper.java")

        assertTrue(direct.contains("@Select(\"SELECT 7 AS result_value\")"))
        assertTrue(
            direct.contains("@Select(\"SELECT #{value,jdbcType=BIGINT}::BIGINT AS result_value\")"),
        )
        assertTrue(direct.contains("@Select(\"SELECT ${value} AS result_value\")"))
        assertTrue(direct.contains("@Insert("))
        assertTrue(direct.contains("@Update("))
        assertTrue(direct.contains("@Delete("))
        assertTrue(provider.contains("@SelectProvider"))

        val readme = source("README.md")
        assertTrue(readme.contains("J02 | bound `@Select` BIGINT"))
        assertTrue(readme.contains("prepared preview/result"))
        assertTrue(readme.contains("J07 | `@SelectProvider`"))
        assertTrue(readme.contains("typed provider-not-supported refusal"))
    }

    private fun javacExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "javac.exe"
        } else {
            "javac"
        }
        val javaHome = System.getenv("JAVA_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
        val javaHomeJavac = javaHome?.resolve("bin")?.resolve(executable)
        return javaHomeJavac
            ?.takeIf(Files::isRegularFile)
            ?.toString()
            ?: executable
    }

    private fun myBatisClasspathEntry(): Path {
        val type = Class.forName("org.apache.ibatis.annotations.Select")
        val resourcePath = type.name.replace('.', '/') + ".class"
        val resource = requireNotNull(type.getResource("/$resourcePath")) {
            "MyBatis class resource is unavailable"
        }
        return when (resource.protocol) {
            "jar" -> {
                val external = resource.toExternalForm()
                val separator = external.indexOf("!/")
                require(separator > "jar:".length) { "invalid MyBatis jar resource: $resource" }
                Path.of(URI.create(external.substring("jar:".length, separator)))
            }
            "file" -> {
                var root = Path.of(resource.toURI())
                repeat(resourcePath.split('/').size) {
                    root = requireNotNull(root.parent) {
                        "invalid MyBatis class resource root: $resource"
                    }
                }
                root
            }
            else -> error("unsupported MyBatis class resource protocol: $resource")
        }
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
