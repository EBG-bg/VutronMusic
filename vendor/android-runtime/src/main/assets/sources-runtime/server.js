'use strict';

// The matcher runs entirely in this app. It never accepts or stores an account Cookie.
process.env.LOG_LEVEL = 'silent';
process.env.JSON_LOG = 'true';
process.env.ENABLE_FLAC = 'true';
delete process.env.FOLLOW_SOURCE_ORDER;
process.env.ENABLE_LOCAL_VIP = 'true';
process.env.SELECT_MAX_BR = 'true';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { AsyncLocalStorage } = require('async_hooks');
const tasks = new AsyncLocalStorage();
const { compareValidatedSources, describeQuality } = require('./quality-selection.cjs');
const { selectCandidate } = require('./strict-identity.cjs');
const diagnosticPath = process.argv[4];
let diagnosticError = '';
let stage = 'loading-matcher';
let lastError = '';

// Only fixed event names, source names and error types enter this private log.
// Account data and signed media URLs never enter a diagnostic entry.
function diagnostic(event, fields = {}) {
  if (!diagnosticPath) return;
  try {
    if (fs.existsSync(diagnosticPath) && fs.statSync(diagnosticPath).size > 262144) {
      fs.renameSync(diagnosticPath, `${diagnosticPath}.previous`);
    }
    fs.appendFileSync(diagnosticPath, `${JSON.stringify({ time: Date.now(), event, ...fields })}\n`);
  } catch (error) {
    diagnosticError = String(error.code || error.name || 'DiagnosticError');
  }
}

function errorType(error) {
  return String(error && (error.code || error.name) || typeof error).replace(/[^A-Za-z0-9_-]/g, '').slice(0, 64);
}

diagnostic('script-start', { node: process.versions.node });
process.once('exit', (code) => diagnostic('script-exit', { code }));
process.on('uncaughtExceptionMonitor', (error) => diagnostic('script-uncaught', { errorType: errorType(error) }));

// Track each matcher's own sockets so changing songs/timeout can close its work.
for (const protocol of [http, https]) {
  const request = protocol.request;
  protocol.request = function (...args) {
    const scope = tasks.getStore();
    const outgoing = request.apply(this, args);
    if (scope) {
      scope.requests.add(outgoing);
      // A socket idle timeout starts only once a socket connects. This timer
      // also bounds slow DNS, TLS handshakes and drip-fed responses.
      const timeout = setTimeout(() => {
        const error = new Error('Source network deadline');
        error.code = 'SOURCE_NETWORK_TIMEOUT';
        outgoing.destroy(error);
      }, Math.max(1, Math.min(4000, scope.deadline - Date.now())));
      outgoing.once('close', () => { clearTimeout(timeout); scope.requests.delete(outgoing); });
      outgoing.once('error', (error) => {
        if (!scope.cancelled) {
          lastError = errorType(error);
          diagnostic('source-network-error', { source: scope.source, errorType: lastError });
        }
      });
      if (scope.cancelled) outgoing.destroy(new Error('Matcher request cancelled'));
    }
    return outgoing;
  };
}

// Upstream providers discard their selected song metadata and return only an
// audio URL. Wrap its shared find/select modules before loading the matcher so
// every source candidate is checked against the authoritative Netease record.
const matcherDirectory = path.dirname(require.resolve('@unblockneteasemusic/server'));
const findModulePath = path.join(matcherDirectory, 'find.js');
require(findModulePath);
const upstreamRequest = require(path.join(matcherDirectory, '../request.js'));
function targetMetadata(song) {
  if (!song || !song.name || !Array.isArray(song.artists) || !song.artists.length) {
    const error = new Error('Incomplete target metadata');
    error.code = 'TARGET_METADATA_MISSING';
    throw error;
  }
  return {
    id: song.id,
    // Upstream find removes cover annotations. Keep the original title so
    // live, remix, cover and instrumental recordings remain distinguishable.
    name: song.name,
    album: song.album,
    artists: song.artists,
    duration: song.duration,
    keyword: `${song.name} - ${song.artists.map((artist) => artist.name).filter(Boolean).join(' / ')}`,
  };
}
require.cache[findModulePath].exports = async (id, data) => {
  const scope = tasks.getStore();
  let info;
  if (data) info = targetMetadata(data);
  else {
    const operation = scope && scope.operation;
    if (!operation || !operation.reference) throw Object.assign(new Error('Missing authoritative target metadata'), { code: 'TARGET_SCOPE_MISSING' });
    info = operation.reference;
  }
  if (scope) scope.track = info;
  return info;
};
const selectModulePath = path.join(matcherDirectory, 'select.js');
const upstreamSelect = require(selectModulePath);
const validatedTracks = new WeakMap();
require.cache[selectModulePath].exports = (list, info) => {
  const scope = tasks.getStore();
  const selection = selectCandidate(list, info);
  if (!selection) {
    const error = new Error('No identity-validated candidate');
    error.code = 'IDENTITY_MISMATCH';
    throw error;
  }
  if (scope) {
    let sources = validatedTracks.get(info);
    if (!sources) { sources = new Map(); validatedTracks.set(info, sources); }
    sources.set(scope.source, selection.rank);
  }
  if (scope) scope.identityRank = selection.rank;
  return selection.candidate;
};
require.cache[selectModulePath].exports.ENABLE_FLAC = upstreamSelect.ENABLE_FLAC;
const match = require('@unblockneteasemusic/server');
const SOURCES = ['bodian', 'kuwo', 'kugou', 'qq', 'bilibili', 'pyncmd', 'migu'];
const SOURCE_CONCURRENCY = 3;
const SOURCE_TIMEOUT_MS = 4500;
const QUALITY_SELECTION_TIMEOUT_MS = 15000;
const port = Number(process.argv[2]);
const token = process.argv[3];
if (!(port >= 49152 && port <= 49251) || !/^[a-f0-9]{64}$/.test(token || '')) {
  throw new Error('Invalid embedded runtime configuration');
}
diagnostic('matcher-loaded');

function cancel(scope) {
  scope.cancelled = true;
  for (const outgoing of scope.requests) outgoing.destroy(new Error('Matcher request cancelled'));
  scope.requests.clear();
}

// Let the upstream matcher preserve its metadata and full 8192-byte Range check,
// while comparing validated results with bounded concurrency and deadlines.
async function highestQualityMatch(id, operation) {
  const deadline = Date.now() + QUALITY_SELECTION_TIMEOUT_MS;
  const metadataDeadline = Math.min(Date.now() + SOURCE_TIMEOUT_MS, deadline);
  const metadataScope = { source: 'metadata', requests: new Set(), cancelled: false, deadline: metadataDeadline };
  operation.scopes.add(metadataScope);
  let metadataTimer;
  try {
    stage = 'loading-song-metadata';
    operation.reference = await Promise.race([
      tasks.run(metadataScope, async () => {
        const response = await upstreamRequest('GET', `https://music.163.com/api/song/detail?ids=[${id}]`);
        return targetMetadata((await response.json()).songs?.[0]);
      }),
      new Promise((_, reject) => {
        metadataTimer = setTimeout(() => reject(Object.assign(new Error('Metadata deadline'), { code: 'METADATA_TIMEOUT' })), Math.max(1, metadataDeadline - Date.now()));
      }),
    ]);
  } finally {
    clearTimeout(metadataTimer);
    cancel(metadataScope);
    operation.scopes.delete(metadataScope);
  }
  if (operation.cancelled) return null;
  const remainingBudget = Math.max(1, deadline - Date.now());
  const selected = await compareValidatedSources({
    sources: SOURCES,
    concurrency: SOURCE_CONCURRENCY,
    sourceTimeoutMs: SOURCE_TIMEOUT_MS,
    totalTimeoutMs: remainingBudget,
    isCancelled: () => operation.cancelled,
    createScope(source, deadline) {
      const scope = { source, requests: new Set(), cancelled: false, deadline, operation };
      operation.scopes.add(scope);
      stage = `source-${source}`;
      diagnostic('source-start', { source });
      return scope;
    },
    cancelScope(scope) {
      cancel(scope);
      operation.scopes.delete(scope);
    },
    async find(source, scope) {
      const result = await tasks.run(scope, () => match(id, [source]));
      if (!result || typeof result.url !== 'string' || !/^https?:\/\//i.test(result.url)) {
        throw new Error('Invalid validated audio candidate');
      }
      const cachedIdentities = validatedTracks.get(scope.track);
      const identityRank = scope.identityRank || (cachedIdentities && cachedIdentities.get(source)) || (source === 'pyncmd' ? 1 : 0);
      if (!identityRank) {
        const error = new Error('Source supplied no checked identity evidence');
        error.code = 'IDENTITY_EVIDENCE_MISSING';
        throw error;
      }
      return {
        ...describeQuality(result),
        // pyncmd resolves the exact Netease ID directly and performs no
        // text search; searched providers are ranked by checked metadata.
        identityRank,
      };
    },
    onSuccess(source, result) {
      diagnostic('source-ready', {
        source, br: Number(result.br) || 0, size: Number(result.size) || 0,
        audioType: result.audioType,
      });
    },
    onFailure(source, error) {
      lastError = errorType(error);
      diagnostic('source-failed', { source, errorType: lastError });
    },
    onTimeout() {
      diagnostic('quality-selection-timeout', { timeoutMs: QUALITY_SELECTION_TIMEOUT_MS });
    },
  });
  if (!selected) {
    if (operation.cancelled) return null;
    const error = new Error('No available audio source');
    error.code = 'NO_AUDIO_SOURCE';
    throw error;
  }
  diagnostic('quality-selected', {
    source: selected.source,
    br: Number(selected.result.br) || 0,
    size: Number(selected.result.size) || 0,
    audioType: selected.result.audioType,
    candidateCount: selected.candidateCount,
    elapsedMs: selected.elapsedMs,
  });
  return selected.result;
}

function json(response, code, value) {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
}

const server = http.createServer((request, response) => {
  if (request.headers.authorization !== `Bearer ${token}`) {
    json(response, 403, { code: 403, message: 'Unauthorized runtime request' });
    return;
  }
  const target = new URL(request.url, 'http://127.0.0.1');
  if (request.method === 'GET' && target.pathname === '/health') {
    json(response, 200, { code: 200, ready: true, node: process.versions.node, matcher: '0.28.0', stage, lastError, diagnosticError });
    return;
  }
  const id = target.searchParams.get('id');
  if (request.method !== 'GET' || target.pathname !== '/song' || !/^[1-9]\d{0,17}$/.test(id || '')) {
    json(response, 400, { code: 400, message: 'Invalid song request' });
    return;
  }
  const operation = { scopes: new Set(), cancelled: false };
  function cancelOperation() {
    operation.cancelled = true;
    for (const scope of operation.scopes) cancel(scope);
  }
  const timeout = setTimeout(() => {
    cancelOperation();
    stage = 'match-timeout';
    diagnostic('match-timeout');
    json(response, 504, { code: 504, message: '音源查找超时，请重试或选择本地文件' });
  }, 35000);
  response.once('close', () => { clearTimeout(timeout); cancelOperation(); });
  (async () => {
    try {
      const item = await highestQualityMatch(id, operation);
      if (operation.cancelled || !item) return;
      if (!item || typeof item.url !== 'string' || !/^https?:\/\//i.test(item.url)) {
        json(response, 404, { code: 404, message: '暂未找到这首歌的可用音源' });
        return;
      }
      // Preserve all matcher metadata. Some CDNs require the same Referer
      // that upstream match.js used when checking the returned audio URL.
      const result = { ...item, headers: { ...(item.headers || {}) } };
      const host = new URL(result.url).hostname;
      if (host.endsWith('.bilivideo.com') || host === 'bilivideo.com' || host.endsWith('.akamaized.net')) {
        result.headers.Referer = 'https://www.bilibili.com/';
      }
      stage = 'match-ready';
      diagnostic('match-ready');
      json(response, 200, { code: 200, data: result });
    } catch (error) {
      if (!operation.cancelled) {
        stage = 'match-failed';
        lastError = errorType(error);
        diagnostic('match-failed', { errorType: lastError });
        json(response, 404, { code: 404, message: '暂未找到这首歌的可用音源，可重试或使用本地文件' });
      }
    } finally {
      clearTimeout(timeout);
      for (const scope of operation.scopes) cancel(scope);
    }
  })();
});
server.requestTimeout = 40000;
server.headersTimeout = 5000;
server.keepAliveTimeout = 1000;
server.on('error', (error) => {
  // Return a failed node::Start status to Java without calling process.exit(),
  // which would terminate the Android host process as well.
  process.exitCode = 1;
  stage = 'server-failed';
  diagnostic('server-failed', { errorType: errorType(error) });
});
server.listen(port, '127.0.0.1', () => {
  stage = 'ready';
  diagnostic('server-ready');
});
