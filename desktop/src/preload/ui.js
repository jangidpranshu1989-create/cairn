const { contextBridge, ipcRenderer } = require('electron');

const ALLOWED_EVENTS = new Set(['tabs:update', 'agent:event', 'agent:prefill', 'agent:toggle', 'toast', 'shield', 'find:result', 'find:open', 'focus:omnibox',
  'zoom', 'bookmark:toggle', 'pw:prompt', 'downloads:update', 'tool:busy', 'theme', 'settings:changed', 'cloud:session', 'cloud:online', 'fullscreen']);

contextBridge.exposeInMainWorld('saarthi', {
  invoke: (ch, ...args) => ipcRenderer.invoke(ch, ...args),
  on: (ch, fn) => {
    if (!ALLOWED_EVENTS.has(ch)) throw new Error('blocked channel ' + ch);
    const l = (_e, d) => fn(d);
    ipcRenderer.on(ch, l);
    return () => ipcRenderer.removeListener(ch, l);
  },
  platform: process.platform,
});
