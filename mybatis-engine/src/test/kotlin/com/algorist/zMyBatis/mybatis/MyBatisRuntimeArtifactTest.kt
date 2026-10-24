package com.algorist.zMyBatis.mybatis

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.apache.ibatis.session.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MyBatisRuntimeArtifactTest {
    private val probeType = MyBatisRuntimeArtifactProbe::class.java
    private val probeEntry = probeType.name.replace('.', '/') + ".class"

    @Test
    fun actualMyBatisArtifactCanLoadIsolatedConfigurationAndMapperBuilder() {
        val location = MyBatisRuntimeArtifact.fromClassResource(Configuration::class.java)
        assertNotNull(location)
        URLClassLoader(arrayOf(location!!), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertSame(loader, Class.forName("org.apache.ibatis.session.Configuration", false, loader).classLoader)
            assertSame(loader, Class.forName("org.apache.ibatis.builder.xml.XMLMapperBuilder", false, loader).classLoader)
        }
    }

    @Test
    fun nullCodeSourceCanRecoverExactLocalJarWithoutParentDelegation() {
        val jarPath = Files.createTempFile("mybatis-runtime-resource", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
                jar.putNextEntry(JarEntry(probeEntry))
                jar.write(probeBytes())
                jar.closeEntry()
            }
            val classResource = URL("jar:" + jarPath.toUri().toURL() + "!/" + probeEntry)
            val classFromResourceOnlyLoader = defineWithoutCodeSource(classResource)
            assertNull(classFromResourceOnlyLoader.protectionDomain?.codeSource?.location)

            val recovered = MyBatisRuntimeArtifact.fromClassResource(classFromResourceOnlyLoader)
            assertEquals(jarPath.toUri().toURL(), recovered)
            URLClassLoader(arrayOf(recovered!!), ClassLoader.getPlatformClassLoader()).use { loader ->
                assertSame(loader, Class.forName(probeType.name, false, loader).classLoader)
            }
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    @Test
    fun nullCodeSourceCanRecoverExactLocalClassesRoot() {
        val root = Files.createTempDirectory("mybatis-runtime-classes")
        val classFile = root.resolve(probeEntry)
        try {
            Files.createDirectories(classFile.parent)
            Files.write(classFile, probeBytes())
            val classFromResourceOnlyLoader = defineWithoutCodeSource(classFile.toUri().toURL())
            assertNull(classFromResourceOnlyLoader.protectionDomain?.codeSource?.location)

            val recovered = MyBatisRuntimeArtifact.fromClassResource(classFromResourceOnlyLoader)
            assertEquals(root.toUri().toURL(), recovered)
        } finally {
            Files.deleteIfExists(classFile)
            Files.deleteIfExists(classFile.parent)
            Files.deleteIfExists(classFile.parent.parent)
            Files.deleteIfExists(classFile.parent.parent.parent)
            Files.deleteIfExists(classFile.parent.parent.parent.parent)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun remoteClassResourceIsNotAnArtifactAuthority() {
        val remoteClass = defineWithoutCodeSource(URL("https://example.invalid/" + probeEntry))
        assertNull(remoteClass.protectionDomain?.codeSource?.location)
        assertNull(MyBatisRuntimeArtifact.fromClassResource(remoteClass))
    }

    @Test
    fun mismatchedJarEntryFailsClosed() {
        val jarPath = Files.createTempFile("mybatis-runtime-wrong-entry", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
                jar.putNextEntry(JarEntry(probeEntry))
                jar.write(probeBytes())
                jar.closeEntry()
            }
            val wrongEntry = URL("jar:" + jarPath.toUri().toURL() + "!/wrong/Configuration.class")
            assertNull(MyBatisRuntimeArtifact.fromClassResource(defineWithoutCodeSource(wrongEntry)))
        } finally {
            Files.deleteIfExists(jarPath)
        }
    }

    private fun probeBytes(): ByteArray =
        requireNotNull(probeType.getResourceAsStream("/" + probeEntry)).use { it.readBytes() }

    private fun defineWithoutCodeSource(resource: URL): Class<*> {
        val bytes = probeBytes()
        val loader = object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != probeType.name) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    val loaded = findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }

            override fun getResource(name: String): URL? =
                if (name == probeEntry) resource else super.getResource(name)
        }
        return Class.forName(probeType.name, false, loader)
    }
}

internal class MyBatisRuntimeArtifactProbe
