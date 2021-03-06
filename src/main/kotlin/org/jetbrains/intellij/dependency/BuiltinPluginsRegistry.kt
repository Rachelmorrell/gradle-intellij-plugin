package org.jetbrains.intellij.dependency

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.intellij.createPlugin
import org.jetbrains.intellij.debug
import org.jetbrains.intellij.parseXml
import org.jetbrains.intellij.warn
import java.io.File
import java.io.Serializable

class BuiltinPluginsRegistry(private val pluginsDirectory: File) : Serializable {

    private val plugins = mutableMapOf<String, Plugin>()
    private val directoryNameMapping = mutableMapOf<String, String>()

    companion object {
        fun fromDirectory(pluginsDirectory: File, loggingContext: Any) =
            BuiltinPluginsRegistry(pluginsDirectory).apply {
                if (!fillFromCache(loggingContext)) {
                    debug(loggingContext, "Builtin registry cache is missing")
                    fillFromDirectory(loggingContext)
                    dumpToCache(loggingContext)
                }
            }
    }

    private fun fillFromCache(loggingContext: Any): Boolean {
        val cache = cacheFile().takeIf { it.exists() } ?: return false

        debug(loggingContext, "Builtin registry cache is found. Loading from $cache")
        return try {
            parseXml(cache, PluginsCache::class.java).plugin.forEach {
                plugins[it.id] = it
                directoryNameMapping[it.directoryName] = it.id
            }
            true
        } catch (t: Throwable) {
            warn(loggingContext, "Cannot read builtin registry cache", t)
            false
        }
    }

    private fun fillFromDirectory(loggingContext: Any) {
        pluginsDirectory.listFiles()?.apply {
            asSequence()
                .filter { it.isDirectory }
                .forEach { add(it, loggingContext) }
        }
        debug(loggingContext, "Builtin registry populated with ${plugins.size} plugins")
    }

    private fun dumpToCache(loggingContext: Any) {
        debug(loggingContext, "Dumping cache for builtin plugin")
        try {
            XmlMapper()
                .registerKotlinModule()
                .writeValue(cacheFile(), PluginsCache(plugins.values.toList()))
        } catch (t: Throwable) {
            warn(loggingContext, "Failed to dump cache for builtin plugin", t)
        }
    }

    private fun cacheFile() = File(pluginsDirectory, "builtinRegistry.xml")

    fun findPlugin(name: String): File? {
        val plugin = plugins[name] ?: plugins[directoryNameMapping[name]] ?: return null
        val result = File(pluginsDirectory, plugin.directoryName)

        return when {
            result.exists() && result.isDirectory -> result
            else -> null
        }
    }

    fun collectBuiltinDependencies(pluginIds: Collection<String>): Collection<String> {
        val idsToProcess = pluginIds.toMutableList()
        val result = mutableSetOf<String>()

        while (idsToProcess.isNotEmpty()) {
            val id = idsToProcess.removeAt(0)
            val plugin = plugins[id] ?: plugins[directoryNameMapping[id]] ?: continue
            if (result.add(id)) {
                idsToProcess.addAll(plugin.dependencies.dependencies - result)
            }
        }

        return result
    }

    fun add(artifact: File, loggingContext: Any) {
        debug(loggingContext, "Adding directory to plugins index: $artifact)")
        val intellijPlugin = createPlugin(artifact, false, loggingContext) ?: return
        val id = intellijPlugin.pluginId ?: return
        val dependencies = intellijPlugin.dependencies.filter { !it.isOptional }.map { it.id }
        val plugin = Plugin(id, artifact.name, Dependencies(dependencies))

        plugins[id] = plugin
        if (plugin.directoryName != id) {
            directoryNameMapping[plugin.directoryName] = id
        }
    }

    @JacksonXmlRootElement(localName = "plugins")
    data class PluginsCache(
        @JacksonXmlElementWrapper(useWrapping = false)
        var plugin: List<Plugin> = emptyList(),
    ) : Serializable

    data class Plugin(
        @JacksonXmlProperty(isAttribute = true)
        val id: String,
        @JacksonXmlProperty(isAttribute = true)
        val directoryName: String,
        val dependencies: Dependencies,
    ) : Serializable

    data class Dependencies(
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "dependency")
        val dependencies: List<String> = emptyList(),
    ) : Serializable
}
