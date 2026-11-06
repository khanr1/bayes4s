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

/** How much a parameter's posterior moves when the prior or the likelihood is given slightly more
  * or less weight, and what that combination means.
  */
final case class Sensitivity(
    name: String,
    prior: Double,
    likelihood: Double,
    diagnosis: String
)

/** Prior and likelihood sensitivity by power scaling (Kallioinen, Paananen, Bürkner and Vehtari
  * 2024).
  *
  * Raise the prior — or the likelihood — to a power slightly above and slightly below one,
  * reweight the posterior samples by importance sampling instead of fitting anything again, and
  * measure how far the posterior moved. The distance is the cumulative Jensen-Shannon one, divided
  * by the change in the exponent, so the answer is a derivative and is comparable between
  * parameters.
  *
  * The reading, which is the point of having both numbers:
  *
  *   - **both** above the threshold: the prior and the data disagree, and the posterior is a
  *     compromise neither of them would recognise;
  *   - **only the likelihood**: the data is doing the work, which is what you want;
  *   - **only the prior**: the prior is doing the work — a scale parameter with four groups,
  *     usually — and the posterior is a restatement of what you already believed.
  *
  * ArviZ's `psense` is the reference and its arithmetic is the specification, down to the
  * asymmetry that the "both tails" setting smooths only the right one.
  */
object PowerScaling:

  /** The derivative of the cumulative Jensen-Shannon distance at an exponent of one.
    *
    * `component` is the log prior or the log likelihood, summed over its terms, at each posterior
    * draw; `draws` is the parameter being watched. The relative effective sample size sets how much
    * of the weights' tail is smoothed, and defaults to the tail diagnostic of the component
    * itself, which is what ArviZ uses.
    */
  def sensitivity(
      draws: IArray[Double],
      component: IArray[Double],
      lowerAlpha: Double = 0.99,
      upperAlpha: Double = 1.01,
      relativeEss: Option[Double] = None
  ): Double =
    val n = draws.length
    if n < 4 || component.length != n then Double.NaN
    else
      val reff = relativeEss.getOrElse {
        val tail = Convergence.essTail(IArray(component))
        if tail.isNaN || !(tail > 0.0) then 1.0 else tail / n
      }
      val tailDraws = Pareto.tailLength(n, reff, bothTails = true)
      def weights(alpha: Double): IArray[Double] =
        val logWeights = IArray.tabulate(n)(i => (alpha - 1.0) * component(i))
        val (smoothed, _) = Pareto.smoothRightTail(logWeights, tailDraws)
        val raw = IArray.tabulate(n)(i => math.exp(smoothed(i)))
        val total = (0 until n).foldLeft(0.0)((sum, i) => sum + raw(i))
        IArray.tabulate(n)(i => raw(i) / total)
      val negated = IArray.tabulate(n)(i => -draws(i))
      val lower = weights(lowerAlpha)
      val upper = weights(upperAlpha)
      // The larger of the distance and the distance of the reflected sample: the cumulative
      // Jensen-Shannon distance is not symmetric in the direction the sample is sorted, and taking
      // the larger is what makes the answer one.
      val lowerDistance = math.max(jensenShannon(draws, lower), jensenShannon(negated, lower))
      val upperDistance = math.max(jensenShannon(draws, upper), jensenShannon(negated, upper))
      val below = -lowerDistance / (math.log(lowerAlpha) / math.log(2.0))
      val above = upperDistance / (math.log(upperAlpha) / math.log(2.0))
      (below + above) / 2.0

  /** The cumulative Jensen-Shannon distance between a sample and the same sample reweighted.
    *
    * Both distributions are read as their empirical cumulative functions, and the divergence is
    * integrated over the sample's own spacing — which is why it needs no bins and no bandwidth.
    */
  private[bayes4s] def jensenShannon(draws: IArray[Double], weights: IArray[Double]): Double =
    val n = draws.length
    if n < 2 then Double.NaN
    else
      val order = IArray.from((0 until n).sortBy(draws.apply))
      val sorted = IArray.tabulate(n)(i => draws(order(i)))
      val ordered = IArray.tabulate(n)(i => weights(order(i)))
      val mass = (0 until n).foldLeft(0.0)((sum, i) => sum + ordered(i))
      val widths = IArray.tabulate(n - 1)(i => sorted(i + 1) - sorted(i))
      val cdfP = IArray.tabulate(n - 1) { i =>
        // `linspace(1/n, 1 - 1/n, n - 1)`.
        val first = 1.0 / n
        val last = 1.0 - 1.0 / n
        if n == 2 then first else first + (last - first) * i / (n - 2.0)
      }
      val cdfQ =
        val running = (0 until n - 1).foldLeft((Vector.empty[Double], 0.0)) {
          case ((collected, total), i) =>
            val next = total + ordered(i) / mass
            (collected :+ next, next)
        }
        IArray.from(running._1)
      val integralP = (0 until n - 1).foldLeft(0.0)((sum, i) => sum + cdfP(i) * widths(i))
      val integralQ = (0 until n - 1).foldLeft(0.0)((sum, i) => sum + cdfQ(i) * widths(i))
      def log2(x: Double): Double = if x == 0.0 then 0.0 else math.log(x) / math.log(2.0)
      val halfOverLog2 = 0.5 / math.log(2.0)
      val pq = (0 until n - 1).foldLeft(0.0) { (sum, i) =>
        val denominator = log2(0.5 * (cdfP(i) + cdfQ(i)))
        sum + widths(i) * cdfP(i) * (log2(cdfP(i)) - denominator)
      } + halfOverLog2 * (integralQ - integralP)
      val qp = (0 until n - 1).foldLeft(0.0) { (sum, i) =>
        val denominator = log2(0.5 * (cdfP(i) + cdfQ(i)))
        sum + widths(i) * cdfQ(i) * (log2(cdfQ(i)) - denominator)
      } + halfOverLog2 * (integralP - integralQ)
      val bound = integralP + integralQ
      math.sqrt((math.max(0.0, pq) + math.max(0.0, qp)) / bound)

  /** The diagnosis the two numbers together give. */
  def diagnose(prior: Double, likelihood: Double, threshold: Double): String =
    (prior > threshold, likelihood > threshold) match
      case (true, true) => "prior-data conflict"
      case (false, true) => "the data is doing the work"
      case (true, false) => "the prior is doing the work"
      case (false, false) => "neither is sensitive"

  /** Every parameter of a model, from its posterior samples.
    *
    * The prior and the likelihood come from the model itself, which is why `Model` keeps them
    * apart (design D-?): nothing here could separate them from a single target.
    */
  def analyse[T[_]](
      model: Model[T],
      chains: List[Chain],
      lowerAlpha: Double = 0.99,
      upperAlpha: Double = 1.01,
      threshold: Double = 0.05
  ): List[Sensitivity] =
    val points = chains.flatMap(_.draws)
    if points.isEmpty then Nil
    else
      val parameters = points.map(q => Model.constrain(model, q))
      val logPrior = IArray.from(parameters.map(p => model.logPrior(p)))
      val logLikelihood = IArray.from(parameters.map(p => model.logLikelihood(p)))
      val named = points.map(q => Model.values(model, q))
      model.params.layout.flatMap { block =>
        (0 until block.size).map { index =>
          val name = if block.size == 1 then block.name else s"${block.name}.${index + 1}"
          val column = IArray.from(named.map(values => values(block.name)(index)))
          val prior = sensitivity(column, logPrior, lowerAlpha, upperAlpha)
          val likelihood = sensitivity(column, logLikelihood, lowerAlpha, upperAlpha)
          Sensitivity(name, prior, likelihood, diagnose(prior, likelihood, threshold))
        }
      }
