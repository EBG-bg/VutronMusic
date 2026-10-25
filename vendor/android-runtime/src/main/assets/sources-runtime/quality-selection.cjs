'use strict';

// Upstream match.js writes 999000 only when it recognizes the FLAC magic
// marker. This is a format sentinel, not a measured bitrate.
function describeQuality(result) {
  const br = Number(result && result.br);
  const isFlac = br === 999000;
  return {
    ...result,
    type: isFlac ? 'flac' : 'unknown',
    audioType: isFlac ? 'flac' : 'unknown',
    lossless: isFlac ? true : null,
    bitrateKind: isFlac ? 'matcher-flac-marker' : Number.isFinite(br) && br > 0 ? 'known' : 'unknown',
  };
}

function qualityRank(result) {
  if (result.lossless === true && result.audioType === 'flac') return Number.MAX_SAFE_INTEGER;
  const br = Number(result.br);
  return result.bitrateKind === 'known' && Number.isFinite(br) && br > 0 ? br : -1;
}

async function compareValidatedSources(options) {
  const {
    sources, concurrency, sourceTimeoutMs, totalTimeoutMs,
    isCancelled, createScope, cancelScope, find,
    onSuccess, onFailure, onTimeout,
  } = options;
  const startedAt = Date.now();
  const deadline = startedAt + totalTimeoutMs;
  const results = [];
  const activeScopes = new Set();
  let next = 0;
  let finished = false;

  const worker = async () => {
    while (!finished && !isCancelled() && Date.now() < deadline) {
      const source = sources[next++];
      if (!source) return;
      const sourceDeadline = Math.min(Date.now() + sourceTimeoutMs, deadline);
      const scope = createScope(source, sourceDeadline);
      activeScopes.add(scope);
      let timer;
      try {
        const result = await Promise.race([
          find(source, scope),
          new Promise((_, reject) => {
            timer = setTimeout(() => {
              const error = new Error('Source deadline');
              error.code = 'SOURCE_TIMEOUT';
              cancelScope(scope);
              reject(error);
            }, Math.max(1, sourceDeadline - Date.now()));
          }),
        ]);
        if (finished || isCancelled()) return;
        results.push({ source, result });
        onSuccess(source, result);
      } catch (error) {
        if (!finished && !isCancelled()) onFailure(source, error);
      } finally {
        clearTimeout(timer);
        cancelScope(scope);
        activeScopes.delete(scope);
      }
    }
  };

  let totalTimer;
  await Promise.race([
    Promise.all(Array.from({ length: Math.min(concurrency, sources.length) }, worker)),
    new Promise((resolve) => {
      totalTimer = setTimeout(() => {
        finished = true;
        onTimeout();
        for (const scope of activeScopes) cancelScope(scope);
        resolve();
      }, Math.max(1, deadline - Date.now()));
    }),
  ]);
  clearTimeout(totalTimer);
  finished = true;

  if (isCancelled()) return null;
  results.sort((a, b) => {
    const identityDifference = (b.result.identityRank || 0) - (a.result.identityRank || 0);
    if (identityDifference) return identityDifference;
    const difference = qualityRank(b.result) - qualityRank(a.result);
    return difference || sources.indexOf(a.source) - sources.indexOf(b.source);
  });
  if (!results.length) return null;
  return { ...results[0], candidateCount: results.length, elapsedMs: Date.now() - startedAt };
}

module.exports = { compareValidatedSources, describeQuality, qualityRank };
