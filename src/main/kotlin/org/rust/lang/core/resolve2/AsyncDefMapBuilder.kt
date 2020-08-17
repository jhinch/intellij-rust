/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapiext.isUnitTestMode
import org.rust.lang.core.crate.Crate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.system.measureTimeMillis

class AsyncDefMapBuilder(
    private val pool: Executor,
    topSortedCrates: List<Crate>,
    private val indicator: ProgressIndicator
) {
    /** Values - number of dependencies for which [CrateDefMap] is not build yet */
    private val remainingDependenciesCounts: MutableMap<Crate, Int> = topSortedCrates
        .associateWith { it.dependencies.size }
        .toMutableMap()
    private val completableFuture: CompletableFuture<Unit> = CompletableFuture()

    @Volatile
    private var remainingNumberCrates: Int = topSortedCrates.size

    // only for profiling
    private val tasksTimes: MutableMap<Crate, Long> = ConcurrentHashMap()

    fun build() {
        val wallTime = measureTimeMillis {
            buildImpl()
        }

        if (!isUnitTestMode || wallTime > 2000) {
            val totalTime = tasksTimes.values.sum()
            val top5crates = tasksTimes.entries
                .sortedByDescending { (_, time) -> time }
                .take(5)
                .joinToString { (crate, time) -> "$crate ${time}ms" }
            println("wallTime: $wallTime, totalTime: $totalTime, " +
                "parallelism coefficient: ${"%.2f".format((totalTime.toDouble() / wallTime))}.    " +
                "Top 5 crates: $top5crates")
        }
    }

    fun buildImpl() {
        remainingDependenciesCounts
            .filterValues { it == 0 }
            .keys
            .forEach { buildDefMapAsync(it) }
        completableFuture.join()
    }

    private fun buildDefMapAsync(crate: Crate) {
        pool.execute {
            try {
                tasksTimes[crate] = measureTimeMillis {
                    crate.updateDefMap(indicator)
                }
            } catch (e: Throwable) {
                completableFuture.completeExceptionally(e)
                return@execute
            }
            onCrateFinished(crate)
        }
    }

    @Synchronized
    private fun onCrateFinished(crate: Crate) {
        if (completableFuture.isCompletedExceptionally) return

        crate.reverseDependencies.forEach { onDependencyCrateFinished(it) }
        remainingNumberCrates -= 1
        if (remainingNumberCrates == 0) {
            completableFuture.complete(Unit)
        }
    }

    private fun onDependencyCrateFinished(crate: Crate) {
        var count = remainingDependenciesCounts.getValue(crate)
        count -= 1
        remainingDependenciesCounts[crate] = count
        if (count == 0) {
            buildDefMapAsync(crate)
        }
    }
}
