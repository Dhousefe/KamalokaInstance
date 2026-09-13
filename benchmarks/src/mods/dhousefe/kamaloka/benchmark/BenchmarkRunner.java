package mods.dhousefe.kamaloka.benchmark;

import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Collection;

public class BenchmarkRunner {
    public static void main(String[] args) throws RunnerException {
        System.out.println("================================================================================");
        System.out.println("  INICIANDO BATERIA DE BENCHMARKS JMH: KAMALOKA INSTANCE");
        System.out.println("================================================================================");

        Options opt = new OptionsBuilder()
                .include(KamalokaBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(2)
                .measurementIterations(3)
                .build();

        Collection<RunResult> results = new Runner(opt).run();
        System.out.println("\n================================================================================");
        System.out.println("  BENCHMARK FINALIZADO COM SUCESSO. TOTAL DE TESTES: " + results.size());
        System.out.println("================================================================================");
    }
}
