package com.ohyooo.lvtu

import java.util.concurrent.Callable
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.UnaryOperator
import java.util.function.Supplier
import java.util.function.ToLongFunction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext

class MapJobs : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun <T> submit(
        compute: Callable<T>,
        inspect: UnaryOperator<T>,
        success: Consumer<T>,
        failure: Consumer<Exception>,
    ) {
        cancel()
        job = scope.launch {
            try {
                delay(1_000)
                val computed = runInterruptible(Dispatchers.Default) { compute.call() }
                val inspected = runInterruptible(Dispatchers.IO) { inspect.apply(computed) }
                success.accept(inspected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.accept(error)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun io(work: Runnable, failure: Consumer<Exception>): Job = after(null, work, failure)

    fun after(previous: Job?, work: Runnable, failure: Consumer<Exception>): Job =
        scope.launch {
            try {
                previous?.join()
                runInterruptible(Dispatchers.IO) { work.run() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.accept(error)
            }
        }

    fun <T, R> load(
        read: Callable<T>,
        compute: Function<T, R>,
        success: Consumer<R>,
        failure: Consumer<Exception>,
    ): Job = loadAfter(null, read, compute, success, failure)

    fun <T, R> loadAfter(
        previous: Job?, read: Callable<T>, compute: Function<T, R>, success: Consumer<R>, failure: Consumer<Exception>,
    ): Job = scope.launch {
        try {
            previous?.join()
            val raw = runInterruptible(Dispatchers.IO) { read.call() }
            val result = runInterruptible(Dispatchers.Default) { compute.apply(raw) }
            success.accept(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure.accept(error)
        }
    }

    fun <T, R> calculate(
        read: Callable<T>, compute: Function<T, R>, success: Consumer<R>, failure: Consumer<Exception>,
    ) {
        cancel()
        job = scope.launch {
            try {
                delay(1_000)
                val raw = runInterruptible(Dispatchers.IO) { read.call() }
                val result = runInterruptible(Dispatchers.Default) { compute.apply(raw) }
                success.accept(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.accept(error)
            }
        }
    }

    fun observe(work: Runnable): Job = scope.launch {
        while (isActive) {
            work.run()
            delay(100)
        }
    }

    fun download(previous: Job?, prepare: Runnable, work: Runnable, failure: Consumer<Exception>): Job = scope.launch {
        previous?.join()
        prepare.run()
        delay(500)
        var retry = 1_000L
        while (isActive) {
            try {
                runInterruptible(Dispatchers.IO) { work.run() }
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.accept(error)
                delay(retry)
                retry = (retry * 2).coerceAtMost(60_000)
            }
        }
    }

    fun downloadWorkers(work: Consumer<BooleanSupplier>) = downloadWorkers(Supplier {
        ToLongFunction<BooleanSupplier> { running ->
            work.accept(running)
            -1L
        }
    })

    fun downloadWorkers(factory: Supplier<ToLongFunction<BooleanSupplier>>) = runBlocking {
        repeat(4) {
            launch(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val work = factory.get()
                while (isActive) {
                    val wait = runInterruptible { work.applyAsLong(BooleanSupplier { context.isActive }) }
                    if (wait < 0) break
                    if (wait > 0) delay(wait)
                }
            }
        }
    }

    fun onMain(work: Runnable) {
        scope.launch { work.run() }
    }

    override fun close() {
        scope.cancel()
    }
}
