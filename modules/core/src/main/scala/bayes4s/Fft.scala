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

/** A complex spectrum held as two parallel arrays, which is what the autocovariance of the
  * diagnostics consumes and what an in-place transform can fill without boxing.
  */
final case class Spectrum(re: IArray[Double], im: IArray[Double])

/** Radix-2 Cooley-Tukey transform.
  *
  * The length must be a power of two; anything else is a `Left` rather than a silently wrong
  * answer. Validated against a naive O(n^2) discrete Fourier transform written in the tests.
  *
  * This is the first of the two places in the library where a pure function mutates, and the
  * reason is the whole point of the exception: an immutable butterfly would allocate a pair of
  * arrays per stage and per block, which for the thousand-draw autocovariance of the diagnostics
  * means tens of thousands of allocations where two suffice. The arrays are created here, written
  * here, wrapped as `IArray` and returned; no caller can reach them while they are mutable and no
  * two calls share anything. The twiddle factors are recomputed per butterfly rather than carried
  * by a recurrence: a recurrence drifts by a few ulps per stage, and the diagnostics compare
  * against Stan at 1e-10.
  */
object Fft:

  def forward(re: IArray[Double], im: IArray[Double]): Either[BayesError, Spectrum] =
    checked(re, im).map(_ => run(re, im, inverse = false))

  def inverse(re: IArray[Double], im: IArray[Double]): Either[BayesError, Spectrum] =
    checked(re, im).map(_ => run(re, im, inverse = true))

  /** The transform of a real signal, with a zero imaginary part. */
  def forwardReal(xs: IArray[Double]): Either[BayesError, Spectrum] =
    forward(xs, IArray.fill(xs.length)(0.0))

  private def checked(re: IArray[Double], im: IArray[Double]): Either[BayesError, Unit] =
    val n = re.length
    if n != im.length then Left(BayesError.DimensionMismatch("Fft", n, im.length))
    else if n < 1 || (n & (n - 1)) != 0 then
      Left(
        BayesError.InvalidArgument("Fft", "length", n.toDouble, "a power of two, at least one")
      )
    else Right(())

  private def run(reIn: IArray[Double], imIn: IArray[Double], inverse: Boolean): Spectrum =
    val n = reIn.length
    if n == 1 then Spectrum(reIn, imIn)
    else
      val bits = Integer.numberOfTrailingZeros(n)
      val re = Array.tabulate(n)(i => reIn(reversed(i, bits)))
      val im = Array.tabulate(n)(i => imIn(reversed(i, bits)))
      val sign = if inverse then 2.0 else -2.0
      var size = 2 // scalafix:ok DisableSyntax.var; stage width, local to this call
      while size <= n do // scalafix:ok DisableSyntax.while; log2(n) stages
        val half = size / 2
        var start = 0 // scalafix:ok DisableSyntax.var; first index of the current block
        while start < n do // scalafix:ok DisableSyntax.while; n/size blocks per stage
          var k = 0 // scalafix:ok DisableSyntax.var; butterfly within the block
          while k < half do // scalafix:ok DisableSyntax.while; half butterflies per block
            val angle = sign * math.Pi * k / size
            val wr = math.cos(angle)
            val wi = math.sin(angle)
            val i = start + k
            val j = i + half
            val tr = re(j) * wr - im(j) * wi
            val ti = re(j) * wi + im(j) * wr
            re(j) = re(i) - tr
            im(j) = im(i) - ti
            re(i) = re(i) + tr
            im(i) = im(i) + ti
            k = k + 1
          start = start + size
        size = size * 2
      if inverse then
        val scale = 1.0 / n
        Spectrum(
          IArray.unsafeFromArray(Array.tabulate(n)(i => re(i) * scale)),
          IArray.unsafeFromArray(Array.tabulate(n)(i => im(i) * scale))
        )
      else Spectrum(IArray.unsafeFromArray(re), IArray.unsafeFromArray(im))

  /** The index with its lowest `bits` bits reversed, which is the order the stages expect. */
  private def reversed(i: Int, bits: Int): Int = Integer.reverse(i) >>> (32 - bits)
