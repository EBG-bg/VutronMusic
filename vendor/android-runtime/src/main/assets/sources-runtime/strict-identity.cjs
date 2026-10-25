'use strict';

const VERSION_MARKERS = /\b(live|remix(?:ed)?|instrumental|karaoke|acoustic|demo|remaster(?:ed)?|cover|nightcore|sped\s*up|slowed)\b|伴奏|纯音乐|純音樂|现场|現場|翻唱|混音/giu;

function normalizeText(value) {
  if (typeof value !== 'string') return '';
  return value
    .normalize('NFKC')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizedVersionMarkers(value) {
  const normalized = ` ${normalizeText(value)} `;
  return [...normalized.matchAll(VERSION_MARKERS)]
    .map((match) => match[1] || match[0])
    .sort();
}

function names(value) {
  const list = Array.isArray(value) ? value : value ? [value] : [];
  return list
    .map((item) => normalizeText(typeof item === 'string' ? item : item && item.name))
    .filter(Boolean)
    .sort();
}

function albumName(value) {
  return normalizeText(typeof value === 'string' ? value : value && value.name);
}

function candidateIdentity(candidate, target) {
  const name = normalizeText(candidate && candidate.name);
  const targetName = normalizeText(target && target.name);
  if (!name || !targetName || name !== targetName) return null;

  const markers = normalizedVersionMarkers(candidate.name);
  const targetMarkers = normalizedVersionMarkers(target.name);
  if (markers.length !== targetMarkers.length || markers.some((marker, i) => marker !== targetMarkers[i])) {
    return null;
  }

  const candidateArtists = names(candidate.artists);
  const targetArtists = names(target.artists);
  if (!targetArtists.length || !candidateArtists.length || candidateArtists.length !== targetArtists.length || candidateArtists.some((artist, i) => artist !== targetArtists[i])) return null;

  const candidateAlbum = albumName(candidate.album);
  const targetAlbum = albumName(target.album);
  let matchedAlbum = false;
  let albumConflict = false;
  if (candidateAlbum && targetAlbum) {
    matchedAlbum = candidateAlbum === targetAlbum;
    albumConflict = !matchedAlbum;
  }

  const duration = Number(candidate.duration);
  const targetDuration = Number(target.duration);
  let matchedDuration = false;
  if (Number.isFinite(duration) && duration > 0 && Number.isFinite(targetDuration) && targetDuration > 0) {
    if (Math.abs(duration - targetDuration) >= 5000) return null;
    matchedDuration = true;
  }

  // Some catalogs attach the same recording to a single, compilation or
  // reissue album. Permit that only with the exact artist lineup and a close
  // duration, then rank it below candidates with matching album metadata.
  if (albumConflict && (!matchedDuration || Math.abs(duration - targetDuration) > 2500)) return null;
  // Title + artist alone is too ambiguous. Require another independent
  // identity field; absent provider fields never count as a match.
  if (!matchedAlbum && !matchedDuration) return null;
  const matchedFields = 1 + Number(matchedAlbum) + Number(matchedDuration);
  return { rank: albumConflict ? 1 : matchedFields, matchedFields, albumConflict };
}

function selectCandidate(list, target) {
  if (!Array.isArray(list) || !target) return null;
  let selected = null;
  let selectedIdentity = null;
  for (const candidate of list.slice(0, 5)) {
    const identity = candidateIdentity(candidate, target);
    if (!identity) continue;
    if (!selected || identity.rank > selectedIdentity.rank) {
      selected = candidate;
      selectedIdentity = identity;
    }
  }
  return selected ? { candidate: selected, ...selectedIdentity } : null;
}

module.exports = { candidateIdentity, normalizeText, selectCandidate };
