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
import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import weaver.SimpleIOSuite

/** The densities of `LogDensity` and `LogMass`, read by the `Double` interpreter.
  *
  * Three kinds of reference, and the distinction matters:
  *
  *   - **Stan Math**, through the same fixture the law layer is judged by: the 53 valid cases and
  *     the invalid parameters of the fifteen laws in scope. This is what pins this layer onto an
  *     independent implementation rather than onto our own.
  *   - **mpmath at 50 digits**, for the four densities Stan has no test for: the three half laws,
  *     which Stan writes as a truncation rather than a law, and the ordered logistic, whose
  *     `*_lpmf` the fixture does not carry. The values are quoted with the expression that
  *     produced them, so they can be recomputed.
  *   - **the law layer**, for the two-layer agreement. That is not an independent reference and is
  *     not used as one: it says that the formula validated in phases 1 and 1b is the formula this
  *     layer evaluates, which is the only thing that lets the earlier validation carry over.
  */
object LogDensitySuite extends SimpleIOSuite with Approx:

  /** Stan Math's own value is wrong for `neg_binomial_lpmf(10 | 1e6, 1e5)`; the exact value is
    * asserted instead, for the reason `StanMathDensitySuite` records at length.
    */
  private val corrected: Map[(String, String, List[Double]), (Double, Double)] = Map(
    ("neg_binomial", "lpdf", List(10.0, 1000000.0, 100000.0)) -> (-2.078566643110892, 1e-14)
  )

  private def realValue(law: String, function: String, p: IArray[Double]): Either[String, Double] =
    realCase(law, function, p).map(c => Reverse.value(c.fn, c.inputs))

  pureTest("the 53 density cases of Stan Math, through Real[Double]") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val cases = for
          (law, functions) <- fixture.laws.toList.sortBy(_._1)
          if lawsInScope(law)
          (function, body) <- functions.toList.sortBy(_._1)
          if densityFamily(law, function)
          valid <- body.valid
        yield (law, function, valid)
        val assertions = forEach(cases) { (law, function, valid) =>
          val arguments = valid.params.toList.mkString(", ")
          val (expected, tolerance) =
            corrected.getOrElse((law, function, valid.params.toList), (valid.expected, 1e-12))
          realValue(law, function, valid.params) match
            case Left(message) => failure(s"$law.$function($arguments): $message")
            case Right(got) => closeRel(got, expected, tolerance, s"$law.$function($arguments)")
        }
        check(cases.length == 53, s"${cases.length} density cases, expected 53").and(assertions)
  }

  pureTest("the 72 invalid parameters of the density families give NaN") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val wanted = for
          (law, functions) <- fixture.laws.toList
          if lawsInScope(law)
          (function, body) <- functions.toList
          if densityFamily(law, function)
          invalid <- body.invalid
          if invalid.index > 0
          ours <- parameterName(law, function, body.paramNames(invalid.index)).toList
          params <- base(fixture, law, function).toList
        yield (
          law,
          function,
          ours,
          invalid.value,
          substituted(params, invalid.index, invalid.value)
        )
        val distinct = wanted.distinctBy((law, _, name, value, _) => (law, name, value))
        val assertions = forEach(distinct) { (law, function, name, value, params) =>
          realValue(law, function, params) match
            case Left(message) => failure(message)
            case Right(got) =>
              check(
                got.isNaN,
                s"$law.$function with $name = $value gave $got, expected NaN"
              )
        }
        check(distinct.length == 72, s"${distinct.length} distinct invalid parameters, expected 72")
          .and(assertions)
  }

  pureTest("the 14 out-of-support variates give -Inf, or NaN for a NaN") {
    Fixtures.stanMath match
      case Left(message) => failure(message)
      case Right(fixture) =>
        val wanted = for
          (law, functions) <- fixture.laws.toList
          if lawsInScope(law)
          (function, body) <- functions.toList
          if densityFamily(law, function)
          invalid <- body.invalid
          if invalid.index == 0
          params <- base(fixture, law, function).toList
        yield (law, function, invalid.value, substituted(params, 0, invalid.value))
        val assertions = forEach(wanted) { (law, function, value, params) =>
          realValue(law, function, params) match
            case Left(message) => failure(s"$law.$function: $message")
            case Right(got) =>
              if value.isNaN then
                check(got.isNaN, s"$law.$function at a NaN variate gave $got, expected NaN")
              else
                check(
                  got == Double.NegativeInfinity,
                  s"$law.$function at a variate of $value gave $got, expected -Inf"
                )
        }
        check(wanted.length == 14, s"${wanted.length} out-of-support cases, expected 14")
          .and(assertions)
  }

  /** The three half laws, at 50 digits.
    *
    * Stan has no `half_normal_lpdf` — a half law is written there as a normal with a lower bound,
    * which is a property of the parameter and not of the density — so the fixture carries nothing
    * for them and the reference is computed instead:
    *
    * {{{
    * from mpmath import mp, mpf, log, exp, pi, loggamma, log1p
    * mp.dps = 50
    * log(2) - log(s) - log(2*pi)/2 - (x/s)**2/2                      # halfNormal
    * log(2) + student_t(x, nu, 0, s)                                 # halfStudentT
    * log(2) - log(pi) - log(s) - log1p((x/s)**2)                     # halfCauchy
    * }}}
    */
  pureTest("the three half laws against mpmath at 50 digits") {
    val cases = List(
      ("halfNormal(0 | 1)", LogDensity.halfNormal(0.0, 1.0), -0.22579135264472743236),
      ("halfNormal(1.3 | 2.5)", LogDensity.halfNormal(1.3, 2.5), -1.2772820845188825068),
      ("halfNormal(5 | 0.5)", LogDensity.halfNormal(5.0, 0.5), -49.532644172084782123),
      ("halfStudentT(0 | 3, 1)", LogDensity.halfStudentT(0.0, 3.0, 1.0), -0.30774166906356440101),
      (
        "halfStudentT(2.5 | 7, 1.5)",
        LogDensity.halfStudentT(2.5, 7.0, 1.5),
        -2.0036604304662901739
      ),
      (
        "halfStudentT(40 | 2, 1)",
        LogDensity.halfStudentT(40.0, 2.0, 1.0),
        -10.375365010882511486
      ),
      ("halfCauchy(0 | 1)", LogDensity.halfCauchy(0.0, 1.0), -0.45158270528945486473),
      ("halfCauchy(3 | 0.5)", LogDensity.halfCauchy(3.0, 0.5), -3.3693534373737339997),
      ("halfCauchy(1e6 | 2)", LogDensity.halfCauchy(1.0e6, 2.0), -27.389456640662057764)
    )
    forEach(cases)((label, got, expected) => closeRel(got, expected, 1e-12, label))
  }

  /** The ordered logistic, at 50 digits, and the form it is not written in.
    *
    * {{{
    * from mpmath import mp, mpf, log, exp
    * mp.dps = 50
    * il = lambda a: 1/(1+exp(-a))
    * log(1 - il(eta - c[0]))                   # k = 1
    * log(il(eta - c[k-2]) - il(eta - c[k-1]))  # 1 < k < K
    * log(il(eta - c[K-2]))                     # k = K
    * }}}
    *
    * The saturated cases are the ones that decide how a middle category is computed. Taking the
    * difference of two logistic functions in the probability domain — the way the definition reads
    * — gives -29.752084249558838 against -29.752482458925643 for the second of four categories at
    * a log odds of 30: 1.3e-5 relative, where this library asserts 1e-12, and still 9.4e-12 at a
    * log odds of 15. Both probabilities are then within 1e-13 of one, and their difference keeps
    * three of the sixteen digits they each have. The difference is taken through `expm1` of the
    * gap between the cut points instead, which never subtracts two numbers that nearly agree.
    *
    * Two cut points a rounding apart are in the list for the opposite reason: there the naive
    * difference happens to be exact, because five units in the last place of 1 is five units in
    * the last place of a half as well. A single tiny-gap case would have let the naive form pass.
    */
  pureTest("the ordered logistic against mpmath at 50 digits") {
    val cuts = IArray(-1.0, 0.5, 2.0)
    val ordinary = List(
      (1, -1.5410084538329921965),
      (2, -1.0916297821400380384),
      (3, -1.2184073576933117571),
      (4, -1.8677860293862659264)
    )
    // A saturated middle category is where the two forms part company: both logistic functions are
    // within 1e-13 of one, so their difference keeps three digits and the log keeps five, while
    // the form through `expm1` keeps all of them. The error is already 9.4e-12 at a log odds of 15.
    val extreme = List(
      ("k = 2 at a log odds of 30", LogDensity.orderedLogistic(2, 30.0, cuts), -29.752482458925643),
      ("k = 3 at a log odds of 30", LogDensity.orderedLogistic(3, 30.0, cuts), -28.252482458926300),
      ("k = 2 at a log odds of 15", LogDensity.orderedLogistic(2, 15.0, cuts), -14.752483075808158),
      ("k = 3 at a log odds of 15", LogDensity.orderedLogistic(3, 15.0, cuts), -13.252485223599842),
      ("k = 1 at a log odds of 30", LogDensity.orderedLogistic(1, 30.0, cuts), -31.000000000000034),
      (
        "k = 4 at a log odds of -30",
        LogDensity.orderedLogistic(4, -30.0, cuts),
        -32.000000000000013
      ),
      (
        "k = 2 between two cut points a rounding apart",
        LogDensity.orderedLogistic(2, 1.0, IArray(1.0, 1.0 + 1.0e-15, 3.0)),
        -35.820509837802946
      ),
      (
        "k = 2 between two cut points 1e-10 apart",
        LogDensity.orderedLogistic(2, 1.0, IArray(1.0, 1.0 + 1.0e-10, 3.0)),
        -24.412145208319980
      ),
      // The two cases `LogDensityTotalitySuite` found, kept here as values rather than as a
      // property: a gap of 7e9 between the cut points overflows anything written through
      // `expm1(gap)`, and the first of them is a category that holds all of the mass, whose log
      // mass is zero and whose wrong answer was `+Inf` — a probability above one.
      (
        "k = 2 with a gap of 7.07e9 between the cut points, holding all the mass",
        LogDensity.orderedLogistic(2, 1.6324384589344025e7, IArray(1.0, 7.068178184797911e9)),
        0.0
      ),
      (
        "k = 2 with a gap of 7e9, far below both cut points",
        LogDensity.orderedLogistic(2, -5.0e8, IArray(1.0, 7.0e9)),
        -500000001.0
      )
    )
    forEach(ordinary) { (k, expected) =>
      closeRel(LogDensity.orderedLogistic(k, 0.3, cuts), expected, 1e-12, s"k = $k at eta = 0.3")
    }.and(forEach(extreme)((label, got, expected) => closeRel(got, expected, 1e-12, label)))
  }

  pureTest("the categories of an ordered logistic are a distribution, and nothing else is") {
    val cuts = IArray(-2.0, -0.5, 0.5, 3.0)
    val etas = List(-40.0, -3.0, 0.0, 0.7, 40.0)
    val sums = forEach(etas) { eta =>
      val masses = IArray.tabulate(5)(i => LogDensity.orderedLogistic(i + 1, eta, cuts))
      closeRel(LogExp.logSumExp(masses), 0.0, 1e-13, s"the five categories at eta = $eta sum to 1")
    }
    val outside = forEach(List(0, -1, 6, 100)) { k =>
      check(
        LogDensity.orderedLogistic(k, 0.3, cuts) == Double.NegativeInfinity,
        s"category $k is outside 1..5 and should have no mass"
      )
    }
    // Equal cut points leave their category no room at all: an exact zero probability.
    val empty = check(
      LogDensity.orderedLogistic(2, 0.3, IArray(1.0, 1.0, 2.0)) == Double.NegativeInfinity,
      "a category between two equal cut points should have no mass"
    )
    sums.and(outside).and(empty)
  }

  /** The two layers evaluate the same formula.
    *
    * This is not an independent reference and does not pretend to be one: it is what carries the
    * Stan Math and mpmath validation of the law layer over to the layer the models are written
    * against. A law whose constructor refuses its arguments would make the comparison vacuous, so
    * the law's own value is asserted finite first.
    */
  pureTest("every law agrees with its Real[A] density") {
    def of[L](built: Either[BayesError.InvalidArgument, L])(f: L => Double): Double =
      built.fold(_ => Double.NaN, f)
    val cases: List[(String, Double, Double)] = List(
      ("normal", of(Normal.of(0.3, 2.0))(_.logPdf(1.1)), LogDensity.normal(1.1, 0.3, 2.0)),
      ("halfNormal", of(HalfNormal.of(1.4))(_.logPdf(0.6)), LogDensity.halfNormal(0.6, 1.4)),
      (
        "logNormal",
        of(LogNormal.of(-0.2, 0.7))(_.logPdf(2.3)),
        LogDensity.logNormal(2.3, -0.2, 0.7)
      ),
      ("gamma", of(Gamma.of(2.5, 1.3))(_.logPdf(0.8)), LogDensity.gamma(0.8, 2.5, 1.3)),
      ("beta", of(Beta.of(2.0, 5.0))(_.logPdf(0.25)), LogDensity.beta(0.25, 2.0, 5.0)),
      ("logistic", of(Logistic.of(0.5, 1.7))(_.logPdf(-0.4)), LogDensity.logistic(-0.4, 0.5, 1.7)),
      (
        "studentT",
        of(StudentT.of(4.0, 0.2, 1.1))(_.logPdf(2.2)),
        LogDensity.studentT(2.2, 4.0, 0.2, 1.1)
      ),
      ("cauchy", of(Cauchy.of(0.1, 2.0))(_.logPdf(-3.0)), LogDensity.cauchy(-3.0, 0.1, 2.0)),
      (
        "halfStudentT",
        of(HalfStudentT.of(3.0, 1.5))(_.logPdf(2.0)),
        LogDensity.halfStudentT(2.0, 3.0, 1.5)
      ),
      ("halfCauchy", of(HalfCauchy.of(2.5))(_.logPdf(1.0)), LogDensity.halfCauchy(1.0, 2.5)),
      ("exponential", of(Exponential.of(0.7))(_.logPdf(3.1)), LogDensity.exponential(3.1, 0.7)),
      ("poisson", of(Poisson.of(3.2))(_.logMass(4)), LogMass.poisson(4, 3.2)),
      ("poissonLog", of(PoissonLog.of(1.2))(_.logMass(4)), LogMass.poissonLog(4, 1.2)),
      (
        "negativeBinomial2",
        of(NegBinomial2.of(3.0, 2.5))(_.logMass(5)),
        LogMass.negativeBinomial2(5, 3.0, 2.5)
      ),
      (
        "negativeBinomial",
        of(NegBinomial.of(2.0, 0.8))(_.logMass(5)),
        LogMass.negativeBinomial(5, 2.0, 0.8)
      ),
      ("binomial", of(Binomial.of(12, 0.3))(_.logMass(4)), LogMass.binomial(4, 12, 0.3)),
      (
        "bernoulliLogit",
        of(BernoulliLogit.of(-1.3))(_.logMass(1)),
        LogMass.bernoulliLogit(1, -1.3)
      ),
      (
        "binomialLogit",
        of(BinomialLogit.of(12, -1.3))(_.logMass(4)),
        LogMass.binomialLogit(4, 12, -1.3)
      ),
      (
        "betaBinomial",
        of(BetaBinomial.of(12, 2.0, 3.0))(_.logMass(4)),
        LogMass.betaBinomial(4, 12, 2.0, 3.0)
      )
    )
    forEach(cases) { (name, fromLaw, fromReal) =>
      check(fromLaw.isFinite, s"$name: the law gave $fromLaw, so the comparison asserts nothing")
        .and(closeRel(fromReal, fromLaw, 1e-14, s"$name: the two layers disagree"))
    }
  }

  pureTest("the 13 multivariate Gaussian cases of multilevel.json, through Real[Double]") {
    Fixtures.multiNormalCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          closeRel(LogDensity.multiNormalCholesky(c.y, c.mu, c.l), c.value, 1e-12, c.label)
        }
        check(cases.length == 13, s"${cases.length} cases, expected 13").and(assertions)
  }

  pureTest("the 9 LKJ densities and the 20 normalising constants, through Real[Double]") {
    val densities = Fixtures.lkjCholesky match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          closeRel(
            LogDensity.lkjCorrCholesky(c.l, c.eta),
            c.value,
            1e-12,
            s"density at K = ${c.k}, eta = ${c.eta}"
          )
        }
        check(cases.length == 9, s"${cases.length} cases, expected 9").and(assertions)
    // The constant is reached through the density of the identity factor, whose diagonal is all
    // ones: every log of the diagonal is zero, so what is left is the constant alone.
    val constants = Fixtures.lkjConstants match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val identity =
            IArray.tabulate(c.k)(i => IArray.tabulate(c.k)(j => if i == j then 1.0 else 0.0))
          closeRel(
            LogDensity.lkjCorrCholesky(identity, c.eta),
            c.constant,
            1e-12,
            s"constant at K = ${c.k}, eta = ${c.eta}"
          )
        }
        check(cases.length == 20, s"${cases.length} constants, expected 20").and(assertions)
    densities.and(constants)
  }

  pureTest("a multivariate Gaussian whose factor is not a factor gives NaN") {
    val mu = IArray(0.0, 0.0)
    val y = IArray(0.5, -0.5)
    val notLower = IArray(IArray(1.0, 0.5), IArray(0.0, 1.0))
    val zeroDiagonal = IArray(IArray(1.0, 0.0), IArray(0.5, 0.0))
    val negativeDiagonal = IArray(IArray(1.0, 0.0), IArray(0.5, -1.0))
    val good = IArray(IArray(1.0, 0.0), IArray(0.5, 1.0))
    check(LogDensity.multiNormalCholesky(y, mu, notLower).isNaN, "an upper entry was accepted")
      .and(
        check(
          LogDensity.multiNormalCholesky(y, mu, zeroDiagonal).isNaN,
          "a zero pivot was accepted"
        )
      )
      .and(
        check(
          LogDensity.multiNormalCholesky(y, mu, negativeDiagonal).isNaN,
          "a negative pivot was accepted"
        )
      )
      .and(
        check(
          LogDensity.multiNormalCholesky(IArray(0.0, 0.0, 0.0), mu, good).isNaN,
          "a variate of the wrong length was accepted"
        )
      )
      .and(
        check(
          LogDensity.multiNormalCholesky(IArray(Double.NaN, 0.0), mu, good).isNaN,
          "a NaN variate did not give NaN"
        )
      )
  }

  /** The vectorised overload is the one a model with a group effect calls, and it has to be the
    * same number as the sum it stands for.
    */
  pureTest("the vectorised normal is the sum of its terms") {
    val ys = IArray(0.4, -1.2, 2.0, 0.0, 3.3)
    val mus = IArray(0.1, 0.2, -0.3, 0.0, 1.0)
    val sigma = 1.7
    val summed = (0 until ys.length).foldLeft(0.0) { (acc, i) =>
      acc + LogDensity.normal(ys(i), mus(i), sigma)
    }
    closeRel(LogDensity.normal(ys, mus, sigma), summed, 1e-14, "the vectorised normal")
  }

  /** A Poisson cell whose rate has collapsed: the term `k log(rate)` is never formed when the
    * count is zero, so there is nothing to be `NaN` and no constant to add to the rate.
    */
  pureTest("a Poisson mass at rates 0, 1e-300 and 1e-9") {
    val zero = check(LogMass.poisson(0, 0.0) == 0.0, s"poisson(0 | 0) = ${LogMass.poisson(0, 0.0)}")
      .and(
        check(
          LogMass.poisson(1, 0.0) == Double.NegativeInfinity,
          "poisson(1 | 0) should have no mass"
        )
      )
    val tiny = forEach(List(1.0e-300, 1.0e-9)) { rate =>
      closeRel(LogMass.poisson(0, rate), -rate, 1e-15, s"poisson(0 | $rate)")
        .and(
          closeRel(
            LogMass.poisson(2, rate),
            2.0 * math.log(rate) - rate - math.log(2.0),
            1e-14,
            s"poisson(2 | $rate)"
          )
        )
    }
    zero.and(tiny)
  }

  /** Stan rejects an infinite scale for some laws and not for others; this library refuses it
    * everywhere, in the constructors (1b) and here. The fixture asserts the cases Stan asserts, so
    * these are stated separately rather than left to it.
    */
  pureTest("an infinite parameter is invalid everywhere, not only where Stan says so") {
    val cases = List(
      ("normal sigma", LogDensity.normal(0.0, 0.0, Double.PositiveInfinity)),
      ("halfNormal sigma", LogDensity.halfNormal(1.0, Double.PositiveInfinity)),
      ("poisson rate", LogMass.poisson(3, Double.PositiveInfinity)),
      ("bernoulliLogit eta", LogMass.bernoulliLogit(1, Double.PositiveInfinity)),
      ("exponential rate", LogDensity.exponential(1.0, Double.PositiveInfinity)),
      ("normal mu", LogDensity.normal(0.0, Double.NaN, 1.0)),
      ("binomial trials", LogMass.binomial(0, -1, 0.5)),
      ("orderedLogistic cuts", LogDensity.orderedLogistic(2, 0.3, IArray(1.0, Double.NaN)))
    )
    forEach(cases)((name, got) => check(got.isNaN, s"$name: got $got, expected NaN"))
  }

  /** Cut points that do not increase are not an ordering, and the density says so rather than
    * returning a plausible number from a difference that came out negative.
    */
  pureTest("ordered logistic cut points must increase") {
    check(
      LogDensity.orderedLogistic(2, 0.3, IArray(1.0, 0.5)).isNaN,
      "decreasing cut points were accepted"
    ).and(
      check(
        LogDensity.orderedLogistic(1, 0.3, IArray(1.0, 0.5)).isNaN,
        "decreasing cut points were accepted at the first category"
      )
    )
  }
