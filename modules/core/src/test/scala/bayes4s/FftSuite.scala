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
import cats.effect.IO
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The radix-2 transform against a naive O(n^2) discrete Fourier transform written here.
  *
  * The naive form is the definition, so it shares no code and no idea with the fast one beyond the
  * mathematics: that is what makes it an oracle rather than a second opinion. The tolerance is
  * absolute and scaled by the magnitude being summed, because a sum of n terms carries about
  * n * eps of the largest term and a relative bound on a near-zero bin would be unachievable.
  */
object FftSuite extends SimpleIOSuite with Approx:

  private def naive(re: IArray[Double], im: IArray[Double]): (IArray[Double], IArray[Double]) =
    val n = re.length
    val bins = (0 until n).map { k =>
      (0 until n).foldLeft((0.0, 0.0)) { case ((sumRe, sumIm), j) =>
        val angle = -2.0 * math.Pi * k.toDouble * j.toDouble / n.toDouble
        val c = math.cos(angle)
        val s = math.sin(angle)
        (sumRe + re(j) * c - im(j) * s, sumIm + re(j) * s + im(j) * c)
      }
    }
    (IArray.from(bins.map(_._1)), IArray.from(bins.map(_._2)))

  /** A deterministic signal, built from the library's own seed so the test never drifts. */
  private def signal(n: Int): (IArray[Double], IArray[Double]) =
    val xs = Batch.normal(Seed(20261104L), 2 * n)
    (IArray.from(xs.toList.take(n)), IArray.from(xs.toList.drop(n)))

  private def magnitude(re: IArray[Double], im: IArray[Double]): Double =
    re.toList.map(math.abs).sum + im.toList.map(math.abs).sum

  pureTest("the transform equals the naive definition, for every power of two up to 64") {
    forEach(List(1, 2, 4, 8, 16, 32, 64)) { n =>
      val (re, im) = signal(n)
      val (wantRe, wantIm) = naive(re, im)
      val bound = 1e-13 * math.max(1.0, magnitude(re, im))
      Fft.forward(re, im) match
        case Left(error) => failure(s"n = $n: ${error.message}")
        case Right(got) =>
          forEach((0 until n).toList) { k =>
            close(got.re(k), wantRe(k), bound, s"n = $n, real part of bin $k")
              .and(close(got.im(k), wantIm(k), bound, s"n = $n, imaginary part of bin $k"))
          }
    }
  }

  pureTest("a unit impulse transforms to a flat spectrum") {
    val n = 16
    val re = IArray.from(1.0 +: List.fill(n - 1)(0.0))
    val im = IArray.from(List.fill(n)(0.0))
    Fft.forward(re, im) match
      case Left(error) => failure(error.message)
      case Right(got) =>
        forEach((0 until n).toList) { k =>
          close(got.re(k), 1.0, 1e-13, s"real part of bin $k")
            .and(close(got.im(k), 0.0, 1e-13, s"imaginary part of bin $k"))
        }
  }

  pureTest("a constant signal transforms to a single bin") {
    val n = 8
    val re = IArray.from(List.fill(n)(2.5))
    val im = IArray.from(List.fill(n)(0.0))
    Fft.forward(re, im) match
      case Left(error) => failure(error.message)
      case Right(got) =>
        close(got.re(0), n * 2.5, 1e-12, "bin 0 holds the whole signal").and(
          forEach((1 until n).toList) { k =>
            close(got.re(k), 0.0, 1e-12, s"real part of bin $k")
              .and(close(got.im(k), 0.0, 1e-12, s"imaginary part of bin $k"))
          }
        )
  }

  pureTest("the inverse transform returns the signal") {
    val n = 64
    val (re, im) = signal(n)
    val bound = 1e-13 * math.max(1.0, magnitude(re, im))
    val round = for
      forward <- Fft.forward(re, im)
      back <- Fft.inverse(forward.re, forward.im)
    yield back
    round match
      case Left(error) => failure(error.message)
      case Right(back) =>
        forEach((0 until n).toList) { j =>
          close(back.re(j), re(j), bound, s"real part of sample $j")
            .and(close(back.im(j), im(j), bound, s"imaginary part of sample $j"))
        }
  }

  pureTest("Parseval's identity holds") {
    val n = 32
    val (re, im) = signal(n)
    val energy = (0 until n).map(j => re(j) * re(j) + im(j) * im(j)).sum
    Fft.forward(re, im) match
      case Left(error) => failure(error.message)
      case Right(got) =>
        val spectral = (0 until n).map(k => got.re(k) * got.re(k) + got.im(k) * got.im(k)).sum
        closeRel(spectral, n * energy, 1e-12, "sum of |X|^2 = n times sum of |x|^2")
  }

  pureTest("forwardReal agrees with a zero imaginary part") {
    val n = 16
    val (re, _) = signal(n)
    val zeros = IArray.from(List.fill(n)(0.0))
    (Fft.forwardReal(re), Fft.forward(re, zeros)) match
      case (Right(a), Right(b)) =>
        forEach((0 until n).toList) { k =>
          check(a.re(k) == b.re(k) && a.im(k) == b.im(k), s"bin $k differs")
        }
      case (a, b) => failure(s"one of the two transforms failed: $a, $b")
  }

  pureTest("a length that is not a power of two is a Left, not a wrong answer") {
    forEach(List(0, 3, 5, 6, 7, 100)) { n =>
      val re = IArray.from(List.fill(n)(1.0))
      val im = IArray.from(List.fill(n)(0.0))
      check(Fft.forward(re, im).isLeft, s"n = $n was accepted")
    }
  }

  pureTest("a mismatched imaginary part is a Left") {
    check(Fft.forward(IArray(1.0, 2.0), IArray(0.0)).isLeft, "lengths 2 and 1 were accepted")
  }

  // The transform mutates arrays internally; from the outside it must be a function.
  pureTest("the transform is referentially transparent and leaves its input alone") {
    val n = 32
    val (re, im) = signal(n)
    val reBefore = re.toList
    val imBefore = im.toList
    val first = Fft.forward(re, im)
    val second = Fft.forward(re, im)
    val equal = (first, second) match
      case (Right(a), Right(b)) =>
        check(a.re.toList == b.re.toList && a.im.toList == b.im.toList, "two calls disagree")
      case _ => failure("the transform failed")
    equal
      .and(check(re.toList == reBefore, "the real input was modified"))
      .and(check(im.toList == imBefore, "the imaginary input was modified"))
  }

  // Both halves run inside IO so that a failure belongs to this test rather than to the suite.
  test("transforms computed in parallel equal the same transforms computed in sequence") {
    val indices = (0 until 16).toList
    def shifted(i: Int) =
      val (re, im) = signal(16)
      Fft.forward(re.map(_ + i), im).map(s => (s.re.toList, s.im.toList))
    for
      sequential <- IO(indices.map(shifted))
      parallel <- indices.parTraverse(i => IO(shifted(i)))
    yield check(parallel == sequential, "the parallel transforms differ")
  }
