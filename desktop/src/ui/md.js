// Tiny, safe Markdown renderer (escape first, then a limited set of constructs).
window.md = function md(src, cites) {
  const byN = new Map((cites || []).map(c => [c.n, c]));
  const site = (u) => { try { const p = new URL(u).hostname.replace(/^www\./, '').split('.'); return (p.length >= 3 && ['co', 'com', 'org', 'net', 'gov', 'ac', 'edu'].includes(p[p.length - 2]) ? p[p.length - 3] : p[p.length - 2] || p[0]).replace(/^./, x => x.toUpperCase()); } catch { return 'Source'; } };
  const esc = (s) => s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const inline = (s) => esc(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" data-ext>$1</a>')
    .replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, '$1<a href="$2" data-ext>$2</a>')
    .replace(/\[(\d{1,2})\]/g, (m, n) => { const c = byN.get(+n); return c ? `<a class="cite" href="${esc(c.url)}" data-ext title="${esc(c.title)}">${esc(site(c.url))}</a>` : (byN.size ? '' : m); });
  const lines = String(src || '').replace(/【(\d{1,2})(?:†[^】]*)?】/g, '[$1]').replace(/\r/g, '').split('\n');
  let html = '', list = null, table = null;
  const close = () => { if (list) { html += `</${list}>`; list = null; } if (table) { html += '</table>'; table = null; } };
  for (const raw of lines) {
    const l = raw.trimEnd();
    let m;
    if (/^\|.*\|$/.test(l.trim())) {
      if (/^\|\s*:?-{2,}/.test(l.trim())) continue;
      if (!table) { close(); html += '<table>'; table = 'th'; }
      const cells = l.trim().slice(1, -1).split('|').map(c => `<${table}>${inline(c.trim())}</${table}>`).join('');
      html += `<tr>${cells}</tr>`; table = 'td'; continue;
    }
    if ((m = l.match(/^(#{1,3})\s+(.*)/))) { close(); html += `<h${m[1].length}>${inline(m[2])}</h${m[1].length}>`; continue; }
    if ((m = l.match(/^\s*[-*•]\s+(.*)/))) { if (list !== 'ul') { close(); html += '<ul>'; list = 'ul'; } html += `<li>${inline(m[1])}</li>`; continue; }
    if ((m = l.match(/^\s*\d+[.)]\s+(.*)/))) { if (list !== 'ol') { close(); html += '<ol>'; list = 'ol'; } html += `<li>${inline(m[1])}</li>`; continue; }
    if (!l.trim()) { close(); continue; }
    close(); html += `<p>${inline(l)}</p>`;
  }
  close();
  return html;
};
