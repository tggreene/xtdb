@file:UseSerializers(DurationSerde::class)
package xtdb.api
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xtdb.DurationSerde
import java.time.Duration

@Serializable
data class MemoryTrimmerConfig(
    var enabled: Boolean = true,
    var interval: Duration = Duration.ofSeconds(10),
) {
    fun enabled(enabled: Boolean) = apply { this.enabled = enabled }
    fun interval(interval: Duration) = apply { this.interval = interval }
}
