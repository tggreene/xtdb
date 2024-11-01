package xtdb.cache

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PinningCache<V : PinningCache.IEntry>(
    @Suppress("MemberVisibilityCanBePrivate")
    val maxSizeBytes: Long
) : AutoCloseable {

    @Volatile
    private var pinnedBytes: Long = 0

    val stats: Stats
        get() {
            val pinnedBytes = maxSizeBytes - eviction.maximum
            val evictableBytes = eviction.weightedSize().asLong
            return Stats(pinnedBytes, evictableBytes, maxSizeBytes - pinnedBytes - evictableBytes)
        }

    interface IEntry {
        val weight: Long

        // NOTE: updates to this ref-count MUST be done within `cache.asMap().compute(k) { ... }`
        // in order to take effect atomically in the cache eviction policy.
        val refCount: AtomicInteger

        fun onEvict(k: Path, reason: RemovalCause) {}
    }

    val cache: AsyncCache<Path, V> = Caffeine.newBuilder()
        .maximumWeight(maxSizeBytes)
        .evictionListener<Path, V> { key, value, cause -> value!!.onEvict(key!!, cause) }
        .removalListener<Path, V> { key, value, cause -> if (!cause.wasEvicted()) value!!.onEvict(key!!, cause) }
        .weigher<Path, V> { _, value -> if (value.refCount.get() > 0) 0 else value.weight.toInt() }
        .buildAsync()

    private val eviction = cache.synchronous().policy().eviction().get()

    private val lock: Lock = ReentrantLock()

    private fun updatePinnedBytes(delta: Long) {
        lock.withLock {
            pinnedBytes += delta
            eviction.maximum = (maxSizeBytes - pinnedBytes).coerceAtLeast(0)
        }
    }

    @Suppress("NAME_SHADOWING")
    fun get(k: Path, f: (Path) -> CompletableFuture<V>): CompletableFuture<V> =
        cache.asMap().compute(k) { k, fut ->
            // NOTE: this MUST be thenApplyAsync rather than thenApply
            // otherwise the entry is at risk of eviction
            // see #3828 and ben-manes/caffeine#1791
            // but tl;dr: the entry is not pinned before the setMaximum eviction runs

            (fut ?: f(k))
                .thenApplyAsync { entry ->
                    if (0 == entry.refCount.getAndIncrement()) updatePinnedBytes(entry.weight)
                    entry
                }
        }!!

    fun invalidate(k: Path) {
        cache.synchronous().run {
            invalidate(k)
            cleanUp()
        }

        ForkJoinPool.commonPool().awaitQuiescence(100, MILLISECONDS)
    }

    override fun close() {
        cache.asMap().clear()
        cache.synchronous().cleanUp()
        ForkJoinPool.commonPool().awaitQuiescence(100, MILLISECONDS)
    }

    fun releaseEntry(k: Path) {
        cache.asMap().compute(k) { _, fut ->
            fut!!.thenApplyAsync { entry ->
                if (0 == entry.refCount.decrementAndGet().also { check(it >= 0) }) updatePinnedBytes(-entry.weight)
                entry
            }
        }
    }

    inner class Entry(override val weight: Long) : IEntry {
        override val refCount = AtomicInteger(0)
    }
}