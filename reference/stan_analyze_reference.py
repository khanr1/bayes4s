"""Line-by-line NumPy port of Stan's diagnostics (stan-dev/stan, src/stan/analyze/mcmc, BSD-3-Clause).

Purpose: a readable executable specification. It reproduces every value asserted by Stan's own
tests on bern1-4.csv, which ArviZ 1.x does NOT (tail ESS, basic ESS, mcse_sd differ by 2-15 %).
Chains are passed as arrays of shape (draws, chains), the orientation Stan uses.
"""
import numpy as np
from scipy import stats

def autocovariance(y):
    """Biased autocovariance (divide by N), FFT with zero padding to a power of two >= 2N."""
    n = len(y); m = 1
    while m < 2 * n: m *= 2
    f = np.fft.fft(y - y.mean(), m)
    return np.real(np.fft.ifft(f * np.conj(f)))[:n] / n

def is_finite_and_varies(c):
    return np.all(np.isfinite(c)) and np.ptp(c, axis=0).min() > 0 if c.size else False

def ess(chains):
    n_draws, n_chains = chains.shape
    if n_draws < 4: return float("nan")
    acov = np.column_stack([autocovariance(chains[:, i]) for i in range(n_chains)])
    chain_mean = chains.mean(0)
    chain_var = acov[0] * n_draws / (n_draws - 1)
    w = chain_var.mean()
    var_plus = w * (n_draws - 1) / n_draws
    if n_chains > 1: var_plus += chain_mean.var(ddof=1)
    rho = np.zeros(n_draws)
    rho_even = 1.0; rho[0] = rho_even
    rho_odd = 1 - (w - acov[1].mean()) / var_plus; rho[1] = rho_odd
    t = 1
    while t < n_draws - 4 and rho_even + rho_odd > 0 and not np.isnan(rho_even + rho_odd):
        rho_even = 1 - (w - acov[t + 1].mean()) / var_plus
        rho_odd = 1 - (w - acov[t + 2].mean()) / var_plus
        if rho_even + rho_odd >= 0:
            rho[t + 1] = rho_even; rho[t + 2] = rho_odd
        if rho[t + 1] + rho[t + 2] > rho[t - 1] + rho[t]:      # initial monotone sequence
            rho[t + 1] = (rho[t - 1] + rho[t]) / 2; rho[t + 2] = rho[t + 1]
        t += 2
    max_t = t
    if rho_even > 0: rho[max_t + 1] = rho_even
    total = n_chains * n_draws
    tau = -1 + 2 * rho[:max_t].sum() + rho[max_t + 1]
    tau = max(tau, 1 / np.log10(total))
    return total / tau

def split_chains(c):
    n = c.shape[0]; half = n // 2; tail = (n + 1) // 2
    out = []
    for i in range(c.shape[1]):
        out += [c[:half, i], c[tail:tail + half, i]]
    return np.column_stack(out)

def rank_transform(c):
    """Average ranks for ties, then normal quantile of (rank - 3/8) / (S + 1/4). Note: Stan uses
    S + 1/4, which equals the Blom offset S - 2*(3/8) + 1."""
    r = stats.rankdata(c.ravel(order="F"), method="average").reshape(c.shape, order="F")
    return stats.norm.ppf((r - 0.375) / (c.size + 0.25))

def rhat(c):
    n, m = c.shape
    means = c.mean(0)
    b = n * means.var(ddof=1)
    w = c.var(0, ddof=1).mean()
    return np.sqrt((b / w + n - 1) / n)

def quantile(x, p):
    """stan::math::quantile = R type 7 = NumPy 'linear'."""
    return np.quantile(x, p)

def rhat_basic(c): return rhat(split_chains(c))

def split_rank_normalized_rhat(c):
    s = split_chains(c)
    if not is_finite_and_varies(s): return float("nan"), float("nan")
    bulk = rhat(rank_transform(s))
    tail = rhat(rank_transform(np.abs(s - quantile(s.ravel(), 0.5))))
    return bulk, tail

def split_rank_normalized_ess(c):
    s = split_chains(c)
    if not is_finite_and_varies(s) or s.shape[0] < 4: return float("nan"), float("nan")
    bulk = ess(rank_transform(s))
    e05 = ess((s <= quantile(s.ravel(), 0.05)).astype(float))
    e95 = ess((s >= quantile(s.ravel(), 0.95)).astype(float))   # note: >= for the upper tail
    tail = e95 if np.isnan(e05) else e05 if np.isnan(e95) else min(e05, e95)
    return bulk, tail

def mcse_mean(c):
    """Uses ess() on the UNSPLIT chains."""
    if c.shape[0] < 4 or not is_finite_and_varies(c): return float("nan")
    return np.sqrt(((c - c.mean()) ** 2).sum() / (c.size - 1) / ess(c))

def mcse_sd(c):
    """ESS of |x - mean| on split chains, then a Taylor approximation (posterior >= 1.5)."""
    if c.shape[0] < 8 or not is_finite_and_varies(c): return float("nan")
    d = np.abs(c - c.mean())
    e = ess(split_chains(d))
    ev = (d ** 2).mean(); m4 = (d ** 4).mean()
    return np.sqrt((m4 - ev ** 2) / e / ev / 4)
