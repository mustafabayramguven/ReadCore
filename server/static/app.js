'use strict';

const $ = (id) => document.getElementById(id);
const views = { login: $('login'), library: $('library'), reader: $('reader') };
const tokenKey = 'readcore.token';
const userKey = 'readcore.user';
const deviceKey = 'readcore.device';
let token = localStorage.getItem(tokenKey) || '';
let userId = localStorage.getItem(userKey) || 'default';
let lastSavedAt = 0;
let coverController = null;
let coverUrls = [];
let epubBook = null;
let rendition = null;
let openBook = null;
let readerSession = 0;
let restoring = false;
let lastLocation = null;
let progressTimer = null;
let progressQueue = Promise.resolve();
let navigating = false;
let chapterWeights = null;

// Shared with the Android reader: progression is the fraction of the chapter's non-whitespace
// characters before the reading point, so both apps resume on the same sentence.
function deviceId() {
  let id = localStorage.getItem(deviceKey);
  if (!id) { id = `Browser-${Math.random().toString(16).slice(2, 10)}`; localStorage.setItem(deviceKey, id); }
  return id;
}
const weigh = (text) => (text || '').replace(/\s+/g, '').length;
function progressionAt(doc, range) {
  const total = weigh(doc.body && doc.body.textContent);
  if (!total) return 0;
  const before = doc.createRange();
  before.setStart(doc.body, 0);
  before.setEnd(range.startContainer, range.startOffset);
  return Math.max(0, Math.min(1, weigh(before.toString()) / total));
}
function rangeAt(doc, progression) {
  if (!doc.body) return null;
  let remaining = Math.floor(progression * weigh(doc.body.textContent));
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  let last = null;
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node.textContent;
    for (let i = 0; i < text.length; i += 1) {
      if (/\s/.test(text[i])) continue;
      if (remaining === 0) { const range = doc.createRange(); range.setStart(node, i); range.collapse(true); return range; }
      remaining -= 1;
      last = [node, i];
    }
  }
  if (!last) return null;
  const range = doc.createRange(); range.setStart(last[0], last[1]); range.collapse(true);
  return range;
}
function spineIndexOf(position) {
  const match = /^epubcfi\(\/6\/(\d+)/.exec(position || '');
  return match ? Number(match[1]) / 2 - 1 : null;
}
// Book-wide percent weighted by chapter length, matching the Android app. Computed in the
// background after opening; until then the chapter count gives an estimate.
async function measureChapters(book, session) {
  const weights = [];
  for (const section of book.spine.spineItems) {
    if (session !== readerSession) return;
    try {
      await section.load(book.load.bind(book));
      weights.push(weigh(section.document && section.document.body && section.document.body.textContent));
      section.unload();
    } catch (error) { weights.push(0); }
  }
  if (session === readerSession) chapterWeights = weights;
}
function bookPercent(index, progression) {
  const total = chapterWeights ? chapterWeights.reduce((a, b) => a + b, 0) : 0;
  if (total > 0) {
    const before = chapterWeights.slice(0, index).reduce((a, b) => a + b, 0);
    return Math.max(0, Math.min(100, (before + (chapterWeights[index] || 0) * progression) * 100 / total));
  }
  const count = epubBook ? epubBook.spine.spineItems.length : 1;
  return Math.max(0, Math.min(100, (index + progression) * 100 / Math.max(1, count)));
}

const themes = {
  black: { background: '#000000', color: '#eaeaea' },
  sepia: { background: '#f4ecd8', color: '#3b2f25' },
  light: { background: '#ffffff', color: '#202020' }
};

function show(name) {
  Object.entries(views).forEach(([key, element]) => { element.hidden = key !== name; });
  $('sign-out').hidden = name === 'login';
}

function clearCovers() {
  if (coverController) coverController.abort();
  coverController = null;
  coverUrls.forEach((url) => URL.revokeObjectURL(url));
  coverUrls = [];
}

async function api(path, options = {}, credential = token) {
  const headers = new Headers(options.headers || {});
  headers.set('Authorization', `Bearer ${credential}`);
  const response = await fetch(path, { ...options, headers });
  if (!response.ok) {
    const error = new Error(response.status === 401 ? 'Invalid token. Please try again.' : `Server returned ${response.status}.`);
    error.status = response.status;
    throw error;
  }
  return response;
}

function displayError(error, target) {
  if (error.name === 'AbortError') return;
  if (error.status === 401) {
    clearCovers();
    closeReader();
    token = '';
    localStorage.removeItem(tokenKey);
    show('login');
    $('login-error').textContent = error.message;
    return;
  }
  target.textContent = error.message || 'Something went wrong.';
}

async function loadCover(book, image, controller) {
  try {
    const response = await api(`/api/v1/books/${encodeURIComponent(book.id)}/cover`, { signal: controller.signal });
    const blob = await response.blob();
    if (controller.signal.aborted) return;
    const url = URL.createObjectURL(blob);
    coverUrls.push(url);
    image.src = url;
    image.hidden = false;
    image.parentElement.querySelector('.cover-placeholder').hidden = true;
  } catch (error) {
    if (error.status === 401) displayError(error, $('library-status'));
    // A missing cover is expected; retain the text placeholder.
  }
}

async function downloadBook(book, button) {
  button.disabled = true;
  try {
    const response = await api(`/api/v1/books/${encodeURIComponent(book.id)}/download`);
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement('a');
    link.href = url;
    link.download = `${book.title.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_') || 'book'}.${book.format}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    displayError(error, $('library-status'));
  } finally {
    button.disabled = false;
  }
}

function bookCard(book, controller) {
  const card = document.createElement('article');
  card.className = 'book-card';
  const isEpub = book.format.toLowerCase() === 'epub';
  const cover = document.createElement(isEpub ? 'button' : 'div');
  cover.className = 'cover';
  if (isEpub) {
    cover.type = 'button';
    cover.setAttribute('aria-label', `Read ${book.title}`);
    cover.addEventListener('click', () => readBook(book));
  }
  const placeholder = document.createElement('span');
  placeholder.className = 'cover-placeholder';
  const format = document.createElement('span');
  format.className = 'format';
  format.textContent = book.format;
  const coverTitle = document.createElement('span');
  coverTitle.className = 'cover-title';
  coverTitle.textContent = book.title;
  placeholder.append(format, document.createElement('br'), coverTitle);
  const image = document.createElement('img');
  image.alt = '';
  image.hidden = true;
  cover.append(placeholder, image);
  const title = document.createElement('p');
  title.className = 'book-title';
  title.textContent = book.title;
  const author = document.createElement('p');
  author.className = 'book-author';
  author.textContent = book.author || '';
  const percent = book.progress ? Math.floor(book.progress.percent) : 0;
  if (percent > 0) author.textContent += `${book.author ? ' · ' : ''}${percent}% read`;
  const action = document.createElement('button');
  action.type = 'button';
  action.className = 'book-action quiet';
  action.textContent = isEpub ? 'Read' : 'Download';
  action.addEventListener('click', () => isEpub ? readBook(book) : downloadBook(book, action));
  card.append(cover, title, author, action);
  loadCover(book, image, controller);
  return card;
}

async function loadLibrary() {
  clearCovers();
  const controller = new AbortController();
  coverController = controller;
  $('books').replaceChildren();
  $('library-status').textContent = 'Loading your library…';
  try {
    const response = await api(`/api/v1/books?sort=recent&user_id=${encodeURIComponent(userId)}`, { signal: controller.signal });
    const books = await response.json();
    if (controller.signal.aborted) return;
    $('library-status').textContent = books.length ? '' : 'No books found in your library.';
    const fragment = document.createDocumentFragment();
    books.forEach((book) => fragment.appendChild(bookCard(book, controller)));
    $('books').appendChild(fragment);
  } catch (error) {
    displayError(error, $('library-status'));
  }
}

function syncRecord(book, location) {
  const start = location && location.start;
  if (!start || !Number.isInteger(start.index) || start.index < 0 || !start.cfi) return null;
  let progression = null;
  try {
    const contents = (rendition.getContents() || []).find((c) => c.sectionIndex === start.index);
    const range = rendition.getRange(start.cfi);
    if (contents && range) progression = progressionAt(contents.document, range);
  } catch (error) { /* fall back to the page fraction below */ }
  if (progression === null) {
    const displayed = start.displayed || {};
    progression = displayed.total > 1 ? Math.max(0, Math.min(1, (displayed.page - 1) / (displayed.total - 1))) : 0;
  }
  return {
    book_id: book.id, user_id: userId, position: start.cfi, percent: bookPercent(start.index, progression),
    progression, device_id: deviceId(), updated_at: Math.max(Date.now(), lastSavedAt + 1)
  };
}

function saveProgress(book, location, credential) {
  const record = syncRecord(book, location);
  if (!record) return;
  lastSavedAt = record.updated_at;
  // Serialize writes so a slow request cannot overwrite a newer position.
  progressQueue = progressQueue.catch(() => {}).then(async () => {
    await api('/api/v1/sync', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(record), keepalive: true
    }, credential);
  }).catch((error) => {
    if (openBook && openBook.id === book.id) displayError(error, $('reader-status'));
  });
}

function queueProgress(location) {
  if (!openBook || restoring) return;
  lastLocation = location;
  clearTimeout(progressTimer);
  const book = openBook;
  const credential = token;
  progressTimer = setTimeout(() => {
    progressTimer = null;
    saveProgress(book, location, credential);
  }, 1000);
}

function flushProgress() {
  if (progressTimer && lastLocation && openBook) {
    clearTimeout(progressTimer);
    progressTimer = null;
    saveProgress(openBook, lastLocation, token);
  }
}

function closeReader() {
  readerSession += 1;
  flushProgress();
  if (epubBook) epubBook.destroy();
  else if (rendition) rendition.destroy();
  rendition = null;
  epubBook = null;
  openBook = null;
  lastLocation = null;
  chapterWeights = null;
  lastSavedAt = 0;
  restoring = false;
  navigating = false;
  $('viewer').replaceChildren();
  $('reader-status').replaceChildren();
}

function applyReaderSettings() {
  if (!rendition) return;
  const selected = themes[$('theme').value] || themes.black;
  $('viewer').style.backgroundColor = selected.background;
  rendition.themes.default({
    body: { background: `${selected.background} !important`, color: `${selected.color} !important` }
  });
  rendition.themes.fontSize(`${$('font-size').value}%`);
}

async function getSync(id) {
  try {
    return await (await api(`/api/v1/sync/${encodeURIComponent(userId)}/${encodeURIComponent(id)}`)).json();
  } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }
}

async function getLegacyProgress(id) {
  try {
    return await (await api(`/api/progress/${encodeURIComponent(id)}`)).json();
  } catch (error) {
    return null;
  }
}

async function restoreLegacy(progress, session) {
  const index = progress && Number.isInteger(progress.chapter) ? progress.chapter : 0;
  const section = epubBook.spine.get(index);
  await rendition.display(section ? section.href : undefined);
  if (session !== readerSession || !progress || !section) return;
  const current = await Promise.resolve(rendition.currentLocation());
  const total = current && current.start && current.start.displayed ? current.start.displayed.total : 1;
  const targetPage = 1 + Math.round(Math.max(0, Math.min(1, Number(progress.offset) || 0)) * Math.max(0, total - 1));
  let currentPage = current && current.start && current.start.displayed ? current.start.displayed.page : 1;
  while (currentPage < targetPage && session === readerSession) {
    await rendition.next();
    const next = await Promise.resolve(rendition.currentLocation());
    if (!next || !next.start || next.start.index !== index || !next.start.displayed || next.start.displayed.page <= currentPage) break;
    currentPage = next.start.displayed.page;
  }
}

// Positions written by a browser carry an exact CFI; Android positions name the chapter and the
// character fraction inside it, which is turned into a CFI for the same sentence here.
async function goToRecord(record, session) {
  if (record.device_id && record.device_id.startsWith('Browser-')) {
    try { await rendition.display(record.position); return; } catch (error) { /* use progression */ }
  }
  const section = epubBook.spine.get(spineIndexOf(record.position) ?? 0);
  await rendition.display(section ? section.href : undefined);
  if (session !== readerSession || !section || !record.progression) return;
  const contents = (rendition.getContents() || [])[0];
  const range = contents && rangeAt(contents.document, record.progression);
  if (range) await rendition.display(contents.cfiFromRange(range));
}

function offerJump(record) {
  const status = $('reader-status');
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'quiet';
  const device = (record.device_id || 'another device').replace(/-[^-]*$/, '');
  button.textContent = `Go to ${Math.floor(record.percent)}% (read on ${device})`;
  button.addEventListener('click', async () => {
    status.replaceChildren();
    const session = readerSession;
    restoring = true;
    try { await goToRecord(record, session); } finally { if (session === readerSession) restoring = false; }
    lastSavedAt = Math.max(lastSavedAt, record.updated_at);
  });
  status.replaceChildren(button);
}

// Like Play Books: when you come back to this tab, check whether you read further elsewhere.
document.addEventListener('visibilitychange', async () => {
  if (document.visibilityState === 'hidden') { flushProgress(); return; }
  if (!openBook || restoring) return;
  const book = openBook;
  try {
    const record = await getSync(book.id);
    if (record && openBook === book && record.updated_at > lastSavedAt && record.device_id !== deviceId()) offerJump(record);
  } catch (error) { /* keep reading offline */ }
});

async function readBook(book) {
  clearCovers();
  closeReader();
  const session = readerSession;
  openBook = book;
  restoring = true;
  show('reader');
  $('reader-title').textContent = book.title;
  $('reader-status').textContent = 'Opening book…';
  $('position').textContent = '';
  try {
    if (typeof window.ePub !== 'function' || typeof window.JSZip !== 'function') {
      throw new Error('Reader libraries are unavailable. Build the server image to include them.');
    }
    // Finish a pending save before reopening a book in the same browser.
    await progressQueue;
    if (session !== readerSession) return;
    const [response, record] = await Promise.all([
      api(`/api/v1/books/${encodeURIComponent(book.id)}/download`), getSync(book.id)
    ]);
    const bytes = await response.arrayBuffer();
    if (session !== readerSession) return;
    epubBook = window.ePub(bytes);
    await epubBook.ready;
    if (session !== readerSession) return;
    rendition = epubBook.renderTo('viewer', { width: '100%', height: '100%', flow: 'paginated', allowScriptedContent: false });
    rendition.on('relocated', (location) => {
      if (session !== readerSession) return;
      const start = location.start || {};
      const displayed = start.displayed || {};
      const chapter = Number.isInteger(start.index) ? `Chapter ${start.index + 1}` : '';
      const page = displayed.page && displayed.total ? ` · ${displayed.page} / ${displayed.total}` : '';
      const fraction = displayed.total > 1 ? (displayed.page - 1) / (displayed.total - 1) : 0;
      const percent = Number.isInteger(start.index) ? ` · ${Math.floor(bookPercent(start.index, fraction))}%` : '';
      $('position').textContent = chapter + page + percent;
      queueProgress(location);
    });
    applyReaderSettings();
    measureChapters(epubBook, session);
    if (record) {
      lastSavedAt = record.updated_at;
      await goToRecord(record, session);
    } else await restoreLegacy(await getLegacyProgress(book.id), session);
    if (session !== readerSession) return;
    restoring = false;
    $('reader-status').textContent = '';
  } catch (error) {
    if (session === readerSession) {
      restoring = false;
      displayError(error, $('reader-status'));
    }
  }
}

async function turnPage(direction) {
  if (!rendition || navigating || restoring) return;
  navigating = true;
  $('previous').disabled = true;
  $('next').disabled = true;
  try {
    if (direction === 'next') await rendition.next();
    else await rendition.prev();
  } catch (error) {
    displayError(error, $('reader-status'));
  } finally {
    navigating = false;
    $('previous').disabled = false;
    $('next').disabled = false;
  }
}

$('login-form').addEventListener('submit', (event) => {
  event.preventDefault();
  token = $('token').value.trim();
  if (!token) return;
  userId = $('user-id').value.trim() || 'default';
  localStorage.setItem(tokenKey, token);
  localStorage.setItem(userKey, userId);
  $('token').value = '';
  $('login-error').textContent = '';
  show('library');
  loadLibrary();
});
$('sign-out').addEventListener('click', () => {
  clearCovers();
  closeReader();
  token = '';
  localStorage.removeItem(tokenKey);
  $('login-error').textContent = '';
  show('login');
});
$('refresh').addEventListener('click', loadLibrary);
$('back').addEventListener('click', () => { closeReader(); show('library'); loadLibrary(); });
$('previous').addEventListener('click', () => turnPage('previous'));
$('next').addEventListener('click', () => turnPage('next'));
$('font-size').addEventListener('change', applyReaderSettings);
$('theme').addEventListener('change', applyReaderSettings);

$('user-id').value = userId === 'default' ? '' : userId;
if (token) { show('library'); loadLibrary(); }
else show('login');
