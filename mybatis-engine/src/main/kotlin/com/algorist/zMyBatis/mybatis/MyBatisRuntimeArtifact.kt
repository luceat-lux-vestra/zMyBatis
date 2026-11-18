package com.algorist.zMyBatis.mybatis

import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

/**
 * Recovers only the local artifact named by the already-loaded class's own resource.
 *
 * IntelliJ's packaged plugin classloader may expose a null ProtectionDomain.codeSource and may
 * not expose a URL for a class resource even though it can stream the class bytes. In that case,
 * inspect only the defining classloader's own local classpath and accept an artifact only when
 * exactly one entry contains byte-identical class content.
 *
 * Never search the TCCL, system classpath, unrelated plugin directories, or arbitrary jars for
 * alternatives. Ambiguous or non-local defining-loader matches fail closed. The caller must still
 * construct a child loader with the platform parent and verify ownership of the MyBatis classes
 * loaded from this artifact.
 */
internal object MyBatisRuntimeArtifact {
    fun resolve(type: Class<*>): URL? =
        type.protectionDomain?.codeSource?.location
            ?: fromClassResource(type)
            ?: fromDefiningClassLoader(type)

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

    private fun fromDefiningClassLoader(type: Class<*>): URL? {
        val loader = type.classLoader ?: return null
        val resourcePath = type.name.replace('.', '/') + ".class"
        val expectedBytes = definingLoaderResourceBytes(loader, resourcePath)
            ?: return null
        val matches = definingLoaderUrls(loader)
            .asSequence()
            .mapNotNull { localArtifactWithMatchingClass(it, resourcePath, expectedBytes) }
            .distinctBy(URL::toExternalForm)
            .take(2)
            .toList()
        return matches.singleOrNull()
    }

    private fun definingLoaderResourceBytes(
        loader: ClassLoader,
        resourcePath: String,
    ): ByteArray? {
        val ownBytesMethod = loader.javaClass.methods.firstOrNull {
            it.name == "getResourceAsBytes" &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, Boolean::class.javaPrimitiveType))
        }
        if (ownBytesMethod != null) {
            val value = try {
                ownBytesMethod.invoke(loader, resourcePath, false)
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (value is ByteArray) return value
        }
        return loader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
    }

    private fun definingLoaderUrls(loader: ClassLoader): List<URL> {
        if (loader is URLClassLoader) return loader.urLs.toList()

        reflectLocations(loader, "getFiles")?.let { return it }
        reflectLocations(loader, "getUrls")?.let { return it }

        val classPath = invokeNoArg(loader, "getClassPath")
        if (classPath != null) {
            reflectLocations(classPath, "getFiles")?.let { return it }
            reflectLocations(classPath, "getBaseUrls")?.let { return it }
        }
        return emptyList()
    }

    private fun reflectLocations(target: Any, methodName: String): List<URL>? {
        val value = invokeNoArg(target, methodName) ?: return null
        val items = when (value) {
            is Iterable<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> return null
        }
        return items.mapNotNull { item ->
            when (item) {
                is URL -> item
                is Path -> runCatching { item.toUri().toURL() }.getOrNull()
                else -> null
            }
        }
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: return null
        return try {
            method.invoke(target)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun localArtifactWithMatchingClass(
        location: URL,
        resourcePath: String,
        expectedBytes: ByteArray,
    ): URL? {
        if (location.protocol != "file") return null
        return try {
            val path = Paths.get(location.toURI()).toAbsolutePath().normalize()
            when {
                Files.isDirectory(path) -> {
                    val classFile = path.resolve(resourcePath)
                    if (
                        Files.isRegularFile(classFile) &&
                        Files.readAllBytes(classFile).contentEquals(expectedBytes)
                    ) {
                        path.toUri().toURL()
                    } else {
                        null
                    }
                }
                Files.isRegularFile(path) -> JarFile(path.toFile()).use { jar ->
                    val entry = jar.getJarEntry(resourcePath) ?: return@use null
                    val matches = jar.getInputStream(entry).use { it.readBytes() }
                        .contentEquals(expectedBytes)
                    if (matches) path.toUri().toURL() else null
                }
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
