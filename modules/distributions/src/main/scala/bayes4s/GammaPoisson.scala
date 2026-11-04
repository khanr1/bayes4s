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

/** The Gamma-Poisson conjugate pair: a Gamma prior on a rate, Poisson counts.
  *
  * Lives here rather than in a nowcasting module because it is a property of the two
  * distributions, and because it is the first posterior anyone computes by hand.
  */
object GammaPoisson:

  /** Posterior after observing counts over an exposure: `Gamma(alpha + sum(counts), beta +
    * exposure)`. An exposure equal to the number of observations is the plain conjugate update;
    * anything else is the partially observed case the nowcasting models need.
    */
  def update(prior: Gamma, counts: IArray[Int], exposure: Double): Either[BayesError, Gamma] =
    if counts.toList.exists(_ < 0) then
      Left(BayesError.InvalidArgument("GammaPoisson.update", "counts", -1.0, "counts >= 0"))
    else
      Laws
        .positiveFinite("GammaPoisson.update", "exposure", exposure)
        .flatMap(_ => Gamma.of(prior.alpha + counts.toList.sum, prior.beta + exposure))

  /** The predictive distribution of the next count: negative binomial in Stan's `(alpha, beta)`
    * parameterisation. Its mean is the posterior mean of the rate, and its variance is larger, by
    * exactly the posterior variance of that rate — which is the difference a plug-in Poisson
    * throws away.
    */
  def predictive(posterior: Gamma): Either[BayesError.InvalidArgument, NegBinomial] =
    NegBinomial.of(posterior.alpha, posterior.beta)
