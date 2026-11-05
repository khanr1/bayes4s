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

import scala.annotation.targetName
import scala.reflect.ClassTag

/** An index into the tape of the call that created it.
  *
  * Opaque, so that nothing outside this file can make one or read one. Together with the rank-2
  * type of [[DiffFn]], that is what keeps the tape safe: a value of this type cannot be stored
  * anywhere that outlives the call, so the mutable tape it indexes into can be thrown away with
  * certainty when the call returns.
  */
opaque type Rev = Int

/** Reverse-mode automatic differentiation.
  *
  * One forward pass records the computation on a tape, one backward pass accumulates the adjoints.
  * The cost of the gradient is a small multiple of the cost of the value, whatever the number of
  * parameters, which is the only reason a sampler can afford gradients at all.
  */
object Reverse:

  /** The value alone, through the `Double` interpreter: no tape, no allocation. */
  def value(f: DiffFn, x: IArray[Double]): Double = f.apply[Double](x)

  def valueAndGradient(f: DiffFn, x: IArray[Double]): ValueAndGradient =
    val tape = new Rev.Tape(x)
    val output = f.apply[Rev](tape.inputs)(using new Rev.RevReal(tape))
    ValueAndGradient(tape.valueOf(output), tape.gradient(output, x.length))

  /** How many nodes the tape holds for this input: a diagnostic, and the thing to look at when a
    * model is slower than it should be.
    *
    * A model that evaluates ten thousand observations through `gather` and one vectorised density
    * has a node count that does not depend on ten thousand. One that writes the same model as a
    * loop over observations has a node count that does, and this is how the difference is seen.
    */
  def nodeCount(f: DiffFn, x: IArray[Double]): Int =
    val tape = new Rev.Tape(x)
    f.apply[Rev](tape.inputs)(using new Rev.RevReal(tape))
    tape.size

/** The companion, and the only scope where `Rev` is an `Int`.
  *
  * The tape and the arithmetic that records itself live here for that reason alone: an opaque type
  * is transparent in its own companion and nowhere else, so this is where an index can be read as
  * an index. Everything here is package-private; `Reverse` is the facade.
  */
object Rev:

  // --- the tape -----------------------------------------------------------------------------

  /** The tape of one call: node values, and a flat edge list of (parent, partial derivative).
    *
    * This is the mutation the design allows and this file is where it is confined. An immutable
    * tape would allocate a structure per elementary operation — hundreds of thousands of them for
    * one gradient of a realistic model — and the sampler would be slower by orders of magnitude.
    * The tape is created in `valueAndGradient`, written only through the methods below, read once
    * backwards, and dropped; it appears in no public signature and two concurrent calls share
    * nothing.
    *
    * Every node's parents have smaller indices than itself, because a node is only created after
    * the nodes it depends on. One reverse sweep is therefore enough.
    */
  final private[bayes4s] class Tape(at: IArray[Double]):
    private val inputCount: Int = at.length
    private var values: Array[Double] = new Array[Double](
      math.max(16, inputCount * 2)
    ) // scalafix:ok DisableSyntax.var; the tape grows
    private var starts: Array[Int] = new Array[Int](
      math.max(17, inputCount * 2 + 1)
    ) // scalafix:ok DisableSyntax.var; the tape grows
    private var parents: Array[Int] =
      new Array[Int](32) // scalafix:ok DisableSyntax.var; the tape grows
    private var partials: Array[Double] =
      new Array[Double](32) // scalafix:ok DisableSyntax.var; the tape grows
    private var nodes: Int = 0 // scalafix:ok DisableSyntax.var; the write cursor
    private var edges: Int = 0 // scalafix:ok DisableSyntax.var; the write cursor

    def size: Int = nodes

    def valueOf(node: Rev): Double = values(node)

    /** The input nodes, in order: node `i` is parameter `i`, holding its value. */
    val inputs: IArray[Rev] = IArray.tabulate(inputCount)(i => leaf(at(i)))

    def leaf(x: Double): Rev =
      val node = reserve(0)
      values(node) = x
      node

    def unary(parent: Rev, value: Double, partial: Double): Rev =
      val node = reserve(1)
      values(node) = value
      parents(edges - 1) = parent
      partials(edges - 1) = partial
      node

    def binary(a: Rev, b: Rev, value: Double, pa: Double, pb: Double): Rev =
      val node = reserve(2)
      values(node) = value
      parents(edges - 2) = a
      partials(edges - 2) = pa
      parents(edges - 1) = b
      partials(edges - 1) = pb
      node

    /** One node whatever the number of parents: this is what a vectorised primitive pushes. */
    def nary(value: Double, count: Int, parentAt: Int => Rev, partialAt: Int => Double): Rev =
      val first = edges
      val node = reserve(count)
      values(node) = value
      var i = 0 // scalafix:ok DisableSyntax.var; filling the edges of one node
      while i < count do // scalafix:ok DisableSyntax.while; one edge per parent
        parents(first + i) = parentAt(i)
        partials(first + i) = partialAt(i)
        i += 1
      node

    def gradient(output: Rev, wanted: Int): IArray[Double] =
      val adjoints = new Array[Double](nodes)
      adjoints(output) = 1.0
      var node = nodes - 1 // scalafix:ok DisableSyntax.var; the reverse sweep cursor
      while node >= 0 do // scalafix:ok DisableSyntax.while; one pass, backwards
        val adjoint = adjoints(node)
        if adjoint != 0.0 then
          var e = starts(node) // scalafix:ok DisableSyntax.var; the edge cursor
          val last = starts(node + 1)
          while e < last do // scalafix:ok DisableSyntax.while; the edges of this node
            adjoints(parents(e)) += adjoint * partials(e)
            e += 1
        node -= 1
      IArray.tabulate(wanted)(i => adjoints(i))

    /** Makes room for one node with `edgeCount` edges and returns its index. */
    private def reserve(edgeCount: Int): Int =
      if nodes + 1 >= values.length then
        values = grow(values, values.length * 2)
        starts = grow(starts, starts.length * 2)
      if edges + edgeCount > parents.length then
        val wanted = math.max(parents.length * 2, edges + edgeCount)
        parents = grow(parents, wanted)
        partials = grow(partials, wanted)
      val node = nodes
      starts(node) = edges
      edges += edgeCount
      nodes += 1
      starts(nodes) = edges
      node

    private def grow(xs: Array[Double], to: Int): Array[Double] =
      val bigger = new Array[Double](to)
      System.arraycopy(xs, 0, bigger, 0, xs.length)
      bigger

    private def grow(xs: Array[Int], to: Int): Array[Int] =
      val bigger = new Array[Int](to)
      System.arraycopy(xs, 0, bigger, 0, xs.length)
      bigger

  /** The arithmetic that records itself.
    *
    * Each scalar operation pushes one node with its partial derivatives; each vectorised primitive
    * pushes **one** node whatever the length of its argument, which is what keeps a model with ten
    * thousand observations cheap to differentiate. The inherited defaults of `Real` say what each
    * primitive means, and a suite compares these overrides against them through the dual-number
    * oracle, so an override that drifted would be caught.
    */
  final private[bayes4s] class RevReal(tape: Tape) extends Real[Rev]:
    def tag: ClassTag[Rev] = ClassTag.Int

    def const(x: Double): Rev = tape.leaf(x)
    def value(a: Rev): Double = tape.valueOf(a)

    def plus(a: Rev, b: Rev): Rev = tape.binary(a, b, value(a) + value(b), 1.0, 1.0)
    def minus(a: Rev, b: Rev): Rev = tape.binary(a, b, value(a) - value(b), 1.0, -1.0)
    def times(a: Rev, b: Rev): Rev = tape.binary(a, b, value(a) * value(b), value(b), value(a))
    def div(a: Rev, b: Rev): Rev =
      val vb = value(b)
      tape.binary(a, b, value(a) / vb, 1.0 / vb, -value(a) / (vb * vb))
    def negate(a: Rev): Rev = tape.unary(a, -value(a), -1.0)

    def exp(a: Rev): Rev =
      val e = math.exp(value(a))
      tape.unary(a, e, e)
    def expm1(a: Rev): Rev =
      val v = value(a)
      tape.unary(a, math.expm1(v), math.exp(v))
    def log(a: Rev): Rev = tape.unary(a, math.log(value(a)), 1.0 / value(a))
    def log1p(a: Rev): Rev = tape.unary(a, math.log1p(value(a)), 1.0 / (1.0 + value(a)))
    def sqrt(a: Rev): Rev =
      val r = math.sqrt(value(a))
      tape.unary(a, r, 0.5 / r)
    def pow(a: Rev, p: Double): Rev =
      tape.unary(a, math.pow(value(a), p), p * math.pow(value(a), p - 1.0))

    def invLogit(a: Rev): Rev =
      val p = LogExp.invLogit(value(a))
      tape.unary(a, p, p * (1.0 - p))
    def logInvLogit(a: Rev): Rev =
      tape.unary(a, LogExp.logInvLogit(value(a)), LogExp.invLogit(-value(a)))
    def log1mInvLogit(a: Rev): Rev =
      tape.unary(a, LogExp.log1mInvLogit(value(a)), -LogExp.invLogit(value(a)))
    def log1pExp(a: Rev): Rev =
      tape.unary(a, LogExp.log1pExp(value(a)), LogExp.invLogit(value(a)))
    def lgamma(a: Rev): Rev =
      tape.unary(a, Special.lgamma(value(a)), Special.digamma(value(a)))
    def lbeta(a: Rev, b: Rev): Rev =
      val va = value(a)
      val vb = value(b)
      val shared = Special.digamma(va + vb)
      tape.binary(
        a,
        b,
        Special.lbeta(va, vb),
        Special.digamma(va) - shared,
        Special.digamma(vb) - shared
      )

    // --- one node per vectorised primitive --------------------------------------------------

    override def sum(xs: IArray[Rev]): Rev =
      val total = (0 until xs.length).foldLeft(0.0)((s, i) => s + value(xs(i)))
      tape.nary(total, xs.length, i => xs(i), _ => 1.0)

    override def logSumExp(xs: IArray[Rev]): Rev =
      if xs.length == 0 then const(Double.NegativeInfinity)
      else
        val primals = IArray.tabulate(xs.length)(i => value(xs(i)))
        val total = LogExp.logSumExp(primals)
        if total.isInfinite then const(total)
        else tape.nary(total, xs.length, i => xs(i), i => math.exp(primals(i) - total))

    override def poissonSum(ks: IArray[Int], rates: IArray[Rev]): Rev =
      val total = (0 until ks.length).foldLeft(0.0) { (s, i) =>
        val k = ks(i)
        val rate = value(rates(i))
        if k == 0 then s - rate
        else s + k * math.log(rate) - rate - Special.lgamma(k + 1.0)
      }
      tape.nary(
        total,
        ks.length,
        i => rates(i),
        i => if ks(i) == 0 then -1.0 else ks(i) / value(rates(i)) - 1.0
      )

    override def poissonLogSum(ks: IArray[Int], logRates: IArray[Rev]): Rev =
      val total = (0 until ks.length).foldLeft(0.0) { (s, i) =>
        val logRate = value(logRates(i))
        s + ks(i) * logRate - math.exp(logRate) - Special.lgamma(ks(i) + 1.0)
      }
      tape.nary(total, ks.length, i => logRates(i), i => ks(i) - math.exp(value(logRates(i))))

    override def normalSum(xs: IArray[Rev], mu: Rev, sigma: Rev): Rev =
      val n = xs.length
      val m = value(mu)
      val s = value(sigma)
      val zs = IArray.tabulate(n)(i => (value(xs(i)) - m) / s)
      val quadratic = zs.foldLeft(0.0)((acc, z) => acc + z * z)
      val total = -0.5 * quadratic - n * (math.log(s) + Real.HalfLog2Pi)
      val dMu = zs.foldLeft(0.0)((acc, z) => acc + z) / s
      val dSigma = quadratic / s - n / s
      tape.nary(
        total,
        n + 2,
        i => if i < n then xs(i) else if i == n then mu else sigma,
        i => if i < n then -zs(i) / s else if i == n then dMu else dSigma
      )

    override def normalSum(ys: IArray[Double], mus: IArray[Rev], sigma: Rev): Rev =
      val n = ys.length
      val s = value(sigma)
      val zs = IArray.tabulate(n)(i => (ys(i) - value(mus(i))) / s)
      val quadratic = zs.foldLeft(0.0)((acc, z) => acc + z * z)
      val total = -0.5 * quadratic - n * (math.log(s) + Real.HalfLog2Pi)
      val dSigma = quadratic / s - n / s
      tape.nary(
        total,
        n + 1,
        i => if i < n then mus(i) else sigma,
        i => if i < n then zs(i) / s else dSigma
      )

    @targetName("normalSumVaryingScale")
    override def normalSum(ys: IArray[Double], mus: IArray[Rev], sigmas: IArray[Rev]): Rev =
      val n = ys.length
      val zs = IArray.tabulate(n)(i => (ys(i) - value(mus(i))) / value(sigmas(i)))
      val total = (0 until n).foldLeft(0.0) { (acc, i) =>
        acc - 0.5 * zs(i) * zs(i) - math.log(value(sigmas(i))) - Real.HalfLog2Pi
      }
      tape.nary(
        total,
        2 * n,
        i => if i < n then mus(i) else sigmas(i - n),
        i =>
          if i < n then zs(i) / value(sigmas(i))
          else
            val j = i - n
            (zs(j) * zs(j) - 1.0) / value(sigmas(j))
      )

    override def bernoulliLogitSum(ks: IArray[Int], etas: IArray[Rev]): Rev =
      val total = (0 until ks.length).foldLeft(0.0) { (s, i) =>
        val eta = value(etas(i))
        s + (if ks(i) == 1 then LogExp.logInvLogit(eta) else LogExp.log1mInvLogit(eta))
      }
      tape.nary(total, ks.length, i => etas(i), i => ks(i) - LogExp.invLogit(value(etas(i))))

    override def binomialLogitSum(ks: IArray[Int], ns: IArray[Int], etas: IArray[Rev]): Rev =
      val total = (0 until ks.length).foldLeft(0.0) { (s, i) =>
        val eta = value(etas(i))
        s + ks(i) * LogExp.logInvLogit(eta) + (ns(i) - ks(i)) * LogExp.log1mInvLogit(eta) +
          Real.logBinomialCoefficient(ns(i).toDouble, ks(i).toDouble)
      }
      tape.nary(
        total,
        ks.length,
        i => etas(i),
        i => ks(i) - ns(i) * LogExp.invLogit(value(etas(i)))
      )

    override def dot(xs: IArray[Rev], ys: IArray[Rev]): Rev =
      val n = xs.length
      val total = (0 until n).foldLeft(0.0)((s, i) => s + value(xs(i)) * value(ys(i)))
      tape.nary(
        total,
        2 * n,
        i => if i < n then xs(i) else ys(i - n),
        i => if i < n then value(ys(i)) else value(xs(i - n))
      )

    /** A chain of additions rather than one node per prefix: the same numbers, linear in n. */
    override def cumulativeSum(xs: IArray[Rev]): IArray[Rev] =
      if xs.length == 0 then IArray.empty[Rev]
      else
        val running =
          (1 until xs.length).foldLeft(Vector(xs(0)))((acc, i) => acc :+ plus(acc(i - 1), xs(i)))
        IArray.from(running)
