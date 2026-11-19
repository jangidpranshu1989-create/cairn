// Tool definitions (OpenAI function-calling schema) + implementations for the Cairn agent.
const dom = require('./dom');
const shopping = require('./shopping');
const pageTools = require('../page-tools');
const store = require('../store');
const cloud = require('../cloud');

const fn = (name, description, properties = {}, required = []) => ({ type: 'function', function: { name, description, parameters: { type: 'object', properties, required } } });
const S = (d) => ({ type: 'string', description: d });
const N = (d) => ({ type: 'number', description: d });
const B = (d) => ({ type: 'boolean', description: d });

const DEFS = [
  fn('web_search', 'Search the web and get top results (title, url, snippet). Use for facts, finding pages, reviews.', { query: S('search query') }, ['query']),
  fn('compare_prices', 'Search many trusted online stores in parallel (in the background) for a product and return listings ranked by price with trust scores. ALWAYS use this for "cheapest / best price / sasta" shopping requests.', { product: S('precise product, e.g. "iPhone 16 128GB"'), country: S('ISO country: IN, US, GB. Default from settings') }, ['product']),
  fn('open_url', 'Open a URL in the visible agent tab (user can watch).', { url: S('absolute URL'), new_tab: B('open in a new tab') }, ['url']),
  fn('read_page', 'Get the current page: url, title, numbered interactive elements and visible text. Call after every navigation before clicking.'),
  fn('click', 'Click an element by its [id] from read_page.', { id: N('element id') }, ['id']),
  fn('click_text', 'Click the visible button/link whose text best matches (e.g. "Add to Cart"). Use when you know the label.', { text: S('visible label') }, ['text']),
  fn('type', 'Type text into an input by [id]. Replaces existing text.', { id: N('element id'), text: S('text to type'), submit: B('press Enter after typing') }, ['id', 'text']),
  fn('select_option', 'Choose an option in a <select> by [id].', { id: N('element id'), option: S('option text or value') }, ['id', 'option']),
  fn('scroll', 'Scroll the page.', { direction: { type: 'string', enum: ['down', 'up', 'top', 'bottom'] } }, ['direction']),
  fn('press_key', 'Press a keyboard key on the page.', { key: { type: 'string', enum: ['Enter', 'Escape', 'Tab', 'ArrowDown', 'ArrowUp', 'Backspace'] } }, ['key']),
  fn('go_back', 'Go back in the agent tab history.'),
  fn('wait', 'Wait for the page to update.', { seconds: N('1-8') }, ['seconds']),
  fn('read_article', 'Read the main article text of a URL in the BACKGROUND without disturbing the user (or the current page if url omitted). Use for "read this Wikipedia article to me".', { url: S('article URL (optional)') }),
  fn('read_aloud', 'Speak text aloud to the user with text-to-speech. source="last_article" speaks the article fetched by read_article; source="text" speaks the given text (e.g. a summary).', { source: { type: 'string', enum: ['last_article', 'text'] }, text: S('text when source=text'), language: S('BCP-47 like en-IN, hi-IN') }, ['source']),
  fn('summarize_page', 'Summarize the current page and show it to the user.'),
  fn('make_mindmap', 'Create a visual mind map of the current page and open it.'),
  fn('save_pdf', 'Save as PDF. source="page" prints the current page; "last_article" makes a clean reader PDF of the last read_article; "summary" saves the last summary.', { source: { type: 'string', enum: ['page', 'last_article', 'summary'] } }, ['source']),
  fn('screenshot', 'Take a screenshot of the current page. full=true captures the entire scrolling page (long screenshot).', { full: B('full page') }),
  fn('download_file', 'Download a file from a URL to the Cairn downloads folder.', { url: S('file URL'), filename: S('optional name') }, ['url']),
  fn('send_to_phone', 'Send a file to the user\'s paired Android phone and save it in a folder there (e.g. "Documents/Wikipedia"). file="last" sends the most recent PDF/screenshot/download.', { file: S('"last" or absolute path'), folder: S('folder on phone') }, ['file']),
  fn('request_approval', 'REQUIRED before placing an order, paying, or any irreversible purchase. Sends an approval pop-up to the user\'s phone (biometric). Returns approved/denied.', { kind: { type: 'string', enum: ['payment', 'purchase', 'sensitive'] }, title: S('short, e.g. "Pay ₹69,900 to Flipkart"'), amount: S('e.g. ₹69,900'), merchant: S('store'), item: S('item name') }, ['kind', 'title']),
  fn('ask_user', 'Ask the user a question and wait for the answer (e.g. choose a variant, provide OTP, confirm address, or log in manually).', { question: S('question in the user\'s language') }, ['question']),
  fn('show_products', 'Display product comparison cards to the user. Pass indices from the last compare_prices results in the order to show (best first).', { indices: { type: 'array', items: { type: 'number' } }, headline: S('one-line headline') }, ['indices']),
];

const FINAL_ACTION = /place (your )?order|pay(ment)?\s*(now|securely|₹|rs\.?|\$|£|€|\d)|confirm (order|purchase|payment)|complete (purchase|order|payment)|proceed to pay|make payment|submit order|pay with|authorize payment/i;

async function settle(wc, ms = 3500) {
  const start = Date.now();
  await new Promise(r => setTimeout(r, 350));
  while (wc.isLoading() && Date.now() - start < ms) await new Promise(r => setTimeout(r, 150));
  await new Promise(r => setTimeout(r, 400));
}

async function mouseClick(ctx, wc, x, y, label) {
  await dom.overlay(wc, 'move', x, y, label);
  await new Promise(r => setTimeout(r, 420));
  await dom.overlay(wc, 'click', x, y, label);
  wc.sendInputEvent({ type: 'mouseMove', x, y });
  wc.sendInputEvent({ type: 'mouseDown', x, y, button: 'left', clickCount: 1 });
  wc.sendInputEvent({ type: 'mouseUp', x, y, button: 'left', clickCount: 1 });
  await settle(wc);
}

async function guardFinal(ctx, label) {
  if (!FINAL_ACTION.test(label || '')) return null;
  if (ctx.approved) { ctx.approved = false; return null; } // one approval == one final click
  return `BLOCKED: "${label}" looks like placing an order or paying. Call request_approval first (with amount, merchant, item), then retry this click only if approved.`;
}

async function webSearch(query) {
  const r = await fetch('https://html.duckduckgo.com/html/?q=' + encodeURIComponent(query), {
    headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36' } });
  const html = await r.text();
  const out = [];
  const re = /<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?<a[^>]+class="result__snippet"[^>]*>([\s\S]*?)<\/a>/g;
  let m;
  const strip = (s) => s.replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#x27;/g, "'").replace(/\s+/g, ' ').trim();
  while ((m = re.exec(html)) && out.length < 8) {
    let url = m[1];
    const u = url.match(/uddg=([^&]+)/); if (u) url = decodeURIComponent(u[1]);
    if (url.startsWith('//')) url = 'https:' + url;
    if (/duckduckgo\.com\/y\.js/.test(url)) continue; // ads
    out.push({ title: strip(m[2]), url, snippet: strip(m[3]).slice(0, 240) });
  }
  return out;
}

async function execute(name, args, ctx) {
  const wc = () => ctx.agentTab().webContents;
  switch (name) {
    case 'web_search': {
      ctx.step('search', `Searching the web: ${args.query}`);
      const res = await webSearch(args.query);
      ctx.sources(res);
      return res.length ? res.map((r, i) => `${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}`).join('\n') : 'No results.';
    }
    case 'compare_prices': {
      const country = (args.country || store.get().settings.data.shoppingCountry || 'IN').toUpperCase();
      ctx.step('shop', `Comparing prices for "${args.product}"`);
      const res = await shopping.compare(args.product, { country, signal: ctx.signal, onProgress: (p) => ctx.step('store', p.text) });
      ctx.lastProducts = res.results;
      if (!res.results.length) return `No listings found. Store status: ${JSON.stringify(res.stores)}. Try a simpler query or web_search.`;
      ctx.emit({ type: 'products', query: args.product, items: res.results.slice(0, 8), stores: res.stores });
      return `Store status: ${res.stores.map(s => `${s.name}=${s.error ? 'error' : s.count}`).join(', ')}\nResults sorted by price (index|store|title|variant|price|mrp|rating|reviews|trust|flags|url):\n` +
        res.results.map((r, i) => `${i}|${r.store}|${r.title.slice(0, 110)}|${r.variant || ''}|${r.price}|${r.mrp || ''}|${r.rating || ''}|${r.reviews || ''}|${r.trust}|${r.flags.join('; ')}|${r.url}`).join('\n') +
        '\nProduct cards are already shown to the user. Now give a short recommendation: best trustworthy pick with price + store + link, and 1-2 alternatives.';
    }
    case 'show_products': {
      const items = (args.indices || []).map(i => ctx.lastProducts?.[i]).filter(Boolean);
      if (items.length) ctx.emit({ type: 'products', headline: args.headline, items });
      return items.length ? 'Shown.' : 'No such indices.';
    }
    case 'open_url': {
      let url = String(args.url).trim();
      if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
      ctx.step('nav', `Opening ${new URL(url).hostname}`);
      const tab = args.new_tab ? ctx.newAgentTab(url) : ctx.agentTab();
      if (!args.new_tab) await tab.webContents.loadURL(url).catch(() => {});
      await settle(tab.webContents, 8000);
      await dom.overlay(tab.webContents, 'move', 40, 40, 'Cairn');
      return `Opened ${tab.webContents.getURL()} — "${tab.webContents.getTitle()}". Call read_page to see it.`;
    }
    case 'read_page': {
      const w = wc();
      ctx.step('read', `Reading ${safeHost(w.getURL())}`);
      const s = await dom.snapshot(w);
      const st = await dom.pageState(w).catch(() => ({}));
      const notes = [st.captcha && 'NOTE: a CAPTCHA/human check is shown — use ask_user to have the user solve it.', st.otp && 'NOTE: page asks for an OTP — use ask_user.', st.hasPassword && 'NOTE: login form present. Saved passwords may autofill; otherwise ask_user to log in.', st.payment && 'NOTE: this looks like a checkout/payment step. request_approval before placing order or paying.'].filter(Boolean).join('\n');
      return `URL: ${s.url}\nTitle: ${s.title}\nScroll: ${s.scroll.y}/${s.scroll.max}\n${notes}\nElements:\n${s.elements}\n\nVisible text:\n${s.text}`;
    }
    case 'click': {
      const w = wc();
      const loc = await dom.locate(w, args.id);
      if (!loc) return `Element ${args.id} not found — call read_page again.`;
      const blocked = await guardFinal(ctx, loc.text);
      if (blocked) return blocked;
      ctx.step('click', `Clicking "${loc.text.slice(0, 50) || loc.tag}"`);
      await mouseClick(ctx, w, loc.x, loc.y, loc.text.slice(0, 30));
      return `Clicked [${args.id}] "${loc.text.slice(0, 80)}". Now at ${w.getURL()}`;
    }
    case 'click_text': {
      const w = wc();
      const loc = await dom.findByText(w, args.text);
      if (!loc) return `No visible element with text "${args.text}". Use read_page.`;
      const blocked = await guardFinal(ctx, loc.text);
      if (blocked) return blocked;
      ctx.step('click', `Clicking "${loc.text.slice(0, 50)}"`);
      await mouseClick(ctx, w, loc.x, loc.y, loc.text.slice(0, 30));
      return `Clicked "${loc.text.slice(0, 80)}". Now at ${w.getURL()}`;
    }
    case 'type': {
      const w = wc();
      const loc = await dom.locate(w, args.id);
      if (!loc) return `Element ${args.id} not found.`;
      ctx.step('type', `Typing "${loc.type === 'password' ? '••••' : String(args.text).slice(0, 40)}"`);
      await mouseClick(ctx, w, loc.x, loc.y, 'typing');
      await dom.focus(w, args.id, true);
      w.insertText(String(args.text));
      if (args.submit) { await pressKey(w, 'Enter'); await settle(w, 8000); }
      return `Typed into [${args.id}]${args.submit ? ' and pressed Enter' : ''}.`;
    }
    case 'select_option': return dom.select(wc(), args.id, args.option);
    case 'scroll': { ctx.step('scroll', `Scrolling ${args.direction}`); const y = await dom.scroll(wc(), args.direction); await new Promise(r => setTimeout(r, 500)); return `Scrolled to y=${y}`; }
    case 'press_key': { await pressKey(wc(), args.key); await settle(wc()); return `Pressed ${args.key}`; }
    case 'go_back': { if (wc().navigationHistory.canGoBack()) wc().navigationHistory.goBack(); await settle(wc()); return `Back at ${wc().getURL()}`; }
    case 'wait': { await new Promise(r => setTimeout(r, Math.min(8, Math.max(1, args.seconds || 2)) * 1000)); return 'Waited.'; }
    case 'read_article': {
      ctx.step('read', args.url ? `Reading article in background: ${safeHost(args.url)}` : 'Reading this article');
      const a = args.url ? await pageTools.readUrl(args.url, ctx.signal) : await pageTools.getArticle(wc());
      ctx.lastArticle = a;
      return `Title: ${a.title}\nURL: ${a.url}\nLength: ${a.text.length} chars\nOpening:\n${a.text.slice(0, 2500)}`;
    }
    case 'read_aloud': {
      const text = args.source === 'last_article' ? (ctx.lastArticle ? `${ctx.lastArticle.title}.\n${ctx.lastArticle.text}` : null) : args.text;
      if (!text) return 'Nothing to read. Call read_article first.';
      ctx.step('speak', 'Reading aloud');
      ctx.emit({ type: 'speak', text: text.replace(/^##\s*/gm, ''), lang: args.language || ctx.lastArticle?.lang || '', title: ctx.lastArticle?.title });
      return 'Started reading aloud. The user can pause/stop from the player.';
    }
    case 'summarize_page': {
      ctx.step('sum', 'Summarizing the page');
      const s = await pageTools.summarize(wc(), { userText: ctx.userText });
      ctx.lastSummary = s;
      ctx.emit({ type: 'summary', ...s });
      return 'Summary shown to the user:\n' + s.markdown;
    }
    case 'make_mindmap': {
      ctx.step('map', 'Building a mind map');
      const m = await pageTools.mindmap(wc(), { userText: ctx.userText });
      ctx.openMindmap(m);
      return 'Mind map opened in a new tab.';
    }
    case 'save_pdf': {
      ctx.step('pdf', 'Creating PDF');
      let file;
      if (args.source === 'last_article' && ctx.lastArticle) file = await pageTools.htmlToPdf(pageTools.documentHtml(ctx.lastArticle.title, ctx.lastArticle.url, ctx.lastArticle.text), ctx.lastArticle.title);
      else if (args.source === 'summary' && ctx.lastSummary) file = await pageTools.htmlToPdf(pageTools.documentHtml(ctx.lastSummary.title + ' — Summary', ctx.lastSummary.url, ctx.lastSummary.markdown), ctx.lastSummary.title + '-summary');
      else file = await pageTools.savePdf(wc());
      ctx.lastFile = file; ctx.emit({ type: 'file', file, kind: 'pdf' });
      return `Saved PDF: ${file}`;
    }
    case 'screenshot': {
      ctx.step('shot', args.full ? 'Capturing full-page screenshot' : 'Capturing screenshot');
      const file = args.full ? (await pageTools.longScreenshot(wc())).file : await pageTools.visibleScreenshot(wc());
      ctx.lastFile = file; ctx.emit({ type: 'file', file, kind: 'image' });
      return `Saved screenshot: ${file}`;
    }
    case 'download_file': {
      ctx.step('down', `Downloading ${safeHost(args.url)}`);
      const file = await pageTools.downloadUrl(wc().session, args.url, args.filename);
      ctx.lastFile = file; ctx.emit({ type: 'file', file, kind: 'file' });
      return `Downloaded to ${file}`;
    }
    case 'send_to_phone': {
      const file = args.file === 'last' || !args.file ? ctx.lastFile : args.file;
      if (!file) return 'No file yet. Create one first (save_pdf / screenshot / download_file).';
      if (!cloud.isSignedIn()) return 'User is not signed in to Cairn account, so the phone cannot be reached. Tell them to sign in and pair their phone (Settings → Phone).';
      ctx.step('phone', `Sending to phone → ${args.folder || 'Cairn'}`);
      const r = await cloud.sendToPhone(file, args.folder);
      return r.deliveredLive ? `Sent. Phone is online and is saving it to Download/Cairn/${r.transfer.folder}.` : `Queued. The phone will save it to Download/Cairn/${r.transfer.folder} when the Cairn app connects.`;
    }
    case 'request_approval': {
      ctx.step('lock', `Waiting for your approval: ${args.title}`);
      const details = { amount: args.amount, merchant: args.merchant, item: args.item, url: wc().getURL() };
      const res = await ctx.approve({ kind: args.kind, title: args.title, details });
      ctx.approved = res === 'approved';
      ctx.step(res === 'approved' ? 'ok' : 'stop', res === 'approved' ? 'Approved' : `Not approved (${res})`);
      return res === 'approved' ? 'APPROVED. You may now perform the single final click (place order / pay).' : `NOT APPROVED (${res}). Do not pay. Tell the user and stop.`;
    }
    case 'ask_user': {
      ctx.step('ask', 'Waiting for your reply');
      const answer = await ctx.ask(args.question);
      return `User replied: ${answer}`;
    }
    default: return `Unknown tool ${name}`;
  }
}

async function pressKey(wc, key) {
  const map = { Enter: 'Return', Escape: 'Escape', Tab: 'Tab', ArrowDown: 'Down', ArrowUp: 'Up', Backspace: 'Backspace' };
  const k = map[key] || key;
  wc.sendInputEvent({ type: 'keyDown', keyCode: k });
  if (key === 'Enter') wc.sendInputEvent({ type: 'char', keyCode: '\r' });
  wc.sendInputEvent({ type: 'keyUp', keyCode: k });
}
const safeHost = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return u; } };

module.exports = { DEFS, execute, webSearch };
