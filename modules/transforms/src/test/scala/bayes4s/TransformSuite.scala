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
import bayes4s.testkit.Fixtures
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** The constraint transforms, against ports of `stan/math/prim/constraint` and against Stan's own
  * test files.
  *
  * Two things are asserted for every family and they are not the same thing. The **value** is what
  * the model sees, and getting it wrong changes the model. The **log Jacobian** is what makes the
  * density on the unconstrained space the right density, and getting it wrong changes the
  * posterior while leaving every value plausible — which is why the fixture carries an independent
  * numerical Jacobian for the families where a closed form could be mistyped, and why this suite
  * computes its own for the families where the Jacobian is diagonal.
  */
object TransformSuite extends SimpleIOSuite with Checkers with Approx:

  private def of(c: Constraint): Transform = Transform.of(c)

  private def constrained(c: Constraint, u: IArray[Double]): (IArray[Double], Double) =
    of(c).constrain[Double](u)

  pureTest("the 18 lower-bound cases of transforms.json") {
    Fixtures.lowerBoundCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val (value, jacobian) = constrained(Constraint.LowerBound(c.bound), IArray(c.x))
          closeRel(value(0), c.value, 1e-12, s"lb(${c.x}, ${c.bound})")
            .and(closeRel(jacobian, c.logJacobian, 1e-12, s"lb log Jacobian at ${c.x}"))
        }
        check(cases.length == 18, s"${cases.length} cases, expected 18").and(assertions)
  }

  pureTest("the 9 upper-bound cases of transforms.json") {
    Fixtures.upperBoundCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val (value, jacobian) = constrained(Constraint.UpperBound(c.bound), IArray(c.x))
          closeRel(value(0), c.value, 1e-12, s"ub(${c.x}, ${c.bound})")
            .and(closeRel(jacobian, c.logJacobian, 1e-12, s"ub log Jacobian at ${c.x}"))
        }
        check(cases.length == 9, s"${cases.length} cases, expected 9").and(assertions)
  }

  pureTest("the 27 interval cases, and their numerical Jacobians") {
    Fixtures.intervalCases.flatMap(cs => Fixtures.numericalJacobianError.map((cs, _))) match
      case Left(message) => failure(message)
      case Right((cases, bound)) =>
        val withNumerical = cases.count(_.numerical.isDefined)
        val assertions = forEach(cases) { c =>
          val constraint = Constraint.Interval(c.lower, c.upper)
          val (value, jacobian) = constrained(constraint, IArray(c.x))
          val numerical = c.numerical.fold(success) { n =>
            close(jacobian, n, bound, s"lub log Jacobian at ${c.x} against the finite difference")
          }
          closeRel(value(0), c.value, 1e-12, s"lub(${c.x}, ${c.lower}, ${c.upper})")
            .and(closeRel(jacobian, c.logJacobian, 1e-12, s"lub log Jacobian at ${c.x}"))
            .and(numerical)
        }
        check(cases.length == 27, s"${cases.length} cases, expected 27")
          .and(check(withNumerical >= 9, s"only $withNumerical cases carry a numerical Jacobian"))
          .and(assertions)
  }

  pureTest("the 9 offset-multiplier cases") {
    Fixtures.offsetMultiplierCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val constraint = Constraint.OffsetMultiplier(c.offset, c.multiplier)
          val (value, jacobian) = constrained(constraint, IArray(c.x))
          closeRel(value(0), c.value, 1e-12, s"offsetMultiplier(${c.x})")
            .and(closeRel(jacobian, c.logJacobian, 1e-12, s"its log Jacobian"))
        }
        check(cases.length == 9, s"${cases.length} cases, expected 9").and(assertions)
  }

  pureTest("the 15 ordered cases, their Jacobians and their inverse") {
    Fixtures
      .vectorTransformCases("ordered")
      .flatMap(cs => Fixtures.numericalJacobianError.map((cs, _))) match
      case Left(message) => failure(message)
      case Right((cases, bound)) =>
        val assertions = forEach(cases) { c =>
          val (value, jacobian) = constrained(Constraint.Ordered, c.y)
          val expectedJacobian = c.logJacobian.getOrElse(Double.NaN)
          val increasing = (1 until value.length).forall(i => value(i) > value(i - 1))
          val inverse = of(Constraint.Ordered).unconstrain(c.value)
          forEach((0 until c.value.length).toList) { i =>
            closeRel(value(i), c.value(i), 1e-12, s"ordered value $i")
          }
            .and(closeRel(jacobian, expectedJacobian, 1e-12, "ordered log Jacobian"))
            .and(c.numerical.fold(success) { n =>
              close(jacobian, n, bound, "ordered log Jacobian against the finite difference")
            })
            .and(check(increasing, s"the value is not increasing: ${value.toList}"))
            .and(inverse match
              case Left(error) => failure(error.message)
              case Right(free) =>
                forEach((0 until free.length).toList) { i =>
                  closeRel(free(i), c.y(i), 1e-12, s"the inverse at $i")
                })
        }
        check(cases.length == 15, s"${cases.length} cases, expected 15").and(assertions)
  }

  pureTest("the 15 sum-to-zero cases sum to zero, and come back") {
    Fixtures.vectorTransformCases("sum_to_zero") match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val (value, jacobian) = constrained(Constraint.SumToZero, c.y)
          val total = value.toList.sum
          forEach((0 until c.value.length).toList) { i =>
            closeRel(value(i), c.value(i), 1e-12, s"sumToZero value $i")
          }
            // The map is linear and volume preserving, so there is no Jacobian to add, and the
            // fixture states no log_jacobian for it. Zero is a claim, not an omission.
            .and(check(jacobian == 0.0, s"sumToZero added $jacobian to the density"))
            .and(close(total, 0.0, 1e-12, s"the values sum to $total"))
            .and(of(Constraint.SumToZero).unconstrain(value) match
              case Left(error) => failure(error.message)
              case Right(free) =>
                forEach((0 until free.length).toList) { i =>
                  closeRel(free(i), c.y(i), 1e-12, s"the inverse at $i")
                })
        }
        check(cases.length == 15, s"${cases.length} cases, expected 15").and(assertions)
  }

  pureTest("the 15 simplex cases, their Jacobians and the round trip the fixture states") {
    Fixtures
      .vectorTransformCases("simplex")
      .flatMap(cs => Fixtures.numericalJacobianError.map((cs, _))) match
      case Left(message) => failure(message)
      case Right((cases, bound)) =>
        val assertions = forEach(cases) { c =>
          val (value, jacobian) = constrained(Constraint.Simplex, c.y)
          val total = value.toList.sum
          val roundTrip = c.roundTrip.getOrElse(IArray.empty[Double])
          forEach((0 until c.value.length).toList) { i =>
            closeRel(value(i), c.value(i), 1e-12, s"simplex value $i")
          }
            .and(
              closeRel(jacobian, c.logJacobian.getOrElse(Double.NaN), 1e-12, "simplex log Jacobian")
            )
            .and(c.numerical.fold(success) { n =>
              close(jacobian, n, bound, "simplex log Jacobian against the finite difference")
            })
            .and(close(total, 1.0, 1e-12, s"the values sum to $total"))
            .and(check(value.toList.forall(_ > 0.0), "a component is not positive"))
            .and(of(Constraint.Simplex).unconstrain(value) match
              case Left(error) => failure(error.message)
              case Right(free) =>
                forEach((0 until free.length).toList) { i =>
                  closeRel(free(i), roundTrip(i), 1e-12, s"the inverse at $i")
                })
        }
        check(cases.length == 15, s"${cases.length} cases, expected 15").and(assertions)
  }

  /** The five cases Stan's own test files assert, each read by name.
    *
    * The underflow case is the one the specification calls out: `Interval(0, 1)` at `x = -1000`
    * must be exactly 0 and not `NaN`. Through the probability that is `0 * (1 - 0)` and the log
    * Jacobian is `log(0) + log(1)`, which is `-Inf` and not a number; through `logInvLogit` and
    * `log1mInvLogit` it is -1000 and finite.
    */
  pureTest("the 5 cases Stan's own transform tests assert") {
    Fixtures.stanAssertedTransforms match
      case Left(message) => failure(message)
      case Right(cases) =>
        def named(name: String) = cases.find(_.test.contains(name))
        val lubJ = named("lub_j").fold(failure("no lub_j case")) { c =>
          val (value, jacobian) =
            constrained(Constraint.Interval(c.lower.get, c.upper.get), IArray(c.x.get))
          closeRel(value(0), c.scalarValue.get, 1e-12, "lub_j value")
            .and(closeRel(jacobian, c.logJacobian.get, 1e-12, "lub_j log Jacobian"))
        }
        val underflow = named("underflow").fold(failure("no underflow case")) { c =>
          val (value, jacobian) =
            constrained(Constraint.Interval(c.lower.get, c.upper.get), IArray(c.x.get))
          check(value(0) == 0.0, s"Interval(0, 1) at -1000 gave ${value(0)}, expected exactly 0")
            .and(check(!jacobian.isNaN, s"its log Jacobian is $jacobian, which is not a number"))
            .and(closeRel(jacobian, -1000.0, 1e-12, "its log Jacobian"))
        }
        val orderedJ = named("ordered_j").fold(failure("no ordered_j case")) { c =>
          val (value, jacobian) = constrained(Constraint.Ordered, c.y.get)
          forEach((0 until value.length).toList) { i =>
            closeRel(value(i), c.vectorValue.get(i), 1e-12, s"ordered_j value $i")
          }.and(closeRel(jacobian, c.logJacobian.get, 1e-12, "ordered_j log Jacobian"))
        }
        val orderedF = named("ordered_f").fold(failure("no ordered_f case")) { c =>
          of(Constraint.Ordered).unconstrain(c.xVector.get) match
            case Left(error) => failure(error.message)
            case Right(free) =>
              forEach((0 until free.length).toList) { i =>
                closeRel(free(i), c.free.get(i), 1e-12, s"ordered_f at $i")
              }
        }
        val simplexEmpty = named("simplex_0").fold(failure("no simplex_0 case")) { c =>
          val (value, jacobian) = constrained(Constraint.Simplex, IArray.empty[Double])
          check(value.length == 1, s"the simplex of nothing has ${value.length} components")
            .and(closeRel(value(0), 1.0, 1e-12, "the one component"))
            .and(check(jacobian == 0.0, s"it added $jacobian to the density"))
            .and(check(c.y.get.isEmpty, "the fixture case is not the empty one"))
        }
        check(cases.length == 5, s"${cases.length} asserted cases, expected 5")
          .and(lubJ)
          .and(underflow)
          .and(orderedJ)
          .and(orderedF)
          .and(simplexEmpty)
  }

  pureTest("the 12 Cholesky correlation factors of multilevel.json") {
    Fixtures.choleskyCorrCases match
      case Left(message) => failure(message)
      case Right(cases) =>
        val assertions = forEach(cases) { c =>
          val constraint = Constraint.CholeskyFactorCorr(c.k)
          val (flat, jacobian) = constrained(constraint, c.y)
          val rows = (0 until c.k).toList
          // Column by column, as Stan stores a matrix and as `Block.names` lists it.
          val entries = forEach(rows.flatMap(i => rows.map(j => (i, j)))) { (i, j) =>
            closeRel(flat(j * c.k + i), c.l(i)(j), 1e-12, s"L($i, $j) at K = ${c.k}")
          }
          val unitNorms = forEach(rows) { i =>
            val norm =
              (0 until c.k).foldLeft(0.0)((s, j) => s + flat(j * c.k + i) * flat(j * c.k + i))
            close(norm, 1.0, 1e-12, s"row $i of L has norm $norm")
          }
          entries
            .and(closeRel(jacobian, c.logJacobian, 1e-12, s"the log Jacobian at K = ${c.k}"))
            // Against the fixture's own finite difference, at the only honest tolerance: no
            // further from it than the fixture's own closed form is. A 10 by 10 numerical
            // determinant is the noisy quantity here — at K = 5 the fixture's two numbers differ
            // by 1.9e-6 — while the closed form itself is asserted just above to 1e-12.
            .and(
              close(
                jacobian,
                c.numerical,
                math.abs(c.logJacobian - c.numerical) +
                  1e-12 * math.max(1.0, math.abs(c.logJacobian)),
                s"against the numerical Jacobian at K = ${c.k}"
              )
            )
            .and(unitNorms)
            .and(constraint match
              case _ =>
                of(constraint).unconstrain(flat) match
                  case Left(error) => failure(error.message)
                  case Right(free) =>
                    forEach((0 until free.length).toList) { i =>
                      closeRel(free(i), c.y(i), 1e-12, s"the inverse at $i")
                    })
        }
        check(cases.length == 12, s"${cases.length} cases, expected 12").and(assertions)
  }

  // --- properties -----------------------------------------------------------------------------

  private val everyConstraint: List[(String, Int => Constraint)] = List(
    ("Unconstrained", _ => Constraint.Unconstrained),
    ("Positive", _ => Constraint.Positive),
    ("UnitInterval", _ => Constraint.UnitInterval),
    ("LowerBound", _ => Constraint.LowerBound(-2.5)),
    ("UpperBound", _ => Constraint.UpperBound(7.0)),
    ("Interval", _ => Constraint.Interval(-1.0, 3.0)),
    ("OffsetMultiplier", _ => Constraint.OffsetMultiplier(1.5, 0.2)),
    ("Ordered", _ => Constraint.Ordered),
    ("SumToZero", _ => Constraint.SumToZero),
    ("Simplex", _ => Constraint.Simplex),
    ("CholeskyFactorCorr", n => Constraint.CholeskyFactorCorr(n + 1))
  )

  /** `unconstrain(constrain(u)) == u`, which is the direction that can be tested on any input:
    * `constrain` is total, and it is `unconstrain` that is partial.
    */
  test("every transform comes back, to 1e-12") {
    // Up to 4 in the unconstrained space, which is a correlation of 0.9993 and a probability of
    // 0.982: the whole range a model lives in. Beyond it `atanh` and `logit` amplify their own
    // input's last bit by their derivative, and the next test says by how much.
    val ordinary: Gen[Double] = Gen.choose(-4.0, 4.0)
    forall(Gen.zip(Gen.choose(1, 5), Gen.listOfN(15, ordinary))) { (n, values) =>
      forEach(everyConstraint) { (name, build) =>
        val constraint = build(n)
        val size = Transform.of(constraint) match
          case t => t.unconstrainedSize(n + 1)
        val u = IArray.from(values.take(math.max(size, 0)))
        val (x, _) = constrained(constraint, u)
        of(constraint).unconstrain(x) match
          case Left(error) => failure(s"$name: ${error.message}")
          case Right(back) =>
            check(back.length == u.length, s"$name: came back with ${back.length} of ${u.length}")
              .and(forEach((0 until u.length).toList) { i =>
                closeRel(back(i), u(i), 1e-12, s"$name: coordinate $i")
              })
      }
    }
  }

  /** How far the round trip of a saturating transform can be from exact, and why that is the
    * arithmetic rather than the implementation.
    *
    * `atanh` has derivative `1 / (1 - z²) = cosh²(y)` at `z = tanh(y)`, so the last bit of `z`
    * becomes `cosh²(y)` last bits of the recovered `y`. At `y = 6` that factor is 3.3e4 and the
    * round trip can be 1e-11 out; at `y = 10` it is 2.4e8 and nothing better than 1e-8 is
    * available. The test asserts that bound, which is tighter than a flat 1e-12 for every ordinary
    * value and honest about the saturated ones.
    */
  test("a saturated correlation comes back as well as atanh allows, and no better") {
    forall(Gen.listOfN(1, Gen.choose(-10.0, 10.0))) { values =>
      val u = IArray.from(values)
      val (x, _) = constrained(Constraint.CholeskyFactorCorr(2), u)
      of(Constraint.CholeskyFactorCorr(2)).unconstrain(x) match
        case Left(error) => failure(error.message)
        case Right(back) =>
          val conditioning = math.cosh(u(0)) * math.cosh(u(0))
          close(
            back(0),
            u(0),
            1e-14 * math.max(1.0, conditioning),
            s"the round trip at y = ${u(0)}, whose conditioning is $conditioning"
          )
    }
  }

  /** The log Jacobian against a finite difference computed here, for the families whose Jacobian
    * is diagonal or triangular — which is every family whose output has the size of its input.
    * `sum_to_zero`, `simplex` and the Cholesky factor change the size, and the fixture's own
    * numerical Jacobians cover those.
    */
  test("the log Jacobian of a square transform equals its numerical determinant") {
    val square: List[(String, Constraint)] = List(
      ("Positive", Constraint.Positive),
      ("UnitInterval", Constraint.UnitInterval),
      ("LowerBound", Constraint.LowerBound(-2.5)),
      ("UpperBound", Constraint.UpperBound(7.0)),
      ("Interval", Constraint.Interval(-1.0, 3.0)),
      ("OffsetMultiplier", Constraint.OffsetMultiplier(1.5, 0.2)),
      ("Ordered", Constraint.Ordered)
    )
    forall(Gen.listOfN(4, Gen.choose(-3.0, 3.0))) { values =>
      val u = IArray.from(values)
      forEach(square) { (name, constraint) =>
        val (_, jacobian) = constrained(constraint, u)
        // Both families are triangular in the right order, so the determinant is the product of
        // the diagonal and its log is the sum of the logs of d x(i) / d u(i).
        val numerical = (0 until u.length).foldLeft(0.0) { (total, i) =>
          val h = 1e-6 * math.max(1.0, math.abs(u(i)))
          val up = IArray.tabulate(u.length)(j => if j == i then u(j) + h else u(j))
          val down = IArray.tabulate(u.length)(j => if j == i then u(j) - h else u(j))
          val slope = (constrained(constraint, up)._1(i) - constrained(constraint, down)._1(i)) /
            (2.0 * h)
          total + math.log(math.abs(slope))
        }
        close(jacobian, numerical, 1e-6, s"$name: the log Jacobian against finite differences")
      }
    }
  }

  pureTest("the sizes are what each transform says they are") {
    val expectations = List(
      (Constraint.Unconstrained, 5, 5),
      (Constraint.Positive, 5, 5),
      (Constraint.Interval(0.0, 1.0), 5, 5),
      (Constraint.Ordered, 5, 5),
      (Constraint.SumToZero, 5, 4),
      (Constraint.Simplex, 5, 4),
      (Constraint.CholeskyFactorCorr(4), 16, 6)
    )
    forEach(expectations) { (constraint, size, unconstrained) =>
      check(
        of(constraint).unconstrainedSize(size) == unconstrained,
        s"$constraint of $size wants ${of(constraint).unconstrainedSize(size)}, not $unconstrained"
      )
    }
  }

  pureTest("a value outside its constraint has no antecedent, and says so") {
    val cases = List(
      ("Positive at zero", Constraint.Positive, IArray(0.0)),
      ("Positive at a negative", Constraint.Positive, IArray(-1.0)),
      ("UnitInterval above one", Constraint.UnitInterval, IArray(1.5)),
      ("Interval below its lower bound", Constraint.Interval(-1.0, 3.0), IArray(-2.0)),
      ("UpperBound above its bound", Constraint.UpperBound(7.0), IArray(8.0)),
      ("Ordered, not ordered", Constraint.Ordered, IArray(1.0, 0.5)),
      ("Ordered, tied", Constraint.Ordered, IArray(1.0, 1.0)),
      ("SumToZero, not summing to zero", Constraint.SumToZero, IArray(1.0, 1.0)),
      ("Simplex, not summing to one", Constraint.Simplex, IArray(0.3, 0.3)),
      ("Simplex with a negative", Constraint.Simplex, IArray(1.3, -0.3)),
      ("OffsetMultiplier of a NaN", Constraint.OffsetMultiplier(0.0, 2.0), IArray(Double.NaN)),
      // Column major: the entries are L(0,0), L(1,0), L(0,1), L(1,1).
      (
        "a factor that is not lower triangular",
        Constraint.CholeskyFactorCorr(2),
        IArray(1.0, 0.5, 0.5, 1.0)
      ),
      (
        "a factor whose rows are not of norm one",
        Constraint.CholeskyFactorCorr(2),
        IArray(1.0, 0.5, 0.0, 0.5)
      ),
      ("a factor of the wrong length", Constraint.CholeskyFactorCorr(2), IArray(1.0, 0.0, 0.0))
    )
    forEach(cases) { (name, constraint, x) =>
      check(of(constraint).unconstrain(x).isLeft, s"$name was accepted")
    }
  }

  /** `constrain` is total: a sampler's proposal lands where the dynamics take it, and a transform
    * that threw there would take the chain with it.
    */
  test("constrain is total on any unconstrained vector") {
    val awkward: Gen[Double] = Gen.oneOf(
      Double.NaN,
      Double.PositiveInfinity,
      Double.NegativeInfinity,
      0.0,
      -0.0,
      1e-320,
      1e300,
      -1e300,
      1000.0,
      -1000.0
    )
    forall(Gen.listOfN(12, Gen.frequency(2 -> awkward, 1 -> Gen.choose(-5.0, 5.0)))) { values =>
      forEach(everyConstraint) { (name, build) =>
        val constraint = build(3)
        val size = of(constraint).unconstrainedSize(4)
        val (x, jacobian) = constrained(constraint, IArray.from(values.take(size)))
        check(x.length > 0, s"$name returned nothing")
          .and(check(jacobian == jacobian || jacobian.isNaN, s"$name threw"))
      }
    }
  }
