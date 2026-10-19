package dev.sid.fiveberinspector;

import java.util.concurrent.*;

/** Bounded, one-attempt native supervision. No failure payload or credentials escape the worker. */
final class DownloadWorkerSupervisor {
    static final class Outcome<T> {
        final T result;
        final boolean workerStopped;
        Outcome(T result, boolean workerStopped) {this.result=result;this.workerStopped=workerStopped;}
    }
    static void checkCancelled() throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Download cancelled");
    }
    static <T> Outcome<T> run(Callable<T> work,Runnable stopNative,long timeoutMs,long shutdownMs) {
        if(timeoutMs<1 || shutdownMs<1)throw new IllegalArgumentException("Invalid worker deadline");
        ExecutorService executor=Executors.newSingleThreadExecutor();
        Future<T> future=executor.submit(work);
        T result=null;
        boolean interrupted=false;
        try {
            result=future.get(timeoutMs,TimeUnit.MILLISECONDS);
        } catch(InterruptedException cancelled) {
            interrupted=true;
        } catch(ExecutionException|TimeoutException|CancellationException ignored) {
            // Intentionally discard throwable messages/causes from native/card/network processing.
        } finally {
            future.cancel(true);
            try {stopNative.run();}catch(RuntimeException ignored){result=null;}
            executor.shutdownNow();
        }
        boolean stopped=false;
        try {stopped=executor.awaitTermination(shutdownMs,TimeUnit.MILLISECONDS);}
        catch(InterruptedException cancelled){interrupted=true;stopped=executor.isTerminated();}
        finally {if(interrupted)Thread.currentThread().interrupt();}
        // A cancelled operation never becomes success even if it completes during shutdown.
        return new Outcome<>(result,stopped);
    }
    private DownloadWorkerSupervisor(){}
}
