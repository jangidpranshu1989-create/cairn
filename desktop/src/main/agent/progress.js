// Keeps the agent from going round in circles (same logic as Android's Progress.kt): a task journal that is always
// sent with the system prompt, no exact repeats of navigation, never reopening out-of-stock pages, and escalation
// hint → firm plan → forced wrap-up when nothing new happens, so every task ends with an answer.
const NAV = new Set(['open_url', 'add_to_cart', 'web_search', 'compare_prices', 'read_article']);
const PAGEY = new Set(['read_page', 'wait', 'scroll', 'summarize_page', 'screenshot']);
const TRACKING = /^(utm_.*|ref|ref_|tag|lid|marketplace|store|srno|otracker1?|fm|iid|ppt|ppn|ssid|spm|gclid|fbclid|affid|affExtParam\d*|qH|_refId|_appId|cid|sr|crid|sprefix|psc|th|keywords|dib|dib_tag|pd_rd_.*|pf_rd_.*|content-id|smid|hv.*)$/i;
const OOS = /OUT OF STOCK/;

function normUrl(raw) {
  let u = String(raw || '').trim().split('#')[0];
  if (!u) return '';
  u = u.replace(/^https?:\/\/(www\.|m\.)?/i, '');
  const [path, query = ''] = u.split('?');
  const q = query.split('&').filter(p => p && !TRACKING.test(p.split('=')[0])).sort();
  return path.replace(/\/+$/, '').toLowerCase() + (q.length ? '?' + q.join('&') : '');
}
const short = (s, n) => { s = String(s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };

class Progress {
  constructor(maxJournal = 16) { this.max = maxJournal; this.journal = []; this.calls = new Map(); this.urls = new Map(); this.oos = new Set(); this.steps = 0; this.lastFresh = 0; this.strikes = 0; this.skipped = 0; }
  key(name, args, url) {
    if (name === 'open_url') return 'open_url ' + normUrl(args?.url);
    const a = JSON.stringify(args || {}).toLowerCase();
    return PAGEY.has(name) ? `${name} ${a} @${normUrl(url)}` : `${name} ${a}`;
  }
  seen(name, args, url) { return this.calls.get(this.key(name, args, url)) || 0; }
  shouldSkip(name, args, url) {
    const n = this.seen(name, args, url);
    const oos = name === 'open_url' && this.oos.has(normUrl(args?.url));
    const skip = (NAV.has(name) && n >= 2) || (oos && n >= 1) || (!NAV.has(name) && n >= 3);
    if (skip) this.skipped++;
    return skip;
  }
  skipMessage(name, args, url) {
    const oos = name === 'open_url' && this.oos.has(normUrl(args?.url));
    return `SKIPPED: you already did ${name === 'open_url' ? 'this page' : 'this exact ' + name} ${this.seen(name, args, url)} time(s)${oos ? ' and it was OUT OF STOCK' : ''}. Repeating it won't change anything. Use TASK PROGRESS and pick a DIFFERENT action: another variant/colour on the page, the next candidate product, another store, compare_prices, or finish with the best option found.`;
  }
  record(name, args, pageUrl, title, result) {
    this.steps++;
    result = String(result || '');
    const k = this.key(name, args, pageUrl); const first = !this.calls.get(k);
    this.calls.set(k, (this.calls.get(k) || 0) + 1);
    const u = normUrl(pageUrl); const newUrl = u && !this.urls.get(u);
    if (u) this.urls.set(u, (this.urls.get(u) || 0) + 1);
    const oos = OOS.test(result.slice(0, 6000)); if (oos && u) this.oos.add(u);
    const failed = /^(ERROR|SKIPPED|NOT DONE)/.test(result);
    if (!failed && (newUrl || (first && !['read_page', 'wait', 'scroll'].includes(name)) || ['add_to_cart', 'go_to_checkout', 'compare_prices', 'request_approval', 'ask_user'].includes(name))) this.lastFresh = this.steps;
    const page = title ? `"${short(title, 60)}"` : u.slice(0, 80);
    const price = (result.match(/(?:₹|Rs\.?\s?|\$|£|€)\s?[\d,]{2,9}/) || [])[0];
    let line;
    if (['read_page', 'wait', 'scroll'].includes(name)) line = `${name} on ${page}${oos ? ' → OUT OF STOCK' : ''}`;
    else if (name === 'open_url') line = `opened ${u.slice(0, 80)} → ${page}${price ? ' · ' + price : ''}${failed ? ' · ' + short(result.split('\n')[0], 90) : oos ? ' · OUT OF STOCK' : ''}`;
    else line = `${name}(${short(Object.values(args || {}).join(', '), 60)}) → ${failed ? short(result.split('\n')[0], 90) : oos ? 'OUT OF STOCK' : name === 'compare_prices' ? short(result.split('\n\n')[0], 160) : 'ok'}`;
    if (this.journal[this.journal.length - 1] !== line) this.journal.push(line);
    while (this.journal.length > this.max) this.journal.shift();
  }
  verdict(elapsed, budget, maxSteps) {
    const idle = this.steps - this.lastFresh;
    const want = elapsed >= budget || this.steps >= maxSteps - 1 || idle >= 9 || this.skipped >= 4 ? 3
      : idle >= 6 || this.skipped >= 2 || elapsed >= budget * 0.75 ? 2 : idle >= 4 || this.skipped >= 1 ? 1 : 0;
    if (want <= this.strikes) return 'ok';
    this.strikes = want;
    return ['ok', 'nudge', 'firm', 'wrap'][want];
  }
  relax() { if (this.steps === this.lastFresh && this.strikes >= 1 && this.strikes <= 2 && !this.skipped) this.strikes--; }
  text() {
    if (!this.journal.length) return '';
    return 'TASK PROGRESS (what you already did this task; trust it, don\'t repeat it):\n' + this.journal.map(l => '- ' + l).join('\n') +
      (this.oos.size ? '\nOUT OF STOCK (never reopen): ' + [...this.oos].map(u => u.slice(0, 90)).join(', ') : '');
  }
}

module.exports = { Progress, normUrl };
