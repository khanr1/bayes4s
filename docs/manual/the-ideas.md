# The ideas, in plain words

This page has no code in it. It is here because the rest of the manual assumes you know what a
posterior is, what a sampler is doing, and why a count series can be wrong at its right-hand edge —
and if any of those is unfamiliar, every chapter reads like a reference to something you missed.

Read it once. Nothing below is deep, and each section says which chapter does the real work.

## A model is a story about how the data happened

You write down two things. A **likelihood**: given the quantities you do not know, how probable is
the data you have? And a **prior**: before seeing the data, which values of those unknown
quantities were plausible? That is the whole model. Everything else in this library is machinery
for answering one question about that pair.

The answer is the **posterior**: given the data, which values are plausible now? It is not a
number. It is a distribution over every unknown quantity at once, including how they relate — if
two parameters can trade off against each other, the posterior knows, and no single estimate of
either can tell you.

That is the first thing to get used to. A fit here does not return "the answer, plus or minus". It
returns several thousand *samples from the posterior* — complete sets of parameter values, each one
a coherent possibility — and every number you eventually report is a summary of those samples,
computed at the very end. [Chapter 2](02-writing-a-model.md) writes a model; chapter 0 fits one in
a page.

## Why samples, and what a sampler actually does

For all but a handful of textbook models, the posterior has no formula. What can always be computed
is how *relatively* plausible one set of values is against another. A Markov chain Monte Carlo
sampler turns that into samples: it walks around the space of possible values, preferring plausible
regions, and the places it visits — in the long run, in the right proportions — are samples from
the posterior.

Three consequences you will meet constantly.

**The walk has to settle first.** It starts somewhere arbitrary, and the first few hundred steps
tell you about where it started rather than about the posterior. Those are **warmup** and are
thrown away. During warmup the sampler also tunes itself: how big a step to take, and how the
coordinates are scaled relative to each other.

**Consecutive samples are not independent.** The walk moves, so sample 101 resembles sample 100. A
thousand correlated samples carry less information than a thousand independent ones, and the
**effective number of samples** is how many they are worth. It is the number that decides whether
your third decimal place is real.

**Several independent walks are better than one long one.** If four chains started in four
different places and ended up describing the same distribution, that is evidence they found it. If
they disagree, nothing else in the output is worth reading. **Rhat** is that comparison as a number,
and 1.00 is what you want. [Chapter 3](03-sampling.md) is the sampler; [chapter 4](04-diagnosing.md)
is how to tell whether it worked.

The sampler here is the no-U-turn sampler, which uses the *slope* of the plausibility — its
gradient — to propose long, well-aimed moves rather than a random stagger. That is why this library
has automatic differentiation in it at all: you write a density, and the gradient is computed from
your own code rather than derived by you. [Chapter 1](01-concepts.md) is about that machinery.

**A divergent transition** is the one failure worth memorising. It means the sampler tried to
follow a trajectory and the arithmetic fell apart — almost always because the posterior has a
region shaped like a funnel, narrowing to a point. More samples do not fix it. Rewriting the model
so the funnel disappears does, and that rewrite is standard enough to have a name
(**non-centred**): instead of asking for a group's value directly, you ask for how many standard
deviations from the population mean it is, and multiply back. The posterior is the same; the
geometry the sampler has to walk is not.

## Priors are part of the model, not a formality

A prior says what was plausible before the data. People new to this often want one that "says
nothing", and there is no such thing: a prior flat over an enormous range says that enormous values
are as likely as sensible ones, which is a strong and usually false claim.

The practical test is to **simulate from the prior alone** and look at the data it implies. If your
priors imply a lending library receiving ten million applications a month, they are not neutral —
they are wrong, and the fit will be slower and less stable for it. That check costs nothing and
needs no data. [Chapter 5](05-checking-a-model.md) does it first for that reason.

The flip side: when the data has little to say about a parameter, the prior will decide it, and
that is not a bug — it is the correct answer to a question the data cannot answer. What matters is
knowing which case you are in, and **power scaling** is how: nudge the prior's strength up and
down, see whether the posterior moves. [Chapter 4](04-diagnosing.md) measures it.

## Checking a model, three ways that fail differently

Converged is not correct. Three checks catch three different kinds of wrong, and the order is by
cost.

**The prior predictive simulation** is above: before any fitting, does the model imagine plausible
data?

**The posterior predictive check** comes after the fit: generate fake data from the fitted model
and compare it with the real data. If the model cannot produce anything resembling what actually
happened, it is the wrong model, however healthy the diagnostics. Read it in both directions —
nothing outside the band is as suspicious as half of it outside, because a band nothing escapes is
too wide to say anything.

**Simulation-based calibration** compares the model with *itself*. Draw parameters from the prior,
simulate data from them, fit the model to that data, and ask where the drawn value sits among the
samples. Over many repetitions those ranks must be uniform. Nothing else tests the prior as a
density *and* as a simulator, the transforms, and the sampler, all at once — and all three can be
broken while every other diagnostic looks fine. [Chapter 5](05-checking-a-model.md).

**Cross-validation** asks a fourth question: how well would the model predict an observation it had
not seen? Refitting without each observation in turn is usually unaffordable, so it is estimated by
reweighting the samples you already have. That estimate is reliable only when the reweighting does
not have to work too hard, and there is a diagnostic — the **Pareto shape** — that says per
observation whether it did. [Chapter 4](04-diagnosing.md).

## Groups, and the middle ground between two bad answers

Data usually arrives in groups: cities, sites, clusters, schools. Two obvious things to do with
them are both wrong. Pool everything and you claim the groups are identical. Fit each group alone
and you claim they have nothing to do with each other — so a group with three observations gets an
estimate based on three observations, which will be nonsense.

A **multilevel model** does the third thing: each group has its own value, *and* those values are
themselves drawn from a distribution whose location and spread are also unknown. A group with
plenty of data speaks for itself; a thin group is pulled towards the others. The pull is called
**shrinkage**, it is not a correction applied afterwards — it falls out of the model — and how
strong it is, is itself estimated from the data.

The parameter that this hinges on is the **spread between groups**, and the number of groups is its
sample size. Four groups tell you very little about how much groups vary, which is why a multilevel
model with few groups is exactly where the prior matters most.
[Chapter 11](11-multilevel-models.md).

## Counts that have not finished arriving

This is the problem the `nowcast` part of the library exists for, and it is easy to state and easy
to get badly wrong.

Something happens in one month and is recorded some months later. Arrange events by the month they
happened and the delay before they were recorded, and you have a grid. The recent rows of that grid
are **incomplete**: not quiet, not zero — unknown. Events happened last month that nobody has heard
about yet.

Treat the missing cells as zeros and your model will report, confidently, that the last few months
collapsed. Every diagnostic will agree with it, because the model is answering the question you
actually asked. The fix is to tell the likelihood which cells are observed, and let it infer the
rest — which is what a **reporting delay** model does: it estimates both the underlying rate and
the distribution of delays, and the recent months come out with honestly wide intervals.

A **nowcast** is the estimate of what really happened in those recent months; a **forecast** is the
same machinery pointed at months that have not happened at all. The crude version of the nowcast —
divide what you have seen by the fraction you expect to have seen, the **exposure** — is worth
knowing because it shows what the model is doing, and worth distrusting at the newest month, where
dividing by a tiny fraction turns one early report into an enormous number.
[Chapter 7](07-nowcasting-step-by-step.md) builds the whole thing.

## Forecasts are distributions, and summarising them early ruins them

A forecast here is not a line. It is an **ensemble**: one complete simulated future per sample from
the posterior, each internally consistent — the same rate, the same delays, the same correlations
throughout. The interesting numbers are summaries of that ensemble, and the order matters more than
anything else in this part of the library.

Summarise first and you will **add percentiles**, which is the single most expensive mistake
available here. The 90th percentile of a total is not the sum of the parts' 90th percentiles: the
latter asks "what if every part were simultaneously at its own bad case?", which no actual future
satisfies. Add the *samples*, summarise at the end. [Chapter 6](06-predicting.md) measures the gap.

## Scoring: a forecast nobody scored is an opinion

Once the months close you can ask how good the forecast was, and "how close was the middle" is the
wrong question — a forecast is a distribution, so the score has to read the whole of it.

The **continuous ranked probability score** does: it is the distance between the predicted
distribution and what happened, in the units of the thing being forecast, and it is **proper**,
meaning no forecaster can improve it by reporting anything other than their honest belief. Lower is
better.

A good average score can still hide a systematic problem, so **calibration** is read separately: of
all the times you said "90 % chance it lands in this interval", did it land there nine times in
ten? Too often means your intervals are too wide, too rarely means too narrow, and consistently
high or low means shifted.

One warning that catches people. Scores on neighbouring dates are correlated — a model wrong in
March is usually wrong in April — so the obvious standard error on a mean score is far too small.
Resampling whole blocks of dates is the fix. [Chapter 8](08-scoring-a-forecast.md).

## What to read next

If all of that was familiar, start at [chapter 0](00-quick-start.md) and ignore this page.

If it was not, chapter 0 then [chapter 1](01-concepts.md) is the path: chapter 1 teaches the few
pieces of this library's own vocabulary that every example uses, and after it the code in the rest
of the manual reads without guessing.
