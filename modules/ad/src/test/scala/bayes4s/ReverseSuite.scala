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

import bayes4s.syntax.{*, given}
import bayes4s.testkit.Approx
import cats.effect.IO
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The reverse mode against the dual-number oracle, primitive by primitive.
  *
  * The two share no code. The oracle inherits every vectorised primitive from the defaults of
  * `Real`, which are written in terms of the scalar operations; the reverse mode overrides them
  * with hand-written adjoints that push a single node. Comparing the two is therefore comparing a
  * hand-written derivative against the derivative of the composition it claims to equal, which is
  * exactly the thing that can be silently wrong.
  */
object ReverseSuite extends SimpleIOSuite with Approx:

  /** Every scalar operation, in one function, so one comparison covers them all. */
  private val scalars: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val a = x(0)
      val b = x(1)
      val c = x(2)
      a * b - a / c + exp(a) + log(b) + sqrt(c) + log1p(a) + expm1(a) +
        pow(b, 1.5) + invLogit(a) + logInvLogit(b) + log1mInvLogit(c) +
        log1pExp(a) + lgamma(b) + lbeta(b, c) + (-c) + a * 2.0 + 3.0.lift[A]

  /** A variable used by several paths: its adjoint must accumulate all of them. */
  private val reused: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val a = x(0)
      val first = a * a
      val second = exp(a)
      val third = log(a)
      first * second + second * third + first / third

  /** The second input is never read: its gradient must be exactly zero, not nearly. */
  private val ignoresSecond: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A = x(0) * x(0) + x(2)

  private val vectorised: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      val xs = IArray(x(0), x(1), x(2))
      val running = r.cumulativeSum(xs)
      r.sum(xs) + r.logSumExp(xs) + r.dot(xs, running) + r.sum(running)

  private val gathered: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      // Index 1 appears three times, index 2 never: repeated groups and an empty one.
      val index = IArray(0, 1, 1, 1, 0)
      val perRow = r.gather(IArray(x(0), x(1), x(2)), index)
      r.sum(perRow) + r.logSumExp(perRow)

  private val normals: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      val ys = IArray(0.4, -1.2, 2.0, 0.0)
      val mus = r.gather(IArray(x(0), x(1)), IArray(0, 1, 0, 1))
      val sigmas = r.gather(IArray(x(2), x(2)), IArray(0, 1, 0, 1))
      r.normalSum(IArray(x(0), x(1), x(2)), x(0), x(2)) +
        r.normalSum(ys, mus, x(2)) +
        r.normalSum(ys, mus, sigmas)

  private val counts: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      val ks = IArray(0, 3, 7, 1)
      val rates = r.gather(IArray(x(0), x(1)), IArray(0, 1, 0, 1))
      val logRates = IArray(x(2), x(2), x(0), x(1))
      val successes = IArray(1, 0, 1, 1)
      val trials = IArray(5, 5, 9, 2)
      r.poissonSum(ks, rates) + r.poissonLogSum(ks, logRates) +
        r.bernoulliLogitSum(successes, logRates) +
        r.binomialLogitSum(successes, trials, logRates)

  private val linear: DiffFn = new DiffFn:
    def apply[A: Real](x: IArray[A]): A =
      val r = Real[A]
      val l = IArray(
        IArray(x(0), r.const(0.0), r.const(0.0)),
        IArray(x(1), x(0), r.const(0.0)),
        IArray(x(2), x(1), x(0))
      )
      val b = IArray(r.const(1.0), r.const(-2.0), r.const(0.5))
      val solved = r.lowerSolve(l, b)
      val product = r.matVec(l, solved)
      r.sum(solved) + r.sum(product)

  private val cases: List[(String, DiffFn, IArray[Double])] = List(
    ("every scalar operation", scalars, IArray(0.7, 1.3, 2.1)),
    ("a variable on several paths", reused, IArray(1.4, 0.0, 0.0)),
    ("an input that is never read", ignoresSecond, IArray(1.1, -5.0, 0.3)),
    ("the vectorised sums", vectorised, IArray(0.3, -0.7, 1.9)),
    ("gather, with a repeated and an unused group", gathered, IArray(0.5, -1.0, 2.5)),
    ("the Gaussian sums, including per-observation means", normals, IArray(0.2, -0.4, 1.1)),
    ("the count sums", counts, IArray(2.5, 4.0, 0.3)),
    ("the triangular solve and the matrix-vector product", linear, IArray(1.5, 0.4, -0.8))
  )

  pureTest("the reverse mode agrees with the dual-number oracle, to 1e-12") {
    forEach(cases) { (label, f, at) =>
      val analytic = Reverse.valueAndGradient(f, at)
      val oracle = Dual.gradient(f, at)
      val values = closeRel(analytic.value, Dual.value(f, at), 1e-12, s"$label: the value")
      val gradients = forEach((0 until at.length).toList) { i =>
        closeRel(analytic.gradient(i), oracle(i), 1e-12, s"$label: component $i")
      }
      values.and(gradients)
    }
  }

  pureTest("the three interpreters agree on the value") {
    forEach(cases) { (label, f, at) =>
      closeRel(Reverse.value(f, at), Dual.value(f, at), 1e-15, s"$label: Double against Dual")
        .and(
          closeRel(
            Reverse.valueAndGradient(f, at).value,
            Reverse.value(f, at),
            1e-15,
            s"$label: the tape against Double"
          )
        )
    }
  }

  pureTest("an input that is never read has a gradient of exactly zero") {
    val gradient = Reverse.valueAndGradient(ignoresSecond, IArray(1.1, -5.0, 0.3)).gradient
    check(gradient(1) == 0.0, s"the unused input has gradient ${gradient(1)}, expected exactly 0")
      .and(check(gradient(2) == 1.0, s"the linear input has gradient ${gradient(2)}, expected 1"))
  }

  // The specification calls this out: adding a small constant to the rate to avoid `0 log 0` is
  // the common workaround and it moves the answer. The term is not formed instead, which is why
  // the gradient at a rate of zero is exactly -1 and the value is exactly 0.
  pureTest("poissonSum is exact at a rate of 0, 1e-300 and 1e-9, in value and in gradient") {
    val zeroCount: DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A = Real[A].poissonSum(IArray(0), IArray(x(0)))
    val oneCount: DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A = Real[A].poissonSum(IArray(1), IArray(x(0)))
    val atZero = Reverse.valueAndGradient(zeroCount, IArray(0.0))
    val positiveAtZero = Reverse.valueAndGradient(oneCount, IArray(0.0))
    val tails = forEach(List(1e-300, 1e-9)) { rate =>
      val got = Reverse.valueAndGradient(zeroCount, IArray(rate))
      val oracle = Dual.gradient(zeroCount, IArray(rate))
      closeRel(got.value, -rate, 1e-15, s"the value of a zero count at a rate of $rate")
        .and(closeRel(got.gradient(0), oracle(0), 1e-12, s"its gradient at a rate of $rate"))
        .and(check(got.gradient(0) == -1.0, s"which is exactly -1, not ${got.gradient(0)}"))
    }
    // The tape has its own `poissonSum`, so the three assertions above say nothing about the
    // default that `Real[Double]` and the oracle inherit — and that default is the definition of
    // the primitive. It is read here at the rate that distinguishes the two ways of writing it:
    // `0 * log(0)` is `NaN`, and not forming the term is what makes the value 0 and the
    // derivative exactly -1.
    val defaults = check(
      Reverse.value(zeroCount, IArray(0.0)) == 0.0,
      s"the Double interpreter gives ${Reverse.value(zeroCount, IArray(0.0))} at a rate of zero"
    ).and(
      check(
        Dual.value(zeroCount, IArray(0.0)) == 0.0,
        s"the oracle gives ${Dual.value(zeroCount, IArray(0.0))} at a rate of zero"
      )
    ).and(
      check(
        Dual.gradient(zeroCount, IArray(0.0))(0) == -1.0,
        s"the oracle's derivative is ${Dual.gradient(zeroCount, IArray(0.0))(0)}, expected -1"
      )
    ).and(
      check(
        Reverse.value(oneCount, IArray(0.0)) == Double.NegativeInfinity,
        "a positive count at a rate of zero should be -Inf through the Double interpreter too"
      )
    )
    check(atZero.value == 0.0, s"a zero count at a rate of zero has value ${atZero.value}")
      .and(check(atZero.gradient(0) == -1.0, s"and gradient ${atZero.gradient(0)}, expected -1"))
      .and(defaults)
      .and(
        check(
          positiveAtZero.value == Double.NegativeInfinity,
          s"a positive count at a rate of zero has value ${positiveAtZero.value}, expected -Inf"
        )
      )
      .and(tails)
  }

  pureTest("a tape of 200 000 operations works") {
    val long: DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        (1 to 100_000).foldLeft(Real[A].const(0.0))((acc, _) => acc + x(0) * 1e-5)
    val at = IArray(3.0)
    val got = Reverse.valueAndGradient(long, at)
    val nodes = Reverse.nodeCount(long, at)
    check(nodes >= 200_000, s"the tape holds $nodes nodes, expected at least 200 000")
      .and(closeRel(got.value, 3.0, 1e-9, "the value after 100 000 additions"))
      .and(closeRel(got.gradient(0), 1.0, 1e-9, "the gradient after 100 000 additions"))
  }

  // Ten thousand observations in fifty groups. The node count must not grow with the observations:
  // `gather` aliases rather than allocating, and one vectorised density is one node, so what the
  // tape records is the shape of the model and not the size of the data.
  pureTest("a model with 10 000 observations has a node count that does not depend on 10 000") {
    def model(observations: Int, groups: Int): DiffFn = new DiffFn:
      def apply[A: Real](x: IArray[A]): A =
        val r = Real[A]
        val index = IArray.tabulate(observations)(i => i % groups)
        val ys = IArray.tabulate(observations)(i => math.sin(i.toDouble))
        val alpha = IArray.tabulate(groups)(g => x(g))
        val mus = r.gather(alpha, index)
        r.normalSum(ys, mus, x(groups)) + r.normalSum(alpha, x(groups + 1), x(groups))
      end apply
    val groups = 50
    val at = IArray.tabulate(groups + 2)(i => if i == groups then 1.0 else 0.1 * i)
    val small = Reverse.nodeCount(model(100, groups), at)
    val large = Reverse.nodeCount(model(10_000, groups), at)
    val huge = Reverse.nodeCount(model(100_000, groups), at)
    check(
      small == large && large == huge,
      s"the tape holds $small nodes for 100 observations, $large for 10 000 and $huge for 100 000"
    ).and(check(large < 200, s"$large nodes for a model of 52 parameters is more than expected"))
      .and {
        val gradient = Reverse.valueAndGradient(model(10_000, groups), at).gradient
        val oracle = Dual.gradient(model(10_000, groups), at)
        forEach((0 until at.length).toList) { i =>
          closeRel(gradient(i), oracle(i), 1e-12, s"component $i of the large model")
        }
      }
  }

  test("gradients computed in parallel equal the gradients computed in sequence") {
    val at = IArray(0.7, 1.3, 2.1)
    val work = (0 until 64).toList
    for
      sequential <- IO(
        work.map(i => Reverse.valueAndGradient(scalars, at.map(_ + i * 1e-3)).gradient.toList)
      )
      parallel <- work.parTraverse(i =>
        IO(Reverse.valueAndGradient(scalars, at.map(_ + i * 1e-3)).gradient.toList)
      )
    yield check(parallel == sequential, "the parallel gradients differ from the sequential ones")
  }

  pureTest("finite differences agree at well-conditioned points, and catch a planted error") {
    val agreement = forEach(cases.filterNot(_._1.contains("count"))) { (label, f, at) =>
      val result = GradientCheck(f, at)
      check(result.maxError < 1e-5, s"$label: the largest disagreement is ${result.maxError}")
    }
    // The detector itself: a gradient that is wrong in one component must be reported as such.
    val planted = GradientCheckResult(IArray(1.0, 2.0, 3.0), IArray(1.0, 2.0, 3.5))
    val exact = GradientCheckResult(IArray(1.0, 2.0, 3.0), IArray(1.0, 2.0, 3.0))
    agreement
      .and(closeRel(planted.maxError, 0.5 / 3.5, 1e-12, "the planted error is reported"))
      .and(check(exact.maxError == 0.0, s"an exact gradient reports ${exact.maxError}"))
  }

  pureTest("two calls on the same arguments give the same answer, and leave the input alone") {
    val at = IArray(0.7, 1.3, 2.1)
    val before = at.toList
    val first = Reverse.valueAndGradient(scalars, at)
    val second = Reverse.valueAndGradient(scalars, at)
    check(first.value == second.value, "the values differ")
      .and(check(first.gradient.toList == second.gradient.toList, "the gradients differ"))
      .and(check(at.toList == before, "the input was modified"))
  }
