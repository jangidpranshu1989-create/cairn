// Embeds the publisher's Groq / NVIDIA NIM keys (from env) into the build so users don't have to bring their own.
// Keys are lightly masked, not secret: anything shipped in an app can be extracted. Rotate them if abused.
const fs = require('fs'), path = require('path');
const mask = (k) => Buffer.from([...Buffer.from(k || '')].map((b, i) => b ^ (0x5A + i * 7) & 0xff)).toString('base64');
fs.writeFileSync(path.join(__dirname, 'src/main/builtin.json'), JSON.stringify({ g: mask(process.env.GROQ_API_KEY), n: mask(process.env.NVIDIA_API_KEY) }));
console.log('built-in keys:', process.env.GROQ_API_KEY ? 'groq' : '-', process.env.NVIDIA_API_KEY ? 'nvidia' : '-');
