package org.http4k.cloudnative.env

import org.http4k.core.Uri
import org.http4k.core.with
import org.http4k.lens.BiDiLensSpec
import org.http4k.lens.Lens
import org.http4k.lens.LensGet
import org.http4k.lens.LensSet
import org.http4k.lens.ParamMeta
import org.http4k.lens.duration
import org.http4k.lens.int
import java.io.File
import java.io.Reader
import java.time.Duration
import java.util.Properties

/**
 * This models the runtime environment of the shell where the app is running. Optionally pass a separator to use for multi-values
 * otherwise a standard comma is used - this means you MUST override the separator if you have single values which contain commas, otherwise
 * singular environment keys will just retrieve the first value.
 */
data class Environment private constructor(private val contents: Map<String, String>, val separator: String = ",") {
    internal operator fun <T> get(key: Lens<Environment, T>) = key(this)
    internal operator fun get(key: String): String? = contents[key] ?: contents[key.convertFromKey()]
    internal operator fun set(key: String, value: String) = Environment(contents + (key.convertFromKey() to value))

    /**
     * Overlays the configuration set in this Environment on top of the values in the passed Environment.
     * Used to chain: eg. Local File -> System Properties -> Env Properties -> Defaults
     */
    infix fun overrides(that: Environment) = Environment(that.contents + contents, separator)

    companion object {
        val EMPTY = from()

        /**
         * Configuration from the runtime environment
         */
        val ENV = Environment(System.getenv())

        /**
         * Configuration from JVM properties (-D flags)
         */
        val JVM_PROPERTIES = System.getProperties().toEnvironment()

        /**
         * Load configuration from standard Properties file format on classpath
         */
        fun fromResource(resource: String) =
            Companion::class.java.getResourceAsStream("/${resource.removePrefix("/")}").reader().toProperties()

        /**
         * Load configuration from standard Properties file format on disk
         */
        fun from(file: File) = file.reader().toProperties()

        fun from(vararg pairs: Pair<String, String>) = Environment(pairs.toMap())

        /**
         * Setup default configuration mappings using EnvironmentKey lens bindings
         */
        fun defaults(vararg fn: (Environment) -> Environment) = fn.fold(EMPTY) { acc, next -> next(acc) }

        private fun Reader.toProperties() = Properties().apply { load(this@toProperties) }.toEnvironment()

        private fun Properties.toEnvironment() = Environment(entries
            .fold(emptyMap()) { acc, (k, v) -> acc.plus(k.toString().convertFromKey() to v.toString()) })
    }
}

/**
 * This models the key used to get a value out of the  Environment using the standard Lens mechanic. Note that if your values contain commas, either use a
 * EnvironmentKey.(mapping).multi.required()/optional()/defaulted() to retrieve the entire list, or override the comma separator in your initial Environment.
 */
object EnvironmentKey : BiDiLensSpec<Environment, String>("env", ParamMeta.StringParam,
    LensGet { name, target -> target[name]?.split(target.separator) ?: emptyList() },
    LensSet { name, values, target ->
        values.fold(target) { acc, next ->
            val existing = acc[name]?.let { listOf(it) } ?: emptyList()
            acc.set(name, (existing + next).joinToString(target.separator))
        }
    }
) {
    object k8s {
        operator fun <T> invoke(fn: EnvironmentKey.k8s.() -> T): T = fn(this)

        val SERVICE_PORT = int().required("SERVICE_PORT")
        val HEALTH_PORT = int().required("HEALTH_PORT")

        fun serviceUriFor(serviceName: String, isHttps: Boolean = false) = int()
            .map(serviceName.toUriFor(isHttps), { it.port ?: 80 })
            .required("${serviceName.convertFromKey().toUpperCase()}_SERVICE_PORT")

        private fun String.toUriFor(https: Boolean): (Int) -> Uri = {
            Uri.of("/")
                .scheme(if (https) "https" else "http")
                .authority(if (it == 80 || it == 443) this else "$this:$it")
        }
    }
}

internal fun String.convertFromKey() = replace("_", "-").replace(".", "-").toLowerCase()

fun main(args: Array<String>) {
    val required = EnvironmentKey.duration().multi.required("FOO")
    println(
        required(
            Environment.ENV.with(
                required.of(listOf(Duration.ofHours(1), Duration.ofHours(2)))
            )
        )
    )
}