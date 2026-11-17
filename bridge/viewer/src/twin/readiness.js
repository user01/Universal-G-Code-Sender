// Twin readiness (D27): the plugin says when what this view shows is assumed, missing or out of sync
// with the machine (GET /twin/readiness, `event` records of type `readiness`). Pure, runs in Node.

/** Warnings after which the operator can confirm the stock placement from here. */
const CONFIRMABLE = new Set(['stock_not_confirmed', 'g54_changed_outside']);

/**
 * What the banner shows, or null when there is nothing to warn about.
 * @param {{state?:string, items?:{id:string, severity:string, message:string, fix:string}[]}|null} r
 * @returns {{title:string, items:{id:string, message:string, fix:string}[], confirm:boolean}|null}
 */
export function readinessView(r) {
  if (!r || !Array.isArray(r.items)) return null;
  const warns = r.items.filter((i) => i.severity === 'warn');
  if (!warns.length) return null;
  return {
    title: `Twin: ${warns.length} warning${warns.length === 1 ? '' : 's'}: what you see may not match the machine`,
    items: warns.map((i) => ({ id: i.id, message: i.message ?? '', fix: i.fix ?? '' })),
    confirm: warns.some((i) => CONFIRMABLE.has(i.id)),
  };
}

/** Banner HTML (escaped). The Confirm button carries data-action="confirm-stock". */
export function readinessHtml(view, esc, busy = false) {
  if (!view) return '';
  const items = view.items.map((i) => `<li><b>${esc(i.id)}</b>: ${esc(i.message)} <span class="fix">Fix: ${esc(i.fix)}</span></li>`).join('');
  const button = view.confirm
    ? `<button type="button" data-action="confirm-stock"${busy ? ' disabled' : ''}>Confirm stock is where shown</button>`
    : '';
  return `<div class="banner twin"><details><summary>${esc(view.title)}</summary><ul>${items}</ul></details>${button}</div>`;
}
