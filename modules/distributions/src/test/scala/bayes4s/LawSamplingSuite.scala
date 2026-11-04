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

import bayes4s.testkit.Approx
import bayes4s.testkit.MonteCarlo
import cats.Monad
import weaver.SimpleIOSuite

/** Each law's sampler against that same law's own mean and variance.
  *
  * This is the one place where the two readings of a distribution have to agree: the closed form
  * that `mean` and `variance` report, and the numbers `sample` actually produces. A sampler that
  * drew from a slightly different distribution would pass every density test in the suite and fail
  * here. Threshold: 4 standard errors, announced in each message.
  */
object LawSamplingSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val n = 100_000
  private val seed = Seed(20261104L)

  private def draws[A](program: Rand[A], from: Seed): List[A] =
    Monad[Rand].replicateA(n, program).runA(from).value

  private def average(xs: List[Double]): Double = xs.sum / xs.length

  /** The standard error of a sample variance, from the sample's own fourth moment:
    * sqrt((m4 - s^4) / n). No assumption about the law, which matters here because a gamma of
    * shape 0.05 and a Poisson of mean 0.0025 have fourth moments that the normal formula
    * sigma^2 sqrt(2/n) underestimates by factors of three and five.
    */
  private def varianceStandardError(xs: List[Double]): Double =
    val m = xs.sum / xs.length
    val squares = xs.map(x => (x - m) * (x - m))
    val second = squares.sum / xs.length
    val fourth = squares.map(s2 => s2 * s2).sum / xs.length
    math.sqrt(math.max(0.0, fourth - second * second) / xs.length)

  private def spread(xs: List[Double]): Double =
    val m = average(xs)
    xs.map(x => (x - m) * (x - m)).sum / (xs.length - 1)

  private def continuous(
      name: String,
      law: Either[BayesError.InvalidArgument, ContinuousLaw],
      index: Long
  ) =
    law match
      case Left(error) => failure(error.message)
      case Right(l) =>
        val xs = draws(l.sample[Rand], seed.fold(index))
        val standardError = math.sqrt(l.variance / n)
        withinStandardErrors(average(xs), l.mean, standardError, 4.0, s"the mean of $name").and(
          withinStandardErrors(
            spread(xs),
            l.variance,
            varianceStandardError(xs),
            4.0,
            s"the variance of $name"
          )
        )

  private def discrete(
      name: String,
      law: Either[BayesError.InvalidArgument, DiscreteLaw],
      index: Long
  ) =
    law match
      case Left(error) => failure(error.message)
      case Right(l) =>
        val xs = draws(l.sample[Rand], seed.fold(index)).map(_.toDouble)
        withinStandardErrors(
          average(xs),
          l.mean,
          math.sqrt(l.variance / n),
          4.0,
          s"the mean of $name"
        ).and(
          withinStandardErrors(
            spread(xs),
            l.variance,
            varianceStandardError(xs),
            4.0,
            s"the variance of $name"
          )
        ).and(check(xs.forall(_ >= 0.0), s"$name produced a negative count"))

  pureTest("the continuous laws sample what they claim") {
    continuous("Normal(1.5, 2)", Normal.of(1.5, 2.0), 1L)
      .and(continuous("HalfNormal(2)", HalfNormal.of(2.0), 2L))
      .and(continuous("LogNormal(0, 0.5)", LogNormal.of(0.0, 0.5), 3L))
      .and(continuous("Gamma(0.4, 2)", Gamma.of(0.4, 2.0), 4L))
      .and(continuous("Gamma(9, 0.5)", Gamma.of(9.0, 0.5), 5L))
      .and(continuous("Beta(2, 5)", Beta.of(2.0, 5.0), 6L))
      .and(continuous("Beta(0.5, 0.5)", Beta.of(0.5, 0.5), 7L))
      .and(continuous("Logistic(-1, 3)", Logistic.of(-1.0, 3.0), 8L))
  }

  pureTest("the discrete laws sample what they claim") {
    discrete("Poisson(3.5)", Poisson.of(3.5), 11L)
      .and(discrete("Poisson(250)", Poisson.of(250.0), 12L))
      .and(discrete("PoissonLog(1.2)", PoissonLog.of(1.2), 13L))
      .and(discrete("NegBinomial2(4, 2)", NegBinomial2.of(4.0, 2.0), 14L))
      .and(discrete("NegBinomial(8.5, 1.2)", NegBinomial.of(8.5, 1.2), 15L))
      .and(discrete("Binomial(20, 0.3)", Binomial.of(20, 0.3), 16L))
  }

  pureTest("a law's mass function sums to one over its support") {
    val cases = List(
      ("Poisson(3.5)", Poisson.of(3.5).map(l => (k: Int) => l.logMass(k)), 200),
      ("NegBinomial2(4, 2)", NegBinomial2.of(4.0, 2.0).map(l => (k: Int) => l.logMass(k)), 400),
      ("NegBinomial(8.5, 1.2)", NegBinomial.of(8.5, 1.2).map(l => (k: Int) => l.logMass(k)), 400),
      ("Binomial(20, 0.3)", Binomial.of(20, 0.3).map(l => (k: Int) => l.logMass(k)), 20)
    )
    forEach(cases) { (name, built, bound) =>
      built match
        case Left(error) => failure(error.message)
        case Right(mass) =>
          val total = (0 to bound).map(k => math.exp(mass(k))).sum
          closeRel(total, 1.0, 1e-12, s"the total mass of $name up to $bound")
    }
  }

  pureTest("the categorical and ordered logistic masses sum to one and sample in range") {
    val categorical = Categorical.of(IArray(0.1, 0.2, 0.05, 0.4, 0.25)) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        val total = (0 until 5).map(k => math.exp(law.logMass(k))).sum
        val xs = draws(law.sample[Rand], seed.fold(21L))
        closeRel(total, 1.0, 1e-12, "the total mass of the categorical")
          .and(check(xs.forall(k => k >= 0 && k < 5), "an index was out of range"))
          .and(check(law.logMass(5) == Double.NegativeInfinity, "an index past the end is -Inf"))
          .and(check(law.logMass(-1) == Double.NegativeInfinity, "a negative index is -Inf"))
    val ordered = OrderedLogistic.of(0.4, IArray(-1.0, 0.0, 1.5)) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        val total = (1 to 4).map(k => math.exp(law.logMass(k))).sum
        val xs = draws(law.sample[Rand], seed.fold(22L))
        closeRel(total, 1.0, 1e-12, "the total mass of the ordered logistic")
          .and(check(xs.forall(k => k >= 1 && k <= 4), "a category was out of range"))
          .and(check(law.logMass(0) == Double.NegativeInfinity, "category 0 does not exist"))
          .and(check(law.logMass(5) == Double.NegativeInfinity, "category 5 does not exist"))
    categorical.and(ordered)
  }

  pureTest("the Dirichlet density is zero off the simplex and its mean is right") {
    Dirichlet.of(IArray(2.0, 3.0, 5.0)) match
      case Left(error) => failure(error.message)
      case Right(law) =>
        val mean = law.mean
        check(law.logPdf(IArray(0.5, 0.2, 0.4)) == Double.NegativeInfinity, "off the simplex")
          .and(check(law.logPdf(IArray(0.2, 0.3, 0.5)).isFinite, "on the simplex"))
          .and(check(law.logPdf(IArray(0.5, 0.5)) == Double.NegativeInfinity, "wrong dimension"))
          .and(closeRel(mean(0), 0.2, 1e-12, "the first component of the mean"))
          .and(closeRel(mean(2), 0.5, 1e-12, "the third component of the mean"))
  }

  pureTest("the smart constructors refuse what the laws cannot represent") {
    check(HalfNormal.of(0.0).isLeft, "HalfNormal accepted sigma = 0")
      .and(check(Binomial.of(-1, 0.5).isLeft, "Binomial accepted a negative number of trials"))
      .and(check(Binomial.of(10, 1.5).isLeft, "Binomial accepted a probability above one"))
      .and(check(Binomial.of(10, 0.0).isRight, "Binomial refused a probability of zero"))
      .and(
        check(Categorical.of(IArray(0.5, 0.2)).isLeft, "Categorical accepted masses summing to 0.7")
      )
      .and(check(Categorical.of(IArray.empty[Double]).isLeft, "Categorical accepted no categories"))
      .and(
        check(
          OrderedLogistic.of(0.0, IArray(1.0, 0.0)).isLeft,
          "OrderedLogistic accepted unordered cuts"
        )
      )
      .and(check(Dirichlet.of(IArray(1.0)).isLeft, "Dirichlet accepted a single component"))
      .and(
        check(Dirichlet.of(IArray(1.0, -1.0)).isLeft, "Dirichlet accepted a negative concentration")
      )
  }
