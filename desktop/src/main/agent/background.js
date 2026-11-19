// Invisible browser contexts for background work (price comparison, reading articles)
// They share the user's session partition, so logged-in sites and cookies behave normally.
const { WebContentsView } = require('electron');

const PARTITION = 'persist:saarthi';
let active = 0; const queue = [];
const MAX_PARALLEL = 4;

function slot() {
  if (active < MAX_PARALLEL) { active++; return Promise.resolve(); }
  return new Promise(r => queue.push(r));
}
function release() { active--; const n = queue.shift(); if (n) { active++; n(); } }

/**
 * withPage(url, fn, { timeout, settleMs, scrolls }) – loads url in a hidden view and runs fn(webContents).
 */
async function withPage(url, fn, { timeout = 30000, settleMs = 1800, scrolls = 2, signal } = {}) {
  await slot();
  const view = new WebContentsView({ webPreferences: { partition: PARTITION, sandbox: true, contextIsolation: true, backgroundThrottling: false, offscreen: false } });
  const wc = view.webContents;
  wc.setAudioMuted(true);
  wc.setWindowOpenHandler(() => ({ action: 'deny' }));
  view.setBounds({ x: 0, y: 0, width: 1366, height: 900 });
  const timer = new Promise((_, rej) => setTimeout(() => rej(new Error('timeout loading ' + new URL(url).hostname)), timeout));
  const abort = new Promise((_, rej) => signal?.addEventListener('abort', () => rej(new Error('cancelled')), { once: true }));
  try {
    return await Promise.race([timer, abort, (async () => {
      await wc.loadURL(url).catch(e => { if (!/ERR_ABORTED/.test(e.message)) throw e; });
      await new Promise(r => setTimeout(r, settleMs));
      for (let i = 0; i < scrolls; i++) {
        await wc.executeJavaScript('window.scrollBy(0, innerHeight*1.5)').catch(() => {});
        await new Promise(r => setTimeout(r, 600));
      }
      return fn(wc);
    })()]);
  } finally {
    try { wc.close(); } catch {}
    release();
  }
}

module.exports = { withPage, PARTITION };
