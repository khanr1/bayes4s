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

/** What a parameter is allowed to be.
  *
  * A constraint is a value, and [[Transform.of]] turns it into the pair of maps a sampler needs:
  * from the unconstrained real line onto the constrained set, with the log Jacobian that keeps the
  * density right, and back again. The names and the parameterisations are Stan's, so that a model
  * written from a Stan program reads the same.
  *
  * The parameters of a constraint are not validated here: an `Interval` whose bounds are the wrong
  * way round, or an `OffsetMultiplier` with a negative multiplier, gives `NaN` where it would give
  * a number, which is the same total-function convention the densities follow. A smart constructor
  * would be the wrong place for it, because a constraint is written in a model's source and not
  * built from data.
  */
enum Constraint:

  /** The whole real line: the identity, with no Jacobian. */
  case Unconstrained

  /** Stan's `<lower=0>`, by the exponential. */
  case Positive

  /** Stan's `<lower=0, upper=1>`. */
  case UnitInterval

  /** Increasing values, Stan's `ordered`: the first is free and the gaps are exponentials. */
  case Ordered

  /** Positive values summing to one, Stan's `simplex`. */
  case Simplex

  /** Values summing to zero, Stan's `sum_to_zero_vector`. */
  case SumToZero

  case LowerBound(lower: Double)
  case UpperBound(upper: Double)

  /** Stan's `<lower=L, upper=U>`. */
  case Interval(lower: Double, upper: Double)

  /** Stan's `<offset=..., multiplier=...>`: the affine reparameterisation a non-centred model
    * uses, whose Jacobian is a constant.
    */
  case OffsetMultiplier(offset: Double, multiplier: Double)

  /** The Cholesky factor of a `k` by `k` correlation matrix, Stan's `cholesky_factor_corr`.
    *
    * `k(k-1)/2` unconstrained reals become a lower triangular matrix with a positive diagonal and
    * rows of norm one, so that `L L'` is a correlation matrix. The constrained value is the whole
    * matrix, read row by row, zeros above the diagonal included.
    */
  case CholeskyFactorCorr(k: Int)
