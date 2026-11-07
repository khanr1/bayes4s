# 1. Concepts

Three ideas carry the whole library. Randomness is a value, errors are values, and numeric
functions are total. None of them is a matter of taste: each one buys something you can point at.

A fourth idea — that a log density is written once against an abstract arithmetic and read by
several interpreters — is the subject of the last section.

## A seed is a value

A seed is not a generator you call; it is a state you pass in and get back.

```scala mdoc
import bayes4s.*

val seed = Seed(42)

val (afterOne, first) = seed.next
val (afterTwo, second) = afterOne.next
```

Calling `next` on the same seed twice gives the same answer, because it is a function:

```scala mdoc
seed.next == seed.next
```

That is what makes a run reproducible, and it is why four chains computed in parallel give
bit-for-bit the same samples as four chains computed in sequence. For independent streams, derive
them by index rather than by sharing a counter:

```scala mdoc
val chains = (0 until 4).map(i => seed.fold(i.toLong).next._2).toList
```

## A random program is a value too

`Rand[A]` is `State[Seed, A]`: a description of a computation that consumes randomness. Nothing
happens until you run it with a seed.

```scala mdoc
import cats.Monad

val threeDraws: Rand[List[Double]] = Monad[Rand].replicateA(3, Rng[Rand].uniform)

threeDraws.runA(Seed(7)).value
threeDraws.runA(Seed(7)).value
```

The same program and the same seed give the same list, every time, on any machine. `uniform` lives
on the **open** interval (0, 1), which is why nothing downstream has to guard against `log(0)`.

Samplers are written once against this capability:

```scala mdoc
Monad[Rand].replicateA(5, Sampling.poisson[Rand](3.5)).runA(Seed(1)).value
```

## Errors are values

A distribution is built by a smart constructor, which returns `Either` rather than throwing. The
error names the place, the parameter, the offending value and the rule it broke.

```scala mdoc
Normal.of(0.0, 1.0)
Normal.of(0.0, -1.0)
```

So a model that reads parameters from a file, or from a user, reports *which* parameter was wrong
instead of unwinding a stack:

```scala mdoc
val built = for
  location <- Normal.of(0.0, 10.0)
  scale <- HalfNormal.of(2.5)
yield (location, scale)

built.fold(error => s"rejected: ${error.message}", _ => "accepted")
```

Because an instance can only be made through its constructor, nothing downstream has to re-check
it: `logPdf` is arithmetic, not validation.

## Numeric functions are total

Every numeric function returns a documented value for every double, including the ones that break
textbook formulas. It never throws.

```scala mdoc
LogExp.invLogit(-800.0)
LogExp.log1pExp(800.0)
Special.lgamma(0.0)
Special.invPhi(1e-300)
```

The last one matters later: the rank normalisation of the diagnostics needs the Gaussian quantile
far
into the tail, where `erfinv(2p - 1)` has no digits left.

A value outside a distribution's support is not an error either. It is `-Inf`, which is exactly
what a sampler needs in order to reject a proposal instead of crashing on it:

```scala mdoc
Poisson.of(2.0).map(law => (law.logMass(3), law.logMass(-1)))
```

An invalid *parameter* is refused by the constructor; an impossible *observation* is a density of
zero. The distinction is the contract, and it is what keeps the sampler and the model honest about
different kinds of trouble.

## A density is written once, for three interpreters

A sampler needs the gradient of a log density, not only its value. Writing the derivative by hand
is how a model gets a subtle sign error that no test on the value can see, so nothing here is
written twice: a density is written once against an abstract scalar, `Real[A]`, and read by several
interpreters.

```scala mdoc:silent
import bayes4s.syntax.*

/** A Gaussian location with a half-Gaussian scale, as a model's target is: a function of the
  * parameter vector and nothing else.
  */
val target: DiffFn = new DiffFn:
  def apply[A: Real](x: IArray[A]): A =
    val mu = x(0)
    val sigma = x(1)
    val observations = IArray(1.2, 0.8, 1.9, 1.1, 0.4)
    LogDensity.normal(observations, Real[A].filled(5)(mu), sigma) +
      LogDensity.normal(mu, 0.0.lift[A], 5.0.lift[A]) +
      LogDensity.halfNormal(sigma, 1.0.lift[A])
```

`DiffFn` takes its scalar type at the call, not at the class: that is what stops a value of type
`A` from being kept after the call returns, and therefore what stops a tape from leaking out of the
gradient that owns it.

The value comes from the `Double` interpreter, with no tape at all:

```scala mdoc
Reverse.value(target, IArray(1.0, 1.5))
```

The gradient comes from the same function read by a reverse-mode tape, which records the forward
computation and sweeps it backwards once:

```scala mdoc
Reverse.valueAndGradient(target, IArray(1.0, 1.5))
```

The cost of that gradient is a small multiple of the cost of the value — about two on the models
measured in [chapter 10](10-performance.md) — and the multiple does not grow with the number of
parameters. That is the whole reason a sampler can afford gradients.

Nothing above asked for a derivative to be written by hand, and nothing in your model will. If you
want to check one anyway, `GradientCheck` compares the tape against central differences:

```scala mdoc
GradientCheck(target, IArray(1.0, 1.5)).maxError
```

The library's own suites go further and compare the tape against a third interpreter, dual numbers,
which carries an exact derivative through every operation and shares no code with the tape. That
oracle lives in the test sources, because it is not something a model needs; what it buys is in
`docs/validation.md`.

### Vectorised primitives, and why your model should use them

`LogDensity.normal(observations, means, sigma)` above took a whole vector. It is not a convenience:
the vectorised form is **one node of the tape**, whatever the length of the vector, where the same
sum written as a loop is a node per term. `Reverse.nodeCount` shows the difference, and
[chapter 10](10-performance.md) measures what it costs.

The same holds for a parameter indexed by cluster. `gather(alpha, group)` gives every observation
its own cluster's parameter and costs no node at all — it is index aliasing, and its adjoint
accumulates, for each cluster, the adjoints of all its observations. A multilevel model with ten
thousand observations in fifty clusters therefore has a tape whose size does not depend on the ten
thousand.
