package bayes4s

import org.apache.commons.numbers.gamma.Erfc
import org.apache.commons.numbers.gamma.RegularizedGamma

import bayes4s.testkit.Approx
import bayes4s.testkit.MonteCarlo
import weaver.SimpleIOSuite

/** The same distributional properties as `RngSuite`, at ten times the sample size and over a wider
  * sweep of parameters. Run by `sbt slow:test`, never by `sbt test`.
  *
  * With `-Dbayes4s.stress=true` the seed comes from the clock instead of being fixed, which is the
  * only place in the suite where that is allowed: the weekly workflow exists precisely to try
  * seeds nobody chose. The seed is printed in every message, so a stress failure can be replayed
  * exactly by passing it back as a fixed seed.
  */
object RngSlowSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private val stress: Boolean = sys.props.get("bayes4s.stress").contains("true")
  private val root: Long = if stress then System.currentTimeMillis() else 20261104L
  private val n = 1_000_000

  private def label(what: String): String = s"$what (seed $root, n = $n)"


  /** The draws, as a primitive array, by threading the seed through the program one step at a
    * time. `replicateA` would build a chain and a boxed list of a million elements; this builds
    * neither, and stays exactly as pure.
    */
  private def drawArray(program: Rand[Double], from: Seed, count: Int): Array[Double] =
    Iterator
      .iterate((from, 0.0))((s, _) => program.run(s).value)
      .drop(1)
      .take(count)
      .map(_._2)
      .toArray

  private def average(xs: Array[Double]): Double = xs.sum / xs.length

  private def spread(xs: Array[Double]): Double =
    val m = average(xs)
    xs.map(x => (x - m) * (x - m)).sum / (xs.length - 1)

  private def ks(xs: Array[Double], cdf: Double => Double): Double =
    val sorted = xs.sorted
    val size = sorted.length.toDouble
    sorted.zipWithIndex.map { (x, i) =>
      val f = cdf(x)
      math.max(math.abs(f - i / size), math.abs(f - (i + 1) / size))
    }.max

  pureTest("the normal sampler holds at a million draws") {
    val xs = drawArray(Rng[Rand].normal, Seed(root), n)
    val statistic = ks(xs, x => 0.5 * Erfc.value(-x / math.sqrt(2.0)))
    withinStandardErrors(average(xs), 0.0, 1.0 / math.sqrt(n), 4.0, label("the normal mean"))
      .and(
        withinStandardErrors(
          spread(xs),
          1.0,
          math.sqrt(2.0 / n),
          4.0,
          label("the normal variance")
        )
      )
      .and(
        check(
          statistic <= 1.95 / math.sqrt(n),
          s"${label("Kolmogorov-Smirnov")} = $statistic against ${1.95 / math.sqrt(n)}"
        )
      )
  }

  pureTest("the gamma sampler holds across nine shapes") {
    forEach(List(0.05, 0.2, 0.5, 0.999, 1.0, 1.001, 3.0, 20.0, 500.0)) { shape =>
      val xs = drawArray(Sampling.gamma[Rand](shape), Seed(root).fold(shape.hashCode.toLong), n)
      val statistic = ks(xs, x => RegularizedGamma.P.value(shape, x))
      withinStandardErrors(
        average(xs),
        shape,
        math.sqrt(shape / n),
        4.0,
        label(s"the mean of gamma($shape)")
      ).and(
        check(
          statistic <= 1.95 / math.sqrt(n),
          s"${label(s"gamma($shape) Kolmogorov-Smirnov")} = $statistic"
        )
      )
    }
  }

  pureTest("the Poisson sampler holds across the whole range of means") {
    forEach(List(1e-6, 0.01, 0.5, 2.0, 9.999, 10.0, 10.001, 30.0, 1e3, 1e5, 1e7)) { lambda =>
      val xs =
        drawArray(Sampling.poisson[Rand](lambda).map(_.toDouble), Seed(root).fold(lambda.hashCode.toLong), n)
      withinStandardErrors(
        average(xs),
        lambda,
        math.sqrt(lambda / n),
        4.0,
        label(s"the mean of poisson($lambda)")
      ).and(
        withinStandardErrors(
          spread(xs),
          lambda,
          lambda * math.sqrt(2.0 / n) * math.sqrt(1.0 + 1.0 / lambda),
          4.0,
          label(s"the variance of poisson($lambda)")
        )
      )
    }
  }

  pureTest("sixteen split streams stay mutually uncorrelated") {
    val count = 100_000
    val streams = (0 until 16).toList.map { i =>
      drawArray(Rng[Rand].uniform, Seed(root).fold(i.toLong), count).toList
    }
    val limit = 4.0 / math.sqrt(count)
    val pairs = for
      i <- (0 until 16).toList
      j <- (i + 1) until 16
    yield (i, j)
    forEach(pairs) { (i, j) =>
      val a = streams(i)
      val b = streams(j)
      val ma = average(a.toArray)
      val mb = average(b.toArray)
      val covariance = a.zip(b).map((x, y) => (x - ma) * (y - mb)).sum / count
      val r = covariance / math.sqrt(spread(a.toArray) * spread(b.toArray))
      check(math.abs(r) <= limit, s"${label(s"the correlation of streams $i and $j")} = $r")
    }
  }
