'use strict';
const http = require('http');
const https = require('https');
const dns = require('dns');
const net = require('net');
const crypto = require('crypto');

function publicAddress(address) {
  const value = address.toLowerCase().replace(/^::ffff:/, '');
  if (net.isIP(value) === 4) {
    const [a, b] = value.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 || a === 169 && b === 254 || a === 172 && b >= 16 && b <= 31
      || a === 192 && [0, 168].includes(b) || a === 100 && b >= 64 && b <= 127 || a === 198 && [18, 19].includes(b));
  }
  return net.isIP(value) === 6 && !['::', '::1'].includes(value) && !/^(fc|fd|fe[89ab]|ff)/.test(value);
}
function publicLookup(hostname, options, callback) {
  dns.lookup(hostname, { all: true, verbatim: true }, (error, addresses) => {
    if (error) return callback(error);
    if (!addresses.length || addresses.some(item => !publicAddress(item.address))) return callback(new Error('Private media address rejected'));
    if (options && options.all) callback(null, addresses);
    else callback(null, addresses[0].address, addresses[0].family);
  });
}
function mediaUrl(value) {
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash
      || url.hostname === 'localhost' || url.hostname.endsWith('.localhost')
      || net.isIP(url.hostname.replace(/^\[|\]$/g, '')) && !publicAddress(url.hostname.replace(/^\[|\]$/g, ''))) throw new Error('Invalid media URL');
  return url;
}

function createMediaGateway({ request = (url, options, callback) => (url.protocol === 'https:' ? https : http).request(url, options, callback), now = Date.now } = {}) {
  const sources = new Map();
  function register(value) {
    const url = mediaUrl(value.url);
    const headers = {};
    // These are media-provider headers, never the user's account credentials.
    for (const [name, item] of Object.entries(value.headers || {})) {
      if (!['referer', 'origin', 'user-agent', 'accept'].includes(name.toLowerCase())) continue;
      if (typeof item !== 'string' || /[\r\n]/.test(item) || item.length > 4096) throw new Error('Invalid media header');
      headers[name.toLowerCase()] = item;
    }
    for (const [key, item] of sources) if (item.expires < now()) sources.delete(key);
    while (sources.size >= 64) sources.delete(sources.keys().next().value);
    const id = crypto.randomBytes(24).toString('hex');
    sources.set(id, { url, headers, expires: now() + 6 * 60 * 60 * 1000 });
    return '/media/' + id;
  }
  function handle(incoming, outgoing, target) {
    if (!target.pathname.startsWith('/media/') || target.pathname === '/media/register') return false;
    const id = target.pathname.slice(7), source = sources.get(id);
    if (!['GET', 'HEAD'].includes(incoming.method) || !/^[a-f0-9]{48}$/.test(id) || !source || source.expires < now()) {
      outgoing.writeHead(404); outgoing.end(); return true;
    }
    let current;
    const fail = () => { if (!outgoing.headersSent) outgoing.writeHead(502); outgoing.end(); };
    outgoing.once('close', () => { if (current) current.destroy(); });
    function forward(url, redirects) {
      if (outgoing.destroyed) return;
      const headers = { ...source.headers, 'accept-encoding': 'identity' };
      if (incoming.headers.range) headers.range = incoming.headers.range;
      if (incoming.headers['if-range']) headers['if-range'] = incoming.headers['if-range'];
      try {
        current = request(url, { method: incoming.method, headers, lookup: publicLookup }, response => {
          if ([301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location) {
            response.resume();
            if (redirects >= 5) { fail(); return; }
            try { forward(mediaUrl(new URL(response.headers.location, url).href), redirects + 1); } catch { fail(); }
            return;
          }
          const resultHeaders = { 'cache-control': 'no-store' };
          for (const name of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'content-encoding', 'etag', 'last-modified']) {
            if (response.headers[name] !== undefined) resultHeaders[name] = response.headers[name];
          }
          outgoing.writeHead(response.statusCode || 502, resultHeaders);
          response.once('error', () => outgoing.destroy());
          response.pipe(outgoing);
        });
        const deadline = setTimeout(() => current.destroy(new Error('Media connect deadline')), 20000);
        current.once('response', () => clearTimeout(deadline));
        current.once('close', () => clearTimeout(deadline));
        current.setTimeout(20000, () => current.destroy(new Error('Media idle deadline')));
        current.once('error', fail); current.end();
      } catch { fail(); }
    }
    forward(source.url, 0);
    return true;
  }
  return { register, handle };
}
module.exports = { createMediaGateway, publicAddress, mediaUrl };
