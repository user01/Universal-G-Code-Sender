# Vendored libraries

No package manager or bundler at runtime: these files are served as-is and mapped by the import
map in `../index.html`.

| Library | Version | License | Source | Files |
|---|---|---|---|---|
| three.js | **0.170.0** | MIT (`three-0.170.0/LICENSE`) | npm `three@0.170.0`, tarball sha256 `4a608a355dcaba72e0e5383cdc814303f5b6060b43c238cdf6932dceb699238d` | `build/three.module.min.js`, `examples/jsm/controls/OrbitControls.js` → `addons/controls/`, `examples/jsm/exporters/GLTFExporter.js` → `addons/exporters/` |

Upgrading: `npm pack three@<version>`, copy the same files into `three-<version>/`, update the
import map in `index.html` and this table.
