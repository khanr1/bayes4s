/*
 * Copyright 2026 bayes4s contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bayes4s

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

/** What a gradient costs, which is the number the design of `Real[A]` stands or falls on.
  *
  * Three things are measured, and the comparisons between them are the point:
  *
  *   - the **value** of a model against its **value and gradient**, which is the multiple the
  *     reverse mode costs. A sampler asks for one gradient per leapfrog step, so this ratio is
  *     what decides whether the tape is affordable at all;
  *   - the same multilevel model written with a vectorised density and written out per
  *     observation, which is the measurement of what the vectorised primitives buy;
  *   - the gradient at a hundred observations and at ten thousand, which is where a tape whose
  *     size does not depend on the data becomes visible.
  *
  * Run with `sbt "benchmarks/Jmh/run -i 5 -wi 3 -f1 -t1 bayes4s.GradientBenchmark"`; the figures
  * this machine gave are recorded in docs/validation.md with the hardware they were taken on.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
class GradientBenchmark:

  private val ar1 = TestModels.ar1PoissonOrdinal()
  private val ar1Point = TestModels.ar1At()

  private val varying = TestModels.varyingIntercepts(10_000, 50)
  private val varyingSmall = TestModels.varyingIntercepts(100, 50)
  private val varyingPoint = TestModels.varyingAt(50)

  private val withSlope = TestModels.varyingInterceptsWithSlope(10_000, 50)
  private val withSlopePoint = TestModels.varyingAt(50, withSlope = true)

  @Benchmark
  def ar1Value: Double = Reverse.value(ar1, ar1Point)

  @Benchmark
  def ar1Gradient: ValueAndGradient = Reverse.valueAndGradient(ar1, ar1Point)

  @Benchmark
  def varyingValue: Double = Reverse.value(varying, varyingPoint)

  @Benchmark
  def varyingGradient: ValueAndGradient = Reverse.valueAndGradient(varying, varyingPoint)

  @Benchmark
  def varyingGradientSmall: ValueAndGradient =
    Reverse.valueAndGradient(varyingSmall, varyingPoint)

  @Benchmark
  def varyingGradientWithWrittenOutSlope: ValueAndGradient =
    Reverse.valueAndGradient(withSlope, withSlopePoint)
