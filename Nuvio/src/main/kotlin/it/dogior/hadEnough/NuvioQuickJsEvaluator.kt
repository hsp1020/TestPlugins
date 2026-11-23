package it.dogior.hadEnough

import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers

/** Separate class keeps QuickJS linkage out of the normal Nuvio plugin load path. */
internal object NuvioQuickJsEvaluator {
    suspend fun evaluate(): String {
        repeat(3) {
            quickJs(Dispatchers.Default) {
                val modern = evaluate<Int>(
                    "class Counter { constructor(n) { this.n = n; } add(x) { return this.n + x; } }; " +
                        "const counter = new Counter(40); counter.add(2)"
                )
                check(modern == 42) { "class/const mismatch: $modern" }
                val generator = evaluate<Int>(
                    "function* sequence() { yield 5; }; sequence().next().value"
                )
                check(generator == 5) { "generator mismatch: $generator" }
                val async = evaluate<Int>(
                    "(async function() { return await Promise.resolve(7); })()"
                )
                check(async == 7) { "async/await mismatch: $async" }
            }
        }
        return "class, const, generator, async/await; 3 create/evaluate/close cycles"
    }
}
