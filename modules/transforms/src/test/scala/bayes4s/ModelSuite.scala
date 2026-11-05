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
import bayes4s.testkit.Fixtures
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** A model as a value, and the three things that can go wrong without any of them being visible:
  * the log Jacobians not being added, the gradient not being the gradient, and the names not
  * coming back to the same point.
  *
  * The gradient is compared against the dual-number oracle, which shares no derivative code with
  * the tape, on every constraint the library has — because a Jacobian that is wrong only for
  * `Simplex` would leave every other test green.
  */
object ModelSuite extends SimpleIOSuite with Checkers with Approx:

  /** One block of every constraint whose values are a vector or a scalar, so that one gradient
    * comparison covers them all. A model to exercise the machinery, not to believe in.
    */
  final case class Wide[A](
      free: A,
      positive: A,
      unit: A,
      bounded: A,
      ordered: IArray[A],
      zeroSum: IArray[A],
      simplex: IArray[A],
      factor: IArray[IArray[A]]
  )

  private val wide: Model[Wide] = new Model[Wide]:
    val params: ParamSpec[Wide] =
      (
        ParamSpec.real("free"),
        ParamSpec.positive("positive"),
        ParamSpec.unitInterval("unit"),
        ParamSpec.bounded("bounded", -1.0, 3.0),
        ParamSpec.vector("ordered", 3, Constraint.Ordered),
        ParamSpec.vector("zeroSum", 3, Constraint.SumToZero),
        ParamSpec.vector("simplex", 4, Constraint.Simplex),
        ParamSpec.choleskyCorr("factor", 3)
      ).mapN[Wide]([A] =>
        (
            free: A,
            positive: A,
            unit: A,
            bounded: A,
            ordered: IArray[A],
            zeroSum: IArray[A],
            simplex: IArray[A],
            factor: IArray[IArray[A]]
        ) => Wide(free, positive, unit, bounded, ordered, zeroSum, simplex, factor)
      )

    def logPrior[A: Real](p: Wide[A]): A =
      val r = Real[A]
      LogDensity.normal(p.free, 0.0.lift[A], 1.0.lift[A]) +
        LogDensity.halfNormal(p.positive, 1.0.lift[A]) +
        LogDensity.beta(p.unit, 2.0.lift[A], 2.0.lift[A]) +
        LogDensity.normal(p.bounded, 0.0.lift[A], 1.0.lift[A]) +
        LogDensity.normal(p.ordered, 0.0.lift[A], 2.0.lift[A]) +
        LogDensity.normal(p.zeroSum, 0.0.lift[A], 1.0.lift[A]) +
        // Smooth and proper enough to differentiate; the point here is the Jacobian, not the law.
        r.sum(
          r.vectorOf(p.simplex.length)(i =>
            LogDensity.normal(log(p.simplex(i)), 0.0.lift[A], 1.0.lift[A])
          )
        ) +
        LogDensity.lkjCorrCholesky(p.factor, 2.0.lift[A])

    def logLikelihood[A: Real](p: Wide[A]): A =
      LogDensity.normal(p.ordered(2), 1.0.lift[A], 0.5.lift[A])

  /** Fifteen unconstrained reals for sixteen constrained values plus a nine-entry factor: the
    * two sum-to-one constraints and the correlation factor each cost fewer reals than they hold.
    */
  private val wideAt: IArray[Double] =
    IArray(0.3, -0.4, 0.2, 0.7, -0.5, 0.1, -0.2, 0.4, -0.3, 0.6, 0.15, -0.25, 0.35, 0.5, -0.1)

  private val schools: Either[String, EightSchools.Data] =
    Fixtures.posteriordbData("eight_schools-eight_schools_noncentered").flatMap { json =>
      val cursor = json.hcursor
      for
        y <- cursor.get[List[Double]]("y").left.map(_.getMessage)
        sigma <- cursor.get[List[Double]]("sigma").left.map(_.getMessage)
        data <- EightSchools.Data
          .of(IArray.from(y), IArray.from(sigma))
          .left
          .map(_.message)
      yield data
    }

  private def withSchools(f: EightSchools.Data => weaver.Expectations) =
    schools.fold(message => failure(message), f)

  pureTest("the eight schools are the eight schools of Rubin (1981)") {
    withSchools { data =>
      check(data.schools == 8, s"${data.schools} schools")
        .and(check(data.y.toList.head == 28.0, s"the first effect is ${data.y.toList.head}"))
        .and(check(data.sigma.toList.forall(_ > 0.0), "a standard error is not positive"))
    }
  }

  pureTest("the target is the prior, the likelihood and the Jacobians, and nothing else") {
    withSchools { data =>
      val model = EightSchools.nonCentred(data)
      val u = IArray(0.3, -0.7, 0.1, -0.2, 0.4, 0.0, 0.5, -0.6, 0.2, 0.15)
      val (p, jacobian) = model.params.read[Double](u)
      val expected = model.logPrior(p) + model.logLikelihood(p) + jacobian
      val got = Reverse.value(Model.target(model), u)
      // The Jacobian of this model is `log(tau)`, from the one positive parameter: a number, not
      // zero, so dropping it would be visible here.
      closeRel(got, expected, 1e-14, "the target")
        .and(closeRel(jacobian, u(1), 1e-15, "the log Jacobian is the unconstrained tau"))
        .and(check(math.abs(jacobian) > 0.1, s"the Jacobian is $jacobian, too small to prove much"))
    }
  }

  test("the gradient of the target equals the oracle, on every model") {
    val models: List[(String, DiffFn, Int)] = schools.toOption.toList.flatMap { data =>
      List(
        ("eight schools, centred", Model.target(EightSchools.centred(data)), 10),
        ("eight schools, non-centred", Model.target(EightSchools.nonCentred(data)), 10)
      )
    } ++ List(("every constraint", Model.target(wide), wideAt.length))
    forall(Gen.listOfN(16, Gen.choose(-1.5, 1.5))) { values =>
      forEach(models) { (name, target, size) =>
        val u = IArray.from(values.take(size))
        val taped = Reverse.valueAndGradient(target, u)
        val oracle = Dual.gradient(target, u)
        closeRel(taped.value, Dual.value(target, u), 1e-14, s"$name: the value")
          .and(forEach((0 until size).toList) { i =>
            closeRel(taped.gradient(i), oracle(i), 1e-12, s"$name: d/du($i)")
          })
      }
    }
  }

  pureTest("the gradient of the target agrees with central differences") {
    val result = GradientCheck(Model.target(wide), wideAt, 1e-6)
    withSchools { data =>
      val schoolsResult =
        GradientCheck(Model.target(EightSchools.centred(data)), IArray.fill(10)(0.3), 1e-6)
      check(result.maxError <= 1e-6, s"every constraint: ${result.maxError}")
        .and(check(schoolsResult.maxError <= 1e-6, s"eight schools: ${schoolsResult.maxError}"))
    }
  }

  /** The full round trip, which is the only way to see that the names, the order and the inverses
    * all agree: a point becomes named constrained values and comes back to the same point.
    */
  test("a point becomes named values and comes back, to 1e-12") {
    forall(Gen.listOfN(wide.params.unconstrainedSize, Gen.choose(-3.0, 3.0))) { values =>
      val u = IArray.from(values)
      val named = Model.values(wide, u)
      Model.unconstrain(wide, named) match
        case Left(error) => failure(error.message)
        case Right(back) =>
          check(back.length == u.length, s"came back with ${back.length} of ${u.length}")
            .and(check(named.keySet.size == 8, s"the names are ${named.keySet}"))
            .and(forEach((0 until u.length).toList) { i =>
              closeRel(back(i), u(i), 1e-12, s"coordinate $i")
            })
    }
  }

  pureTest("the named values are the constrained values, under the names the layout gives") {
    val named = Model.values(wide, wideAt)
    val sizes = check(
      wide.params.unconstrainedSize == 15 && wideAt.length == 15,
      s"the wide spec wants ${wide.params.unconstrainedSize} reals and the point has ${wideAt.length}"
    )
    val p = Model.constrain(wide, wideAt)
    check(named("free")(0) == p.free, "free")
      .and(check(named("positive")(0) == p.positive, "positive"))
      .and(check(named("simplex").length == 4, s"the simplex has ${named("simplex").length}"))
      .and(check(named("factor").length == 9, s"the factor has ${named("factor").length}"))
      .and(
        // Column major, as `Block.names` lists it.
        check(named("factor")(1) == p.factor(1)(0), "the factor is not in column-major order")
      )
      .and(
        check(
          wide.params.names.length == named.values.map(_.length).sum,
          "there is not one name per value"
        )
      )
      .and(sizes)
  }

  pureTest("unconstrain says which name it did not find, and which value it could not invert") {
    val named = Model.values(wide, wideAt)
    val missing = Model.unconstrain(wide, named - "simplex")
    val short = Model.unconstrain(wide, named.updated("ordered", IArray(1.0, 2.0)))
    val outside = Model.unconstrain(wide, named.updated("positive", IArray(-1.0)))
    check(
      missing == Left(BayesError.UnknownParameter("simplex")),
      s"a missing name gave $missing"
    )
      .and(check(short.isLeft, s"a vector of the wrong length gave $short"))
      .and(
        check(
          short.left.exists(_.message.contains("ordered")),
          s"the error does not name the block: $short"
        )
      )
      .and(check(outside.isLeft, s"a negative scale gave $outside"))
      .and(
        check(
          outside.left.exists(e => e.message.contains("positive") && e.message.contains("-1")),
          s"the error does not name the block and the value: $outside"
        )
      )
      .and {
        // A block whose transform names a coordinate keeps both names: the block and the entry.
        val inner = Model.unconstrain(wide, named.updated("ordered", IArray(1.0, 0.5, 2.0)))
        check(
          inner.left.exists(e => e.message.contains("ordered") && e.message.contains("value(1)")),
          s"an inner coordinate is not named: $inner"
        )
      }
  }

  pureTest("a model whose names clash is refused by unconstrain, not quietly read") {
    val clashing: Model[[A] =>> (A, A)] = new Model[[A] =>> (A, A)]:
      val params: ParamSpec[[A] =>> (A, A)] =
        (ParamSpec.real("mu"), ParamSpec.positive("mu"))
          .mapN[[A] =>> (A, A)]([A] => (a: A, b: A) => (a, b))
      def logPrior[A: Real](p: (A, A)): A = Real[A].plus(p._1, p._2)
      def logLikelihood[A: Real](p: (A, A)): A = Real[A].const(0.0)
    val attempted = Model.unconstrain(clashing, Map("mu" -> IArray(1.0)))
    check(
      attempted == Left(BayesError.DuplicateParameter("mu")),
      s"a duplicate name gave $attempted"
    )
  }

  pureTest("priorOnly drops the likelihood and nothing else") {
    withSchools { data =>
      val model = EightSchools.nonCentred(data)
      val prior = Model.priorOnly(model)
      val u = IArray(0.3, -0.7, 0.1, -0.2, 0.4, 0.0, 0.5, -0.6, 0.2, 0.15)
      val p = Model.constrain(model, u)
      val target = Reverse.value(Model.target(prior), u)
      val expected = model.logPrior(p) + model.params.read[Double](u)._2
      check(prior.logLikelihood(p) == 0.0, s"the likelihood is ${prior.logLikelihood(p)}")
        .and(check(prior.logPrior(p) == model.logPrior(p), "the prior changed"))
        .and(closeRel(target, expected, 1e-14, "the prior-only target"))
        .and(
          check(
            math.abs(target - Reverse.value(Model.target(model), u)) > 1.0,
            "the two targets are the same, so the likelihood was already nothing"
          )
        )
        .and(check(prior.derived(p).keySet == model.derived(p).keySet, "the derived quantities"))
    }
  }

  pureTest("a derived quantity is computed, not stored") {
    withSchools { data =>
      val model = EightSchools.nonCentred(data)
      val u = IArray(0.3, -0.7, 0.1, -0.2, 0.4, 0.0, 0.5, -0.6, 0.2, 0.15)
      val p = Model.constrain(model, u)
      val derived = model.derived(p)
      val theta = derived.getOrElse("theta", IArray.empty[Double])
      check(derived.keySet == Set("theta"), s"the derived quantities are ${derived.keySet}")
        .and(check(theta.length == 8, s"theta has ${theta.length} values"))
        .and(forEach((0 until 8).toList) { j =>
          closeRel(theta(j), p.mu + p.tau * p.z(j), 1e-15, s"theta($j)")
        })
    }
  }

  pureTest("specs compose beyond the arities mapN covers") {
    val eight = wide.params
    val ninth = ParamSpec.scalar("offset", Constraint.OffsetMultiplier(1.5, 0.25))
    val composed = eight.product(ninth)
    val u = IArray.from(wideAt.toList :+ 0.8)
    val (value, jacobian) = composed.read[Double](u)
    check(composed.names.length == eight.names.length + 1, "the ninth block is missing")
      .and(closeRel(value._2, 1.5 + 0.25 * 0.8, 1e-15, "the ninth value"))
      .and(
        closeRel(
          jacobian,
          eight.read[Double](wideAt)._2 + math.log(0.25),
          1e-13,
          "the ninth Jacobian"
        )
      )
      .and(check(composed.validated.isRight, "the composed names clash"))
  }
