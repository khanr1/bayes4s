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

import bayes4s.StanMathFixtureSupport.*
import bayes4s.syntax.{*, given}
import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import cats.effect.IO
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The gradient of every density, and of two models with the shape of real work, against the
  * dual-number oracle.
  *
  * The oracle shares no derivative code with the tape. It implements the scalar operations and
  * inherits every vectorised primitive from the defaults of `Real`, which are written in terms of
  * those scalar operations; the tape overrides them with hand-written adjoints. Comparing the two
  * compares a hand-written derivative against the derivative of the composition it claims to
  * equal — which is the thing that can be silently wrong, and which no comparison of values would
  * see.
  */
object LogDensityGradientSuite extends SimpleIOSuite with Approx:

  private def agree(f: DiffFn, x: IArray[Double], clue: String) =
    val tape = Reverse.valueAndGradient(f, x)
    val oracle = Dual.gradient(f, x)
    val values = closeRel(tape.value, Dual.value(f, x), 1e-14, s"$clue: the values disagree")
    values.and(forEach((0 until x.length).toList) { i =>
      closeRel(tape.gradient(i), oracle(i), 1e-12, s"$clue: d/dx($i)")
    })

  pureTest("the gradient of all 53 Stan Math cases, tape against Dual") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val cases = for
          (law, functions) <- fixture.laws.toList.sortBy(_._1)
          if lawsInScope(law)
          (function, body) <- functions.toList.sortBy(_._1)
          if densityFamily(law, function)
          valid <- body.valid
          built <- realCase(law, function, valid.params).toOption.toList
          // A case with no derivative — a density at a boundary, say — would assert nothing
          // here, so it would be dropped; the count below is what makes a silent drop visible.
          // As it happens Stan Math asserts none: all 53 are differentiable at their arguments.
          if Dual.value(built.fn, built.inputs).isFinite
          if Dual.gradient(built.fn, built.inputs).toList.forall(_.isFinite)
        yield (law, function, valid.params, built)
        val assertions = forEach(cases) { (law, function, params, built) =>
          agree(built.fn, built.inputs, s"$law.$function(${params.toList.mkString(", ")})")
        }
        check(cases.length == 53, s"${cases.length} differentiable cases, expected 53")
          .and(assertions)
  }

  /** Central differences on the densities, which is the check that shares nothing at all with
    * either implementation — not even the chain rule.
    */
  pureTest("the gradient of each density against central differences") {
    val cases: List[(String, DiffFn, IArray[Double])] = List(
      (
        "normal",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.normal(x(0), x(1), x(2))
        ,
        IArray(1.1, 0.3, 2.0)
      ),
      (
        "halfNormal",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.halfNormal(x(0), x(1))
        ,
        IArray(0.6, 1.4)
      ),
      (
        "logNormal",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.logNormal(x(0), x(1), x(2))
        ,
        IArray(2.3, -0.2, 0.7)
      ),
      (
        "gamma",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.gamma(x(0), x(1), x(2))
        ,
        IArray(0.8, 2.5, 1.3)
      ),
      (
        "beta",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.beta(x(0), x(1), x(2))
        ,
        IArray(0.25, 2.0, 5.0)
      ),
      (
        "logistic",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.logistic(x(0), x(1), x(2))
        ,
        IArray(-0.4, 0.5, 1.7)
      ),
      (
        "studentT",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.studentT(x(0), x(1), x(2), x(3))
        ,
        IArray(2.2, 4.0, 0.2, 1.1)
      ),
      (
        "cauchy",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.cauchy(x(0), x(1), x(2))
        ,
        IArray(-3.0, 0.1, 2.0)
      ),
      (
        "halfStudentT",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.halfStudentT(x(0), x(1), x(2))
        ,
        IArray(2.0, 3.0, 1.5)
      ),
      (
        "halfCauchy",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.halfCauchy(x(0), x(1))
        ,
        IArray(1.0, 2.5)
      ),
      (
        "exponential",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogDensity.exponential(x(0), x(1))
        ,
        IArray(3.1, 0.7)
      ),
      (
        "poisson",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.poisson(4, x(0))
        ,
        IArray(3.2)
      ),
      (
        "poissonLog",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.poissonLog(4, x(0))
        ,
        IArray(1.2)
      ),
      (
        "negativeBinomial2",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.negativeBinomial2(5, x(0), x(1))
        ,
        IArray(3.0, 2.5)
      ),
      (
        "negativeBinomial",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.negativeBinomial(5, x(0), x(1))
        ,
        IArray(2.0, 0.8)
      ),
      (
        "binomial",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.binomial(4, 12, x(0))
        ,
        IArray(0.3)
      ),
      (
        "bernoulliLogit",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.bernoulliLogit(1, x(0))
        ,
        IArray(-1.3)
      ),
      (
        "binomialLogit",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.binomialLogit(4, 12, x(0))
        ,
        IArray(-1.3)
      ),
      (
        "betaBinomial",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A = LogMass.betaBinomial(4, 12, x(0), x(1))
        ,
        IArray(2.0, 3.0)
      ),
      (
        "orderedLogistic",
        new DiffFn:
          def apply[A: Real](x: IArray[A]): A =
            LogDensity.orderedLogistic(2, x(0), IArray(x(1), x(2), x(3)))
        ,
        IArray(0.3, -1.0, 0.5, 2.0)
      )
    )
    // Central differences carry a truncation error in h^2 and a cancellation error in eps/h; at
    // h = 1e-5 both sit near 1e-10, which is the honest tolerance for the comparison and three
    // orders of magnitude tighter than any sign or factor error.
    forEach(cases) { (name, f, at) =>
      val result = GradientCheck(f, at, 1e-5)
      check(result.maxError <= 1e-8, s"$name: central differences differ by ${result.maxError}")
        .and(agree(f, at, name))
    }
  }

  pureTest("the gradient of the multivariate Gaussian, tape against Dual") {
    // Inputs: the variate, the mean, then the six lower entries of a 3 by 3 factor.
    val f = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        val zero = r.const(0.0)
        val l = IArray(
          r.vector(x(6), zero, zero),
          r.vector(x(7), x(8), zero),
          r.vector(x(9), x(10), x(11))
        )
        LogDensity.multiNormalCholesky(r.vector(x(0), x(1), x(2)), r.vector(x(3), x(4), x(5)), l)
    val at = IArray(0.5, -0.5, 1.0, 0.1, 0.2, -0.3, 1.2, 0.4, 0.9, -0.2, 0.3, 1.5)
    agree(f, at, "multiNormalCholesky").and {
      val result = GradientCheck(f, at, 1e-5)
      check(result.maxError <= 1e-8, s"central differences differ by ${result.maxError}")
    }
  }

  pureTest("the gradient of the LKJ density, tape against Dual, and zero off the diagonal") {
    // Inputs: the concentration, then the six lower entries of a factor with unit row norms.
    val f = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        val zero = r.const(0.0)
        val l = IArray(
          r.vector(x(1), zero, zero),
          r.vector(x(2), x(3), zero),
          r.vector(x(4), x(5), x(6))
        )
        LogDensity.lkjCorrCholesky(l, x(0))
    val at = IArray(2.5, 1.0, 0.5, math.sqrt(0.75), 0.3, 0.4, math.sqrt(0.75))
    // The density reads the diagonal and nothing else, so the three off-diagonal entries have no
    // influence at all — a gradient of exactly zero, which is a stronger statement than a small one.
    val offDiagonal = Reverse.valueAndGradient(f, at).gradient
    agree(f, at, "lkjCorrCholesky")
      .and(check(offDiagonal(2) == 0.0, s"d/dL(1,0) = ${offDiagonal(2)}, expected exactly 0"))
      .and(check(offDiagonal(4) == 0.0, s"d/dL(2,0) = ${offDiagonal(4)}, expected exactly 0"))
      .and(check(offDiagonal(5) == 0.0, s"d/dL(2,1) = ${offDiagonal(5)}, expected exactly 0"))
  }

  pureTest("the vectorised normal has the gradient of the sum it stands for") {
    val vectorised = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        LogDensity.normal(IArray(0.4, -1.2, 2.0), r.vector(x(0), x(1), x(0)), x(2))
    val written = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        LogDensity.normal(r.const(0.4), x(0), x(2)) +
          LogDensity.normal(r.const(-1.2), x(1), x(2)) +
          LogDensity.normal(r.const(2.0), x(0), x(2))
    val at = IArray(0.1, 0.2, 1.7)
    val one = Reverse.valueAndGradient(vectorised, at)
    val other = Reverse.valueAndGradient(written, at)
    closeRel(one.value, other.value, 1e-14, "the values")
      .and(forEach((0 until at.length).toList) { i =>
        closeRel(one.gradient(i), other.gradient(i), 1e-13, s"d/dx($i)")
      })
      .and(agree(vectorised, at, "the vectorised normal"))
  }

  // --- the two models -------------------------------------------------------------------------

  private val ar1 = TestModels.ar1PoissonOrdinal()
  private val ar1Point = TestModels.ar1At()

  pureTest("ar1PoissonOrdinal: the tape against Dual, to 1e-12") {
    check(ar1Point.length == 52, s"${ar1Point.length} parameters, expected 52")
      .and(agree(ar1, ar1Point, "ar1PoissonOrdinal"))
  }

  /** The three interpreters agree to a relative 1e-14 and not bit for bit, and the reason is the
    * design rather than an error: a vectorised override is allowed to compute its primitive in a
    * different order from the default it replaces. `normalSum` is the case here — the default adds
    * `-0.5 z^2` term by term, the override sums `z^2` and scales once, because it needs that sum
    * for the adjoint of the scale anyway — and the two differ in the last two bits of a log
    * density near -4357. Nothing downstream depends on bit equality between interpreters: the
    * sampler reads the tape and nothing else.
    */
  pureTest("ar1PoissonOrdinal: the three interpreters give the same value") {
    val plain = Reverse.value(ar1, ar1Point)
    val taped = Reverse.valueAndGradient(ar1, ar1Point).value
    val dual = Dual.value(ar1, ar1Point)
    check(plain.isFinite, s"the model is $plain at its test point")
      .and(closeRel(taped, plain, 1e-14, "the tape against the Double interpreter"))
      .and(closeRel(dual, plain, 1e-14, "the dual numbers"))
  }

  pureTest("ar1PoissonOrdinal: central differences see the same gradient") {
    val result = GradientCheck(ar1, ar1Point, 1e-6)
    check(result.maxError <= 1e-6, s"central differences differ by ${result.maxError}")
  }

  test(
    "ar1PoissonOrdinal: gradients computed concurrently are the gradients computed in sequence"
  ) {
    val points = List.tabulate(16)(i =>
      IArray.from(ar1Point.toList.zipWithIndex.map((v, j) => v + 0.01 * ((i + j) % 7)))
    )
    val sequential = points.map(p => Reverse.valueAndGradient(ar1, p))
    points
      .parTraverse(p => IO(Reverse.valueAndGradient(ar1, p)))
      .map { concurrent =>
        forEach(sequential.zip(concurrent)) { (one, other) =>
          check(one.value == other.value, s"values ${one.value} and ${other.value}")
            .and(
              check(
                one.gradient.toList == other.gradient.toList,
                "the gradients differ bit for bit"
              )
            )
        }
      }
  }

  /** The criterion of the phase: ten thousand observations in fifty groups, in a number of tape
    * nodes that does not depend on the ten thousand.
    *
    * `gather` is index aliasing and pushes nothing; `normalSum` pushes one node whatever the length
    * of its argument. What is left is the handful of nodes the priors cost, which is why the three
    * counts are not merely similar but equal.
    */
  pureTest("varyingIntercepts: the tape does not grow with the data") {
    val at = TestModels.varyingAt(50)
    val small = Reverse.nodeCount(TestModels.varyingIntercepts(100, 50), at)
    val large = Reverse.nodeCount(TestModels.varyingIntercepts(10_000, 50), at)
    val huge = Reverse.nodeCount(TestModels.varyingIntercepts(100_000, 50), at)
    // The same model with one scalar predictor written out per observation, for the measurement
    // docs/validation.md records: three nodes an observation — the constant `predictor(i)` is
    // itself a leaf of the tape, then a multiplication and an addition — and a tape that grows.
    val withSlope = TestModels.varyingAt(50, withSlope = true)
    val slopeSmall = Reverse.nodeCount(TestModels.varyingInterceptsWithSlope(100, 50), withSlope)
    val slopeLarge = Reverse.nodeCount(TestModels.varyingInterceptsWithSlope(10_000, 50), withSlope)
    check(small == large, s"100 observations cost $small nodes and 10 000 cost $large")
      .and(check(large == huge, s"10 000 observations cost $large nodes and 100 000 cost $huge"))
      .and(check(large < 200, s"$large nodes for a model with four kinds of parameter"))
      .and(
        check(
          slopeLarge - slopeSmall == 3 * (10_000 - 100),
          s"a written-out slope cost ${slopeLarge - slopeSmall} nodes for 9 900 observations"
        )
      )
  }

  pureTest("varyingIntercepts: the tape against Dual, to 1e-12") {
    val at = TestModels.varyingAt(20)
    val model = TestModels.varyingIntercepts(1_000, 20)
    val withSlope = TestModels.varyingInterceptsWithSlope(1_000, 20)
    agree(model, at, "varyingIntercepts")
      .and(agree(withSlope, TestModels.varyingAt(20, withSlope = true), "with a slope"))
  }

  pureTest("varyingIntercepts: ten thousand observations, and the gradient is still exact") {
    val at = TestModels.varyingAt(50)
    val model = TestModels.varyingIntercepts(10_000, 50)
    agree(model, at, "varyingIntercepts at ten thousand observations")
  }
