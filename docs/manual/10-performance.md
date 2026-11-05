# 10. Performance and pitfalls

This chapter is written in two halves. The cost of `Real[A]` and the vectorised primitives is
measured here, at phase 2, because that is when it can be; the pitfalls of simulation and scoring
arrive with the chapters that introduce them.

## What a gradient costs

Scala 3 has no `@specialized`, so a function written against an abstract scalar boxes its values.
That is the price of writing a density once and differentiating it three ways, and it is contained
rather than denied: the expensive operations of a realistic model are **vectorised primitives**
that work on an `IArray` and add a single node to the tape.

The figures below were measured by JMH on an Apple M2 Pro with 16 GB and OpenJDK 25, with
`sbt "benchmarks/Jmh/run -i 5 -wi 3 -f1 -t1 bayes4s.GradientBenchmark"`. They are not a promise
about your machine; the ratios between them are the interesting part.

| What | Time per call |
|---|---|
| A reporting-triangle model (36 months, 12 lags, 234 cells, 52 parameters): value | 73.9 µs |
| the same model: value **and** gradient | 148.9 µs |
| 10 000 observations in 50 clusters, vectorised: value and gradient | 79.0 µs |
| the same model with 100 observations | 2.5 µs |
| the same 10 000 observations with one predictor written out per observation | 458.4 µs |

Three things to read from that table.

**A gradient costs about twice a value.** That is the central claim of reverse-mode differentiation
and it holds here: 148.9 against 73.9 µs for a model with 52 parameters. Forward differences would
have cost 52 values, and a hand-written gradient would have cost a bug.

**A vectorised density is 5.8 times faster than the same arithmetic written out.** The last two
rows are the same model and the same answer; the difference is that one of them writes
`slope * predictor(i)` once per observation. That costs three tape nodes a row — the constant
`predictor(i)` is itself a node — so the tape holds 30 103 nodes instead of 89.

**The tape stops growing before the work does.** Ten thousand observations cost 32 times what a
hundred cost, not 100 times, and the node count is identical — 89 either way, of which 53 are the
parameters themselves. A vectorised primitive still reads
every observation; what it avoids is a node, an edge and a reverse sweep for each of them.

## Counting nodes

`Reverse.nodeCount` is the diagnostic to reach for when a model is slower than it should be. It
reports the size of the tape for one evaluation.

```scala mdoc:silent
import bayes4s.*
import bayes4s.syntax.*

val observations = 2_000
val clusters = 20
val group = IArray.tabulate(observations)(i => i % clusters)
val ys = IArray.tabulate(observations)(i => 0.5 * math.cos(i.toDouble))

/** Every observation gets its cluster's level, and the whole likelihood is one primitive. */
val vectorised: DiffFn = new DiffFn:
  def apply[A: Real](x: IArray[A]): A =
    val alpha = Real[A].vectorOf(clusters)(g => x(g))
    LogDensity.normal(ys, Real[A].gather(alpha, group), x(clusters))

/** The same function, one term at a time. */
val written: DiffFn = new DiffFn:
  def apply[A: Real](x: IArray[A]): A =
    val alpha = Real[A].vectorOf(clusters)(g => x(g))
    Real[A].sum(
      Real[A].vectorOf(observations)(i =>
        LogDensity.normal(ys(i).lift[A], alpha(group(i)), x(clusters))
      )
    )

val at = IArray.from(List.tabulate(clusters)(g => 0.1 * g - 0.5) ++ List(1.1))
```

The two agree on the answer, to the last two digits of a log density near -2573 — they add the
same terms in a different order, which is all a vectorised primitive changes about the arithmetic:

```scala mdoc
Reverse.value(vectorised, at)
Reverse.value(written, at)
```

and disagree by three orders of magnitude on what they cost to differentiate:

```scala mdoc
Reverse.nodeCount(vectorised, at)
Reverse.nodeCount(written, at)
```

The rule of thumb that follows: if a model's node count grows with the number of observations, look
for a loop that should have been a vectorised primitive. The ones that exist are `sum`,
`cumulativeSum`, `logSumExp`, `dot`, `matVec`, `lowerSolve`, `gather` and one per density —
`LogDensity.normal`, `LogMass.poisson`, `LogMass.poissonLog`, `LogMass.bernoulliLogit` and
`LogMass.binomialLogit` all take vectors.

## Pitfalls

The pitfalls of simulation, censoring and scoring are introduced with the chapters that cover them;
this section is completed at phase 12. The one that belongs here is the pitfall of measuring a
gradient at all: a model whose density is wrong has a perfectly fast gradient, so measure after the
checks of [chapter 5](index.md) pass and not before.
