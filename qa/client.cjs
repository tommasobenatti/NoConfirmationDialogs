// Usage: node client.cjs <dependency-directory> <port> <paper|proxy>
const path = require('node:path');
const makeRequire = require('node:module').createRequire;
const req = makeRequire(path.resolve(process.argv[2], 'package.json'));
const mc = req('minecraft-protocol');
const deps = makeRequire(req.resolve('minecraft-protocol'));
const nbt = deps('prismarine-nbt');
const assert = require('node:assert/strict');
const mode = process.argv[4];
const seen = new Set();
let completed = false;
const client = mc.createClient({host: '127.0.0.1', port: Number(process.argv[3]), username: 'NcdProbe' + process.pid, auth: 'offline', version: '1.21.6'});
client.on('state', state => console.log('NCD_CLIENT_STATE', state));
client.on('login', () => {
  client.write('settings', {locale: 'en_us', viewDistance: 2, chatFlags: 0, chatColors: true, skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true, particleStatus: 'all'});
  client.write('player_loaded', {});
});
client.on('position', packet => client.write('teleport_confirm', {teleportId: packet.teleportId}));
client.on('update_health', packet => { if (packet.health <= 0) client.write('client_command', {actionId: 0}); });
client.on('packet', (data, meta) => { if (['system_chat', 'login', 'finish_configuration', 'start_configuration', 'kick_disconnect', 'disconnect'].includes(meta.name)) console.log('NCD_CLIENT_PACKET', meta.name); });
function varint(n) { const a = []; do { let b = n & 127; n >>>= 7; if (n) b |= 128; a.push(b); } while (n); return Buffer.from(a); }
function click(id) {
  const name = Buffer.from('ncd_command'), command = Buffer.from('ncdrestricted');
  const keyLength = Buffer.alloc(2); keyLength.writeUInt16BE(name.length);
  const valueLength = Buffer.alloc(2); valueLength.writeUInt16BE(command.length);
  const tag = Buffer.concat([Buffer.from([10, 8]), keyLength, name, valueLength, command, Buffer.from([0])]);
  const key = Buffer.from(id);
  // Native packet: ID, identifier, then length-prefixed optional anonymous NBT.
  // minecraft-data 1.21.6 omits this length prefix, so write the correct packet explicitly.
  client.writeRaw(Buffer.concat([varint(0x41), varint(key.length), key, varint(tag.length), tag]));
}
const timeout = setTimeout(() => { console.error('NCD_CLIENT_TIMEOUT', [...seen]); process.exitCode = 1; client.end(); }, 20000);
client.on('error', error => { console.error(error); process.exitCode = 1; });
client.on('end', reason => { clearTimeout(timeout); if (!completed) process.exitCode = 1; console.log('NCD_CLIENT_END', reason); });
client.on('system_chat', packet => {
  const tree = nbt.simplify(packet.content);
  const text = JSON.stringify(tree);
  if (!text.includes('NCD-')) { if (text.includes('translate')) console.log('NCD_CLIENT_RESPONSE', text.slice(0, 300)); return; }
  function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.click_event) {
      const event = value.click_event;
      assert.equal(event.action, 'custom', text);
      assert.ok(event.id.startsWith(mode === 'proxy' ? 'ncd:proxy/' : 'ncd:paper/'), text);
      seen.add(value.text);
      // A forged command in the payload must never override the registered server command.
      setTimeout(() => click(event.id), 1000 + seen.size * 400);
      console.log('NCD_CLIENT_CLICK', value.text, event.id.split('/')[0]);
    }
    for (const item of Object.values(value)) {
      if (Array.isArray(item)) item.forEach(visit); else if (typeof item === 'object') visit(item);
    }
  }
  visit(tree);
  if (seen.size === (mode === 'proxy' ? 4 : 2)) {
    click(mode === 'proxy' ? 'ncd:proxy/forged' : 'ncd:paper/forged');
    setTimeout(() => { clearTimeout(timeout); if (client.ended) return; completed = true; console.log('NCD_CLIENT_PASS', mode, seen.size); client.end(); }, 6000);
  }
});
