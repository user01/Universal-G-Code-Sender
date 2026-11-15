# Test data

`bridge/testdata/nc/` holds a local copy of the operator's real `.nc` files (from `~/Downloads`). It is **gitignored**: the files contain personal names and the fork is public. Tests that need them must skip cleanly when the folder is missing (`pytest.mark.skipif`, `assumeTrue` in JUnit, `node --test` skip).

Refresh: copy any representative files from `~/Downloads` into `bridge/testdata/nc/`. Notable files:

| File | Why |
|---|---|
| `dr_ryan_complete_T02_then_T21.nc` | 26 781 lines, 9 ops, T02 → T21 mid-file `M6`, full post headers |
| `step20_carve_06_Erik_Langenborg_v0.12.nc` | 21 ops, several tool changes |
| `chelsea_nameplate.nc` | different post (few headers): header-less fallback |
| `step29_gauge_*.nc`, `step40_only_one_lid_*.nc` | tiny files for fast tests |

Synthetic, committable fixtures go in `bridge/testdata-public/` (small, hand-written, no personal data).
