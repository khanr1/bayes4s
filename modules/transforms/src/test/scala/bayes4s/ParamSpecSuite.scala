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
import cats.Applicative
import cats.Eq
import cats.Id
import cats.laws.discipline.ApplicativeTests
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.discipline.Discipline
import weaver.scalacheck.Checkers

/** `ParamSpec` as a value: what it says about the parameters, what it reads, and the applicative
  * laws its combination obeys.
  *
  * The two halves have to agree, and nothing but a test can make them: `layout` is what names the
  * values and sizes the unconstrained vector, `read` is what a model actually sees, and a spec
  * whose layout claimed one thing while its reading did another would produce a model that fits
  * and a CSV that lies.
  */
object ParamSpecSuite extends SimpleIOSuite with Discipline with Checkers with Approx:

  final case class Two[A](first: A, second: A)
  final case class Three[A](first: A, second: A, third: A)

  /** The shape of the plan's own example: eight blocks, three of them constrained. */
  final case class Nowcast[A](
      a: A,
      b: A,
      phi: A,
      tau: A,
      eps: IArray[A],
      c0: A,
      inc: IArray[A],
      beta: A
  )

  private val spec: ParamSpec[Nowcast] =
    (
      ParamSpec.real("a"),
      ParamSpec.real("b"),
      ParamSpec.unitInterval("phi"),
      ParamSpec.positive("tau"),
      ParamSpec.vector("eps", 6),
      ParamSpec.real("c0"),
      ParamSpec.vector("inc", 3, Constraint.Positive),
      ParamSpec.real("beta")
    ).mapN[Nowcast](
      // The plan writes `.mapN(Nowcast.apply)`, which Scala 3 cannot do: it eta-expands a
      // polymorphic method with `A := Any` before checking it against a polymorphic function type.
      // The wrapper is what the language allows (docs/design.md).
      [A] =>
        (a: A, b: A, phi: A, tau: A, eps: IArray[A], c0: A, inc: IArray[A], beta: A) =>
          Nowcast(a, b, phi, tau, eps, c0, inc, beta)
    )

  private val at: IArray[Double] =
    IArray(0.4, -0.2, 0.7, -0.5, 0.1, 0.2, 0.3, -0.1, -0.3, 0.05, 1.1, -0.4, 0.6, 0.25, 0.9)

  pureTest("the layout says what the model declared, in order") {
    val blocks = spec.layout
    val names = blocks.map(_.name)
    val sizes = blocks.map(_.size)
    val constraints = blocks.map(_.constraint)
    check(names == List("a", "b", "phi", "tau", "eps", "c0", "inc", "beta"), s"names are $names")
      .and(check(sizes == List(1, 1, 1, 1, 6, 1, 3, 1), s"sizes are $sizes"))
      .and(
        check(
          constraints == List(
            Constraint.Unconstrained,
            Constraint.Unconstrained,
            Constraint.UnitInterval,
            Constraint.Positive,
            Constraint.Unconstrained,
            Constraint.Unconstrained,
            Constraint.Positive,
            Constraint.Unconstrained
          ),
          s"constraints are $constraints"
        )
      )
      .and(check(spec.unconstrainedSize == 15, s"${spec.unconstrainedSize} reals, expected 15"))
      .and(check(at.length == 15, "the test point is the wrong length"))
  }

  pureTest("every value is named, the way CmdStan names it") {
    val expected = List("a", "b", "phi", "tau") ++
      List.tabulate(6)(i => s"eps.${i + 1}") ++ List("c0") ++
      List.tabulate(3)(i => s"inc.${i + 1}") ++ List("beta")
    check(spec.names == expected, s"the names are ${spec.names}")
      .and(
        check(
          ParamSpec
            .matrix("B", 2, 3)
            .names == List("B.1.1", "B.2.1", "B.1.2", "B.2.2", "B.1.3", "B.2.3"),
          s"a matrix is named ${ParamSpec.matrix("B", 2, 3).names}"
        )
      )
      .and(
        check(
          ParamSpec.choleskyCorr("L", 3).names ==
            List("L.1.1", "L.2.1", "L.3.1", "L.1.2", "L.2.2", "L.3.2", "L.1.3", "L.2.3", "L.3.3"),
          s"a factor is named ${ParamSpec.choleskyCorr("L", 3).names}"
        )
      )
      .and(
        check(
          ParamSpec.matrix("B", 2, 3).unconstrainedSize == 6 &&
            ParamSpec.choleskyCorr("L", 3).unconstrainedSize == 3,
          "a matrix or a factor wants the wrong number of reals"
        )
      )
  }

  /** The reading, against the transforms applied by hand to the same slices.
    *
    * This is the test that makes `layout` and `read` agree: the slices come from the layout's
    * sizes, and the values come from `read`.
    */
  pureTest("the reading gives each block its own slice, transformed, and sums the Jacobians") {
    val (p, jacobian) = spec.read[Double](at)
    def transformed(constraint: Constraint, from: Int, size: Int): (IArray[Double], Double) =
      Transform.of(constraint).constrain[Double](IArray.tabulate(size)(i => at(from + i)))
    val (phi, phiJacobian) = transformed(Constraint.UnitInterval, 2, 1)
    val (tau, tauJacobian) = transformed(Constraint.Positive, 3, 1)
    val (eps, _) = transformed(Constraint.Unconstrained, 4, 6)
    val (inc, incJacobian) = transformed(Constraint.Positive, 11, 3)
    val expectedJacobian = phiJacobian + tauJacobian + incJacobian
    check(p.a == at(0) && p.b == at(1) && p.c0 == at(10) && p.beta == at(14), "a free scalar moved")
      .and(closeRel(p.phi, phi(0), 1e-15, "phi"))
      .and(closeRel(p.tau, tau(0), 1e-15, "tau"))
      .and(forEach((0 until 6).toList)(i => closeRel(p.eps(i), eps(i), 1e-15, s"eps($i)")))
      .and(forEach((0 until 3).toList)(i => closeRel(p.inc(i), inc(i), 1e-15, s"inc($i)")))
      .and(closeRel(jacobian, expectedJacobian, 1e-14, "the total log Jacobian"))
  }

  pureTest("a free spec has no Jacobian at all, exactly") {
    val free =
      (ParamSpec.real("x"), ParamSpec.vector("y", 3))
        .mapN[IArray]([A] => (x: A, y: IArray[A]) => y)
    val (_, jacobian) = free.read[Double](IArray(1.0, 2.0, 3.0, 4.0))
    check(jacobian == 0.0, s"it added $jacobian")
  }

  pureTest("two blocks of one name are refused, and the error names the name") {
    val clash = (ParamSpec.real("mu"), ParamSpec.positive("mu")).mapN[Id]([A] => (a: A, b: A) => a)
    val fine =
      (ParamSpec.real("mu"), ParamSpec.positive("tau")).mapN[Id]([A] => (a: A, b: A) => a)
    clash.validated match
      case Right(_) => failure("two parameters named 'mu' were accepted")
      case Left(error) =>
        check(error.message.contains("mu"), s"the error does not name it: ${error.message}")
          .and(check(fine.validated.isRight, "a spec with distinct names was refused"))
  }

  test("the reading is the same function whichever interpreter reads it") {
    forall(Gen.listOfN(15, Gen.choose(-3.0, 3.0))) { values =>
      val u = IArray.from(values)
      val (plain, plainJacobian) = spec.read[Double](u)
      // Through the tape, which reads the same spec with its own arithmetic.
      val target: DiffFn = new DiffFn:
        def apply[A: Real](x: IArray[A]): A =
          val (p, jacobian) = spec.read[A](x)
          Real[A].plus(Real[A].plus(p.a, p.tau), jacobian)
      val taped = Reverse.valueAndGradient(target, u)
      val oracle = Dual.gradient(target, u)
      closeRel(taped.value, plain.a + plain.tau + plainJacobian, 1e-14, "the value")
        .and(forEach((0 until u.length).toList) { i =>
          closeRel(taped.gradient(i), oracle(i), 1e-12, s"d/du($i)")
        })
    }
  }

  // --- the applicative laws -------------------------------------------------------------------
  //
  // `ParamSpec[T[_]]` abstracts over a type constructor, so `Semigroupal[ParamSpec]` cannot be
  // written: cats wants an `F` of kind `* -> *`. What cats can talk about is the shape where the
  // value does not depend on the scalar — `ParamSpec[[A] =>> V]` — and that is not a degenerate
  // corner: such a spec still declares blocks, still consumes its slice of the unconstrained
  // vector and still accumulates a log Jacobian, so the laws below are about the layout
  // concatenation and the Jacobian accumulation, which is all the applicative structure there is.

  private type Constant[V] = ParamSpec[[A] =>> V]

  private given applicative: Applicative[Constant] with
    def pure[V](v: V): Constant[V] = ParamSpec.pure(v)
    def ap[V, W](ff: Constant[V => W])(fa: Constant[V]): Constant[W] =
      ff.product(fa).map[[A] =>> W]([A] => (p: (V => W, V)) => p._1(p._2))

  /** A few unconstrained vectors, long enough for any spec the generator builds. */
  private val samples: List[IArray[Double]] = List(
    IArray.tabulate(12)(i => 0.1 * i - 0.5),
    IArray.tabulate(12)(i => math.sin(i.toDouble)),
    IArray.fill(12)(0.0)
  )

  /** Two specs are the same when they declare the same blocks and read the same thing.
    *
    * The values are compared exactly; the log Jacobian to 1e-12, because floating-point addition
    * is not associative and the associativity law reassociates exactly those additions. The
    * tolerance is on the only quantity it has to be on, and a dropped block or a dropped Jacobian
    * fails by far more than 1e-12.
    */
  private given [V: Eq]: Eq[Constant[V]] = Eq.instance { (left, right) =>
    val layouts = left.layout == right.layout
    val readings = samples.forall { u =>
      val (leftValue, leftJacobian) = left.read[Double](u)
      val (rightValue, rightJacobian) = right.read[Double](u)
      Eq[V].eqv(leftValue, rightValue) &&
      math.abs(leftJacobian - rightJacobian) <= 1e-12 * math.max(1.0, math.abs(leftJacobian))
    }
    layouts && readings
  }

  private given [V: Arbitrary]: Arbitrary[Constant[V]] = Arbitrary(
    for
      value <- Arbitrary.arbitrary[V]
      blocks <- Gen.oneOf(
        Gen.const(ParamSpec.pure(value)),
        Gen.const(ParamSpec.real("x").map[[A] =>> V]([A] => (_: A) => value)),
        Gen.const(ParamSpec.positive("y").map[[A] =>> V]([A] => (_: A) => value)),
        Gen.const(
          (ParamSpec.unitInterval("p"), ParamSpec.vector("v", 3, Constraint.Simplex))
            .mapN[[A] =>> V]([A] => (_: A, _: IArray[A]) => value)
        )
      )
    yield blocks
  )

  checkAll("ParamSpec.ApplicativeLaws", ApplicativeTests[Constant].applicative[Int, Int, Int])

  pureTest("mapN reads the blocks in the order they were written, whatever the arity") {
    val two =
      (ParamSpec.real("a"), ParamSpec.real("b")).mapN[Two]([A] => (a: A, b: A) => Two(a, b))
    val three =
      (ParamSpec.real("a"), ParamSpec.real("b"), ParamSpec.real("c"))
        .mapN[Three]([A] => (a: A, b: A, c: A) => Three(a, b, c))
    val u = IArray(1.0, 2.0, 3.0)
    val first = two.read[Double](u)._1
    val second = three.read[Double](u)._1
    check(first == Two(1.0, 2.0), s"two blocks came out as $first")
      .and(check(second == Three(1.0, 2.0, 3.0), s"three blocks came out as $second"))
      .and(check(two.names == List("a", "b") && three.names == List("a", "b", "c"), "the names"))
  }
