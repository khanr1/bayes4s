"""Reference values for the statistics the plots need.

Three of them have competing definitions under the same name, which is the whole reason this file
exists: `silverman` and `scott` mean different formulas in SciPy, in R and in ArviZ, and a plot
drawn with the wrong one looks plausible and is wrong. ArviZ's are the reference here, taken from
`arviz_stats.base.array_stats`, because the catalogue is modelled on ArviZ's and bayesplot's.

The kernel density values themselves are computed **exactly** in numpy — a sum of Gaussians over
the sample, with no binning and no FFT — because that has only one definition and because the
Scala side is exact too. ArviZ's own `kde` bins and would disagree in the fourth digit for a
reason that has nothing to do with the formula.

Usage: python -I build_plot_fixtures.py <out dir>
"""
import json, sys, pathlib
import numpy as np
from scipy import stats
from arviz_stats.base import array_stats

OUT = pathlib.Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(20261107)

# ---- the samples the fixture is built on: invented, with three shapes ----
samples = {
    "gaussian": rng.normal(0.0, 1.0, size=200),
    "skewed": rng.gamma(2.0, 1.5, size=200),
    "bimodal": np.concatenate([rng.normal(-2.0, 0.5, 80), rng.normal(2.0, 0.8, 120)]),
}


def exact_kde(x, bandwidth, grid, reflect_at=None):
    """A sum of Gaussians, with optional reflection at a lower bound."""
    points = np.asarray(x)[:, None]
    g = np.asarray(grid)[None, :]
    density = np.exp(-0.5 * ((g - points) / bandwidth) ** 2).sum(axis=0)
    if reflect_at is not None:
        mirrored = 2.0 * reflect_at - points
        density = density + np.exp(-0.5 * ((g - mirrored) / bandwidth) ** 2).sum(axis=0)
    return density / (len(x) * bandwidth * np.sqrt(2.0 * np.pi))


bandwidths = {}
densities = {}
for name, x in samples.items():
    bandwidths[name] = {
        "silverman": float(array_stats.bw_silverman(x)),
        "scott": float(array_stats.bw_scott(x)),
        "isj": float(array_stats.bw_isj(x)),
    }
    grid = np.linspace(x.min() - 1.0, x.max() + 1.0, 41)
    densities[name] = {
        "grid": grid.tolist(),
        "bandwidth": bandwidths[name]["silverman"],
        "density": exact_kde(x, bandwidths[name]["silverman"], grid).tolist(),
    }

# A bounded sample, reflected at zero: the case a scale parameter needs.
positive = samples["skewed"]
bounded_grid = np.linspace(0.0, positive.max() + 1.0, 31)
bounded_bw = float(array_stats.bw_silverman(positive))
reflected = {
    "grid": bounded_grid.tolist(),
    "bandwidth": bounded_bw,
    "lower": 0.0,
    "density": exact_kde(positive, bounded_bw, bounded_grid, reflect_at=0.0).tolist(),
    # Without the reflection the mass near the bound leaks out and the density is too low there.
    "unreflected": exact_kde(positive, bounded_bw, bounded_grid).tolist(),
}

# ---- ranks, pooled over chains, as ArviZ computes them ----
rank_chains = np.array([
    [3.0, 1.0, 4.0, 1.5, 5.0],
    [9.0, 2.0, 6.0, 5.0, 3.5],
    [5.0, 8.0, 9.7, 9.3, 2.3],
])
ranks = {
    "chains": rank_chains.tolist(),
    # `compute_ranks` works on the flattened sample and returns average ranks for ties.
    "pooled": array_stats.compute_ranks(rank_chains.ravel()).reshape(rank_chains.shape).tolist(),
}

# ---- the empirical cumulative function and its pointwise band ----
ecdf_sample = samples["gaussian"][:50]
ecdf_points = np.linspace(-2.5, 2.5, 21)
sorted_sample = np.sort(ecdf_sample)
ecdf_values = np.searchsorted(sorted_sample, ecdf_points, side="right") / len(ecdf_sample)
n = len(ecdf_sample)
# The pointwise band at 0.95: exact binomial quantiles of the count below each point, under the
# hypothesis that the sample really comes from the reference distribution.
reference_cdf = stats.norm.cdf(ecdf_points)
lower = stats.binom.ppf(0.025, n, reference_cdf) / n
upper = stats.binom.ppf(0.975, n, reference_cdf) / n
ecdf = {
    "sample": ecdf_sample.tolist(),
    "points": ecdf_points.tolist(),
    "values": ecdf_values.tolist(),
    "referenceCdf": reference_cdf.tolist(),
    "pointwiseLower": lower.tolist(),
    "pointwiseUpper": upper.tolist(),
    "mass": 0.95,
}

out = {
    "source": f"arviz_stats {__import__('arviz_stats').__version__}, numpy {np.__version__}, "
              f"scipy {__import__('scipy').__version__}",
    "notes": "Bandwidths are ArviZ's formulas; the density values are an exact sum of Gaussians, "
             "not ArviZ's binned kde. The pointwise ECDF band is the exact binomial quantile, not "
             "a normal approximation. The simultaneous band has no fixture: it is checked by its "
             "coverage on fresh uniform samples.",
    "samples": {name: x.tolist() for name, x in samples.items()},
    "bandwidth": bandwidths,
    "density": densities,
    "densityReflected": reflected,
    "ranks": ranks,
    "ecdf": ecdf,
}
(OUT / "plot_statistics.json").write_text(json.dumps(out, indent=1))
print(f"written to {OUT / 'plot_statistics.json'}")
print("bandwidths:", json.dumps(bandwidths, indent=1)[:300])
