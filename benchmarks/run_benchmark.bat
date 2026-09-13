@echo off
setlocal enabledelayedexpansion

echo ======================================================================
echo  [JMH-PROFILER] Executando Microbenchmarks do KamalokaInstance
echo ======================================================================

set CP=benchmarks\bin;benchmarks\libs\jmh-core-1.37.jar;benchmarks\libs\jmh-generator-annprocess-1.37.jar;benchmarks\libs\jopt-simple-5.0.4.jar;benchmarks\libs\commons-math3-3.6.1.jar

java -cp "%CP%" mods.dhousefe.kamaloka.benchmark.BenchmarkRunner %*
