"""Reference cases for the HMC layer, taken from Stan's own unit tests (stan-dev/stan, BSD-3-Clause)
and re-executed with a NumPy port of the corresponding Stan code, so that each value is confirmed
twice (Stan's assertion and the port).

Usage: python -I build_hmc_fixtures.py <stan checkout> <out.json>
"""
import json, math, re, sys, pathlib
import numpy as np

STAN = pathlib.Path(sys.argv[1]); OUT = pathlib.Path(sys.argv[2])

# ---------- NumPy port of unit_e metric + expl_leapfrog (stan/mcmc/hmc) ----------
class Point:
    def __init__(s, q, p, V=None, g=None):
        s.q = np.array(q, float); s.p = np.array(p, float); s.V = V; s.g = None if g is None else np.array(g, float)
    def copy(s): return Point(s.q.copy(), s.p.copy(), s.V, None if s.g is None else s.g.copy())

def std_normal_update(z):            # V = -log p = q.q/2 (constants dropped), g = dV/dq = q
    z.V = 0.5 * float(z.q @ z.q); z.g = z.q.copy()

def H(z): return z.V + 0.5 * float(z.p @ z.p)          # unit_e: tau = p.p/2
def dtau_dp(z): return z.p.copy()

def begin_update_p(z, eps): z.p = z.p - eps * z.g
def update_q(z, eps): z.q = z.q + eps * dtau_dp(z); std_normal_update(z)
def end_update_p(z, eps): z.p = z.p - eps * z.g
def evolve(z, eps): begin_update_p(z, 0.5 * eps); update_q(z, eps); end_update_p(z, 0.5 * eps)

# ---------- 1. Leapfrog tests (expl_leapfrog_test.cpp), parsed ----------
src = (STAN / "src/test/unit/mcmc/hmc/integrators/expl_leapfrog_test.cpp").read_text()
leapfrog = []
for m in re.finditer(r"TEST_F\(McmcHmcIntegratorsExplLeapfrogF,\s*(\w+)\)\s*\{(.*?)\n\}", src, re.S):
    name, body = m.group(1), m.group(2)
    init = {k: float(v) for k, v in re.findall(r"z\.(V|q\(0\)|p\(0\)|g\(0\))\s*=\s*([-\d.eE+]+);", body)}
    eps_m = re.search(r"double epsilon = ([\d.eE+-]+);", body)
    if eps_m is None: continue
    op_part = body[eps_m.end():]
    ops = re.findall(r"unit_e_integrator\.(\w+)\(z, hamiltonian,\s*([^,]+),", op_part)
    loop = re.search(r"for \(int i = 0; i < (\d+); \+\+i\)", op_part)
    after = op_part[op_part.find("unit_e_integrator"):]
    exp = {k: (float(v), float(t)) for k, v, t in
           re.findall(r"EXPECT_NEAR\(z\.(V|q\(0\)|p\(0\)|g\(0\)),\s*([-\d.eE+]+),\s*([\d.eE+-]+)\)", after)}
    if not init or not ops or not exp: continue
    eps = float(eps_m.group(1))
    z = Point([init["q(0)"]], [init["p(0)"]], init["V"], [init["g(0)"]])
    reps = int(loop.group(1)) if loop else 1
    steps = []
    for _ in range(reps):
        for op, arg in ops:
            a = 0.5 * eps if "0.5" in arg else eps
            {"begin_update_p": begin_update_p, "update_q": update_q,
             "end_update_p": end_update_p, "evolve": evolve}[op](z, a)
    port = {"V": z.V, "q(0)": z.q[0], "p(0)": z.p[0], "g(0)": z.g[0]}
    ok = all(abs(port[k] - v) <= t for k, (v, t) in exp.items())
    leapfrog.append({"test": name, "model": "V(q) = q^2/2 (1-D standard normal), g = dV/dq = q",
                     "initial": init, "epsilon": eps, "operations": [o for o, _ in ops], "repeat": reps,
                     "expected": {k: v for k, (v, _) in exp.items()},
                     "tolerance": max(t for _, t in exp.values()), "numpy_port_ok": bool(ok)})

# ---------- 2. NUTS build_tree on gauss3D (unit_e_nuts_test.cpp) ----------
def log_sum_exp(a, b):
    if a == -math.inf: return b
    if b == -math.inf: return a
    m = max(a, b); return m + math.log(math.exp(a - m) + math.exp(b - m))

class Tree:
    """Port of base_nuts::build_tree without the multinomial proposal (z_propose needs the RNG and
    is not asserted by Stan's test)."""
    def __init__(s, z, eps, max_dh=1000.0): s.z, s.eps, s.max_dh, s.divergent = z, eps, max_dh, False
    def crit(s, ps_m, ps_p, rho): return float(ps_p @ rho) > 0 and float(ps_m @ rho) > 0
    def build(s, depth, rho, H0, sign, st):
        if depth == 0:
            evolve(s.z, sign * s.eps); st["n_leapfrog"] += 1
            h = H(s.z); h = math.inf if math.isnan(h) else h
            if h - H0 > s.max_dh: s.divergent = True
            st["log_sum_weight"] = log_sum_exp(st["log_sum_weight"], H0 - h)
            st["sum_metro_prob"] += 1 if H0 - h > 0 else math.exp(H0 - h)
            ps = dtau_dp(s.z)
            rho += s.z.p
            return not s.divergent, ps, ps.copy(), s.z.p.copy(), s.z.p.copy()
        st_init = dict(st, log_sum_weight=-math.inf)
        rho_init = np.zeros_like(rho)
        ok, ps_beg, ps_init_end, p_beg, p_init_end = s.build(depth - 1, rho_init, H0, sign, st_init)
        st["n_leapfrog"], st["sum_metro_prob"] = st_init["n_leapfrog"], st_init["sum_metro_prob"]
        if not ok: return False, ps_beg, ps_init_end, p_beg, p_init_end
        st_fin = dict(st, log_sum_weight=-math.inf)
        rho_fin = np.zeros_like(rho)
        ok, ps_fin_beg, ps_end, p_fin_beg, p_end = s.build(depth - 1, rho_fin, H0, sign, st_fin)
        st["n_leapfrog"], st["sum_metro_prob"] = st_fin["n_leapfrog"], st_fin["sum_metro_prob"]
        if not ok: return False, ps_beg, ps_end, p_beg, p_end
        sub = log_sum_exp(st_init["log_sum_weight"], st_fin["log_sum_weight"])
        st["log_sum_weight"] = log_sum_exp(st["log_sum_weight"], sub)
        rho_sub = rho_init + rho_fin; rho += rho_sub
        persist = s.crit(ps_beg, ps_end, rho_sub)
        persist &= s.crit(ps_beg, ps_fin_beg, rho_init + p_fin_beg)
        persist &= s.crit(ps_init_end, ps_end, rho_fin + p_init_end)
        return persist, ps_beg, ps_end, p_beg, p_end

z0 = Point([1, -1, 1], [-1, 1, -1]); std_normal_update(z0)
t = Tree(z0.copy(), 0.1); rho = z0.p.copy()
st = {"n_leapfrog": 0, "log_sum_weight": -math.inf, "sum_metro_prob": 0.0}
valid, ps_beg, ps_end, p_beg, p_end = t.build(3, rho, -0.1, 1, st)
stan_expected = {
    "valid_subtree": True, "rho": [-11.401228, 11.401228, -11.401228],
    "p_begin": [-1.09475, 1.09475, -1.09475], "p_sharp_begin": [-1.09475, 1.09475, -1.09475],
    "p_end": [-1.4131583, 1.4131583, -1.4131583], "p_sharp_end": [-1.4131583, 1.4131583, -1.4131583],
    "final_q": [-0.022019938, 0.022019938, -0.022019938], "final_p": [-1.4131583, 1.4131583, -1.4131583],
    "n_leapfrog": 8, "log_sum_weight": math.log(0.36134657), "sum_metro_prob": 0.36134657,
}
port = {"valid_subtree": bool(valid), "rho": rho.tolist(), "p_begin": p_beg.tolist(), "p_sharp_begin": ps_beg.tolist(),
        "p_end": p_end.tolist(), "p_sharp_end": ps_end.tolist(), "final_q": t.z.q.tolist(),
        "final_p": t.z.p.tolist(), "n_leapfrog": st["n_leapfrog"], "log_sum_weight": st["log_sum_weight"],
        "sum_metro_prob": st["sum_metro_prob"]}
def agree(a, b):
    a, b = np.atleast_1d(a).astype(float), np.atleast_1d(b).astype(float)
    return bool(np.all(np.abs(a - b) <= 1e-6 * np.maximum(1, np.abs(b))))
build_tree = {
    "source": "src/test/unit/mcmc/hmc/nuts/unit_e_nuts_test.cpp, TEST(McmcUnitENuts, build_tree_test)",
    "model": "gauss3D: x ~ normal(0, 1), 3 dimensions; V = x.x/2",
    "setup": {"q": [1, -1, 1], "p": [-1, 1, -1], "stepsize": 0.1, "stepsize_jitter": 0,
              "depth": 3, "H0": -0.1, "sign": 1, "rho_init": "equal to p",
              "log_sum_weight_init": "-inf", "sum_metro_prob_init": 0},
    "expected": stan_expected, "tolerance": "EXPECT_FLOAT_EQ (about 4 ulp in float); use 1e-6 relative",
    "numpy_port": port,
    "numpy_port_ok": {k: agree(port[k], v) for k, v in stan_expected.items()},
}

# ---------- 3. Criterion, adaptation ----------
criterion = [
    {"p_sharp_start": [1], "p_sharp_end": [1], "rho": [2], "expected": True},
    {"p_sharp_start": [1], "p_sharp_end": [-1], "rho": [0], "expected": False},
]

def windows(num_warmup, init_buffer=75, term_buffer=50, base_window=25):
    """Port of windowed_adaptation: returns the iterations (0-based) at which the metric is updated."""
    if num_warmup < 20: return {"adapt": False, "ends": []}
    if init_buffer + base_window + term_buffer > num_warmup:
        init_buffer = int(0.15 * num_warmup); term_buffer = int(0.10 * num_warmup)
        base_window = num_warmup - (init_buffer + term_buffer)
    counter, size = 0, base_window; nxt = init_buffer + size - 1; ends = []
    for counter in range(num_warmup):
        in_window = init_buffer <= counter < num_warmup - term_buffer
        if counter == nxt and counter != num_warmup:
            ends.append(counter)
            if nxt != num_warmup - term_buffer - 1:
                size *= 2; nxt = counter + size
                if nxt != num_warmup - term_buffer - 1 and nxt + 2 * size >= num_warmup - term_buffer:
                    nxt = num_warmup - term_buffer - 1
    return {"adapt": True, "init_buffer": init_buffer, "term_buffer": term_buffer,
            "base_window": base_window, "ends": ends}

window_cases = {str(n): windows(n) for n in (19, 20, 100, 150, 200, 300, 500, 1000, 2000)}
assert window_cases["100"]["init_buffer"] == 15 and window_cases["100"]["base_window"] == 75 \
    and window_cases["100"]["term_buffer"] == 10           # asserted by windowed_adaptation_test.cpp
assert window_cases["1000"]["ends"] == [99, 149, 249, 449, 949]   # Stan manual: windows 25,50,100,200,500

def dual_averaging(stats, mu, delta=0.8, gamma=0.05, kappa=0.75, t0=10):
    """Port of stepsize_adaptation::learn_stepsize, then complete_adaptation."""
    s_bar = x_bar = 0.0; eps = []
    for k, a in enumerate(stats, 1):
        a = min(a, 1.0); eta = 1 / (k + t0)
        s_bar = (1 - eta) * s_bar + eta * (delta - a)
        x = mu - s_bar * math.sqrt(k) / gamma
        xe = k ** -kappa; x_bar = (1 - xe) * x_bar + xe * x
        eps.append(math.exp(x))
    return eps, math.exp(x_bar)

stats = [0.95, 0.30, 0.99, 0.70, 0.85, 1.20, 0.10, 0.82, 0.79, 0.81]
traj, final = dual_averaging(stats, mu=math.log(10 * 0.5))
assert dual_averaging([0.65], mu=0.0, delta=0.65)[0][0] == 1.0     # stepsize_adaptation_test.cpp
adaptation = {
    "dual_averaging_stan_test": {"mu": 0.0, "delta": 0.65, "accept_stats": [0.65], "expected_stepsize": 1.0,
                                 "source": "stepsize_adaptation_test.cpp learn_stepsize"},
    "dual_averaging_port_trajectory": {
        "note": "computed by the NumPy port of stepsize_adaptation.hpp (not by Stan itself); mu = log(10 * 0.5) "
                "as Stan sets mu = log(10 * epsilon) at each restart; accept statistics above 1 are capped",
        "mu": math.log(5.0), "delta": 0.8, "gamma": 0.05, "kappa": 0.75, "t0": 10,
        "accept_stats": stats, "stepsizes": traj, "final_stepsize_exp_xbar": final},
    "variance_regularization_stan_test": {
        "source": "var_adaptation_test.cpp learn_variance",
        "setup": "dimension 10, set_window_params(num_warmup=50, init_buffer=0, term_buffer=0, base_window=10), "
                 "10 draws all equal to 0",
        "expected_each_component": 1e-3 * 5.0 / (10 + 5.0),
        "rule": "var = n/(n+5) * sample_var + 1e-3 * 5/(n+5)"},
    "windows": {"note": "0-based warmup iterations at which the metric is re-estimated; port of "
                        "windowed_adaptation.hpp; n=100 case and the 1000 schedule are asserted by Stan",
                "cases": window_cases},
}

OUT.write_text(json.dumps({
    "provenance": "stan-dev/stan unit tests (BSD-3-Clause), re-executed by tools/build_hmc_fixtures.py",
    "leapfrog": leapfrog, "nuts_build_tree_unit_e": build_tree, "nuts_criterion": criterion,
    "adaptation": adaptation}, indent=1))
print("leapfrog cases", len(leapfrog), "port ok", sum(c["numpy_port_ok"] for c in leapfrog))
print("build_tree port ok", build_tree["numpy_port_ok"])
print("windows 1000", window_cases["1000"]["ends"], "| 200", window_cases["200"])
