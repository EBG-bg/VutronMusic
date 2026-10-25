const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { createMediaGateway, publicAddress, mediaUrl } = require('../src/main/assets/sources-runtime/media-gateway.cjs');
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const close = server => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); });
const get = (url, options = {}) => new Promise((resolve, reject) => {
  http.get(url, options, res => { const chunks = []; res.on('data', x => chunks.push(x)); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) })); }).on('error', reject);
});
test('media gateway preserves bytes, ranges, HEAD and required media headers', async () => {
  const bytes = Buffer.from('original FLAC bytes without transcoding'); const requests = [];
  const origin = http.createServer((req, res) => {
    requests.push({ method: req.method, headers: req.headers });
    if (req.url === '/redirect') { res.writeHead(302, { location: 'http://public.example/audio' }); res.end(); return; }
    const range = /bytes=(\d+)-(\d+)/.exec(req.headers.range || '');
    const body = range ? bytes.subarray(Number(range[1]), Number(range[2]) + 1) : bytes;
    res.writeHead(range ? 206 : 200, { 'content-type': 'audio/flac', 'content-length': body.length, 'accept-ranges': 'bytes', ...(range ? { 'content-range': 'bytes 3-8/' + bytes.length } : {}) });
    res.end(req.method === 'HEAD' ? undefined : body);
  });
  await listen(origin);
  // Substitute only the external transport; production public DNS validation stays enabled.
  const gateway = createMediaGateway({ request: (url, options, callback) => http.request('http://127.0.0.1:' + origin.address().port + url.pathname, { method: options.method, headers: options.headers }, callback) });
  const server = http.createServer((req, res) => { if (!gateway.handle(req, res, new URL(req.url, 'http://127.0.0.1'))) { res.writeHead(403); res.end(); } });
  await listen(server);
  try {
    const path = gateway.register({ url: 'http://public.example/redirect', headers: { Referer: 'https://required.example/', Cookie: 'private-cookie', Authorization: 'private-auth' } });
    const url = 'http://127.0.0.1:' + server.address().port + path;
    const full = await get(url); assert.deepEqual(full.body, bytes); assert.equal(full.status, 200);
    const part = await get(url, { headers: { range: 'bytes=3-8' } });
    assert.equal(part.status, 206); assert.deepEqual(part.body, bytes.subarray(3, 9)); assert.equal(part.headers['content-range'], 'bytes 3-8/' + bytes.length);
    const head = await get(url, { method: 'HEAD' }); assert.equal(head.body.length, 0); assert.equal(Number(head.headers['content-length']), bytes.length);
    assert.equal(requests.at(-1).headers.referer, 'https://required.example/');
    assert.equal(requests.at(-1).headers.cookie, undefined); assert.equal(requests.at(-1).headers.authorization, undefined);
    assert.equal((await get('http://127.0.0.1:' + server.address().port + '/media/not-a-token')).status, 404);
    assert.equal((await get('http://127.0.0.1:' + server.address().port + '/media/register')).status, 403);
  } finally { await close(server); await close(origin); }
});
test('media registration rejects private and credential-bearing URLs', () => {
  for (const address of ['127.0.0.1', '10.1.2.3', '192.168.1.2', '169.254.169.254', '::1', 'fc00::1', '::ffff:127.0.0.1']) assert.equal(publicAddress(address), false);
  assert.equal(publicAddress('8.8.8.8'), true);
  for (const url of ['http://127.0.0.1/a', 'http://localhost/a', 'http://[::1]/a', 'file:///private/a', 'http://user:secret@example.com/a']) assert.throws(() => mediaUrl(url));
  assert.equal(mediaUrl('https://cdn.example.com/song?key=signed').protocol, 'https:');
});
