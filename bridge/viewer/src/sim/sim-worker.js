// Material-removal simulation worker (module worker). Messages:
//   → {type:'init', nx, ny, cs, top, seg, rad, prof, profs}   ← {type:'progress', frac} … {type:'ready', final, ms}
//   → {type:'goto', i, f, id}                                ← {type:'state', id, i, f, rect|null, ms}
// The simulation itself is in heightfield.js (pure, tested in Node).
import { Heightfield } from './heightfield.js';

let hf = null;

self.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'init') {
    const t0 = performance.now();
    hf = new Heightfield(m);
    const final = hf.build((frac) => postMessage({ type: 'progress', frac }));
    postMessage({ type: 'ready', final, ms: performance.now() - t0, snapshots: hf.snaps.length }, [final.buffer]);
  } else if (m.type === 'goto' && hf) {
    const t0 = performance.now();
    hf.goto(m.i, m.f);
    const rect = hf.takeDirty();
    postMessage({ type: 'state', id: m.id, i: hf.ci, f: hf.cf, rect, ms: performance.now() - t0 }, rect ? [rect.data.buffer] : []);
  }
};
