package com.algorist.zMyBatis.mybatis

import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

/**
 * Recovers only the local artifact named by the already-loaded class's own resource.
 *
 * IntelliJ's packaged plugin classloader may expose a null ProtectionDomain.codeSource.
 * Never search the TCCL, system classpath, other jars, or plugin directories for alternatives.
 * The caller must still construct a child loader with the platform parent and verify ownership
 * of the MyBatis classes loaded from this artifact.
 */
internal object MyBatisRuntimeArtifact {
    fun resolve(type: Class<*>): URL? =
        type.protectionDomain?.codeSource?.location
            ?: fromClassResource(type)

    fun fromClassResource(type: Class<*>): URL? {
        val resourcePath = type.name.replace('.', '/') + ".class"
        val resource = type.getResource("/$resourcePath") ?: return null
        return try {
            when (resource.protocol) {
                "jar" -> jarArtifact(resource, resourcePath)
                "file" -> classesArtifact(resource, resourcePath)
                else -> null
            }
        } catch (_: IOException) {
            null
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun jarArtifact(resource: URL, resourcePath: String): URL? {
        val connection = resource.openConnection() as? JarURLConnection ?: return null
        if (connection.entryName != resourcePath) return null
        val backing = connection.jarFileURL
        if (backing.protocol != "file") return null

        val jarPath = Paths.get(backing.toURI())
        if (!Files.isRegularFile(jarPath)) return null
        JarFile(jarPath.toFile()).use { jar ->
            if (jar.getJarEntry(resourcePath) == null) return null
        }
        return jarPath.toUri().toURL()
    }

    private fun classesArtifact(resource: URL, resourcePath: String): URL? {
        val classFile = Paths.get(resource.toURI()).toAbsolutePath().normalize()
        val suffix = Paths.get(resourcePath)
        if (!Files.isRegularFile(classFile) || !classFile.endsWith(suffix)) return null

        var root: Path = classFile
        repeat(suffix.nameCount) {
            root = root.parent ?: return null
        }
        if (!Files.isDirectory(root)) return null
        return root.toUri().toURL()
    }
}
