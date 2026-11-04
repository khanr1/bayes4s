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

import bayes4s.testkit.StanMathFixture

/** The mapping between the Stan Math fixture and this library, written once.
  *
  * Two things here would silently weaken the suites if they were left implicit. First, the fixture
  * names the same parameter differently from one function to the next — `Location` and `Scale` in
  * the cumulative tests against `mu` and `sigma` in the density test — so a rejection suite that
  * compared raw names would pass without asserting anything. Second, only part of the fixture is
  * in scope for this phase, and which part has to be a value the coverage suite can count rather
  * than a comment.
  */
object StanMathFixtureSupport:

  /** The laws of the fixture that this library implements. */
  val lawsInScope: Set[String] = Set(
    "normal",
    "std_normal",
    "lognormal",
    "gamma",
    "beta",
    "logistic",
    "poisson",
    "neg_binomial_2",
    "neg_binomial",
    "binomial"
  )

  /** The function families this phase implements: the densities, not the cumulatives. The log-rate
    * form belongs to Poisson alone, `poisson_log` being in the specification while
    * `neg_binomial_2_log` is not.
    */
  def densityFamily(law: String, function: String): Boolean =
    function == "lpdf" || (function == "lpdf_log_param" && law == "poisson")

  /** Our constructor's name for a fixture parameter, or `None` when we have no such parameter. */
  def parameterName(law: String, fixtureName: String): Option[String] =
    (law, fixtureName) match
      case ("neg_binomial_2", "eta") => None
      case ("logistic", "Location") => Some("mu")
      case ("logistic", "Scale") => Some("sigma")
      case ("poisson", "lambda") => Some("rate")
      case ("poisson", "alpha") => Some("logRate")
      case ("neg_binomial", "Successes") => Some("alpha")
      case ("neg_binomial", "logit") => Some("beta")
      case ("binomial", "Trials" | "N") => Some("trials")
      case ("binomial", "Probability" | "theta") => Some("probability")
      case (_, other) => Some(other)

  /** The parameters each smart constructor takes, used to prove no fixture name went untranslated. */
  val parametersOf: Map[String, Set[String]] = Map(
    "normal" -> Set("mu", "sigma"),
    "std_normal" -> Set.empty,
    "lognormal" -> Set("mu", "sigma"),
    "gamma" -> Set("alpha", "beta"),
    "beta" -> Set("alpha", "beta"),
    "logistic" -> Set("mu", "sigma"),
    "poisson" -> Set("rate", "logRate"),
    "neg_binomial_2" -> Set("mu", "phi"),
    "neg_binomial" -> Set("alpha", "beta"),
    "binomial" -> Set("trials", "probability")
  )

  /** The density of a covered law at a fixture parameter vector, position 0 being the variate. */
  def density(law: String, function: String, p: IArray[Double]): Either[String, Double] =
    def of[L](built: Either[BayesError.InvalidArgument, L])(
        f: L => Double
    ): Either[String, Double] =
      built.left.map(_.message).map(f)
    (law, function) match
      case ("normal", "lpdf") => of(Normal.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("std_normal", "lpdf") => Right(Normal.standard.logPdf(p(0)))
      case ("lognormal", "lpdf") => of(LogNormal.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("gamma", "lpdf") => of(Gamma.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("beta", "lpdf") => of(Beta.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("logistic", "lpdf") => of(Logistic.of(p(1), p(2)))(_.logPdf(p(0)))
      case ("poisson", "lpdf") => of(Poisson.of(p(1)))(_.logMass(p(0).toInt))
      case ("poisson", "lpdf_log_param") => of(PoissonLog.of(p(1)))(_.logMass(p(0).toInt))
      case ("neg_binomial_2", "lpdf") => of(NegBinomial2.of(p(1), p(2)))(_.logMass(p(0).toInt))
      case ("neg_binomial", "lpdf") => of(NegBinomial.of(p(1), p(2)))(_.logMass(p(0).toInt))
      case ("binomial", "lpdf") => of(Binomial.of(p(1).toInt, p(2)))(_.logMass(p(0).toInt))
      case _ => Left(s"no density mapping for $law.$function")

  /** The smart constructor of a covered law, at a fixture parameter vector. */
  def construct(
      law: String,
      p: IArray[Double]
  ): Either[String, Either[BayesError.InvalidArgument, Any]] =
    law match
      case "normal" => Right(Normal.of(p(1), p(2)))
      case "lognormal" => Right(LogNormal.of(p(1), p(2)))
      case "gamma" => Right(Gamma.of(p(1), p(2)))
      case "beta" => Right(Beta.of(p(1), p(2)))
      case "logistic" => Right(Logistic.of(p(1), p(2)))
      case "poisson" => Right(Poisson.of(p(1)))
      case "neg_binomial_2" => Right(NegBinomial2.of(p(1), p(2)))
      case "neg_binomial" => Right(NegBinomial.of(p(1), p(2)))
      case "binomial" => Right(Binomial.of(p(1).toInt, p(2)))
      case other => Left(s"no constructor mapping for $other")

  /** A valid parameter vector for a law and function, taken from the fixture itself. */
  def base(fixture: StanMathFixture, law: String, function: String): Option[IArray[Double]] =
    fixture
      .valid(law, function)
      .headOption
      .map(_.params)
      .orElse(fixture.valid(law, "lpdf").headOption.map(_.params))

  def substituted(p: IArray[Double], index: Int, value: Double): IArray[Double] =
    IArray.from(p.toList.updated(index, value))
