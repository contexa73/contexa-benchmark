"""The few exact statistics the benchmark report uses, with no library beyond the standard one.

clopper_pearson   exact binomial interval of a share (two-sided for `level`; its upper end is one-sided at (1 + level) / 2)
mcnemar_exact     exact two-sided p-value of a paired comparison of two yes/no results on the same cases
"""
from __future__ import annotations

import math


def _cdf(x: int, n: int, p: float) -> float:
    return sum(math.comb(n, i) * p ** i * (1 - p) ** (n - i) for i in range(0, x + 1))


def _solve(target: float, x: int, n: int) -> float:
    """The share p at which P(X <= x) falls to `target`; P(X <= x) decreases as p grows."""
    lo, hi = 0.0, 1.0
    for _ in range(80):
        mid = (lo + hi) / 2
        if _cdf(x, n, mid) > target:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def clopper_pearson(k: int, n: int, level: float = 0.95) -> tuple[float, float]:
    """Exact interval of k successes in n. `level` 0.95 is the usual two-sided 95%; 0.90 gives a one-sided 95% upper end."""
    alpha = 1 - level
    lower = 0.0 if k == 0 else _solve(1 - alpha / 2, k - 1, n)
    upper = 1.0 if k == n else _solve(alpha / 2, k, n)
    return lower, upper


def mcnemar_exact(only_first: int, only_second: int) -> float:
    """Two-sided exact p-value from the discordant pairs: cases only the first run solved, and only the second run solved."""
    n = only_first + only_second
    if n == 0:
        return 1.0
    low = min(only_first, only_second)
    return min(1.0, 2 * _cdf(low, n, 0.5))
