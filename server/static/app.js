'use strict';

const $ = (id) => document.getElementById(id);
const views = { login: $('login'), library: $('library'), reader: $('reader') };
const tokenKey = 'readcore.token';
let token = localStorage.getItem(tokenKey) || '';
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
    const response = await api(`/api/books/${encodeURIComponent(book.id)}/cover`, { signal: controller.signal });
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
    const response = await api(`/api/books/${encodeURIComponent(book.id)}/file`);
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
  const action = document.createElement('button');
  action.type = 'button';
  action.className = 'book-action quiet';
  action.textContent = isEpub ? 'Read' : 'Download';
  action.addEventListener('click', () => isEpub ? readBook(book) : downloadBook(book, action));
  card.append(cover, title, author, action);
  if (isEpub) loadCover(book, image, controller);
  return card;
}

async function loadLibrary() {
  clearCovers();
  const controller = new AbortController();
  coverController = controller;
  $('books').replaceChildren();
  $('library-status').textContent = 'Loading your library…';
  try {
    const response = await api('/api/books', { signal: controller.signal });
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

function saveProgress(book, location, credential) {
  const start = location && location.start;
  if (!start || !Number.isInteger(start.index) || start.index < 0) return;
  const displayed = start.displayed || {};
  const page = Number(displayed.page) || 1;
  const total = Number(displayed.total) || 1;
  const offset = total > 1 ? Math.max(0, Math.min(1, (page - 1) / (total - 1))) : 0;
  const payload = JSON.stringify({ chapter: start.index, offset });
  // Serialize writes so a slow request cannot overwrite a newer position.
  progressQueue = progressQueue.catch(() => {}).then(async () => {
    await api(`/api/progress/${encodeURIComponent(book.id)}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: payload
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
  }, 350);
}

function closeReader() {
  readerSession += 1;
  if (progressTimer && lastLocation && openBook) {
    clearTimeout(progressTimer);
    progressTimer = null;
    saveProgress(openBook, lastLocation, token);
  }
  if (epubBook) epubBook.destroy();
  else if (rendition) rendition.destroy();
  rendition = null;
  epubBook = null;
  openBook = null;
  lastLocation = null;
  restoring = false;
  navigating = false;
  $('viewer').replaceChildren();
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

async function getProgress(id) {
  try {
    return await (await api(`/api/progress/${encodeURIComponent(id)}`)).json();
  } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }
}

async function restoreProgress(progress, session) {
  const index = progress && Number.isInteger(progress.chapter) ? progress.chapter : 0;
  const section = epubBook.spine.get(index);
  await rendition.display(section ? section.href : undefined);
  if (session !== readerSession || !progress || !section) return;
  const current = await Promise.resolve(rendition.currentLocation());
  const total = current && current.start && current.start.displayed ? current.start.displayed.total : 1;
  const fraction = Math.max(0, Math.min(1, Number(progress.offset) || 0));
  const targetPage = 1 + Math.round(fraction * Math.max(0, total - 1));
  let currentPage = current && current.start && current.start.displayed ? current.start.displayed.page : 1;
  // The server stores a chapter index and fraction, so advance within that chapter.
  while (currentPage < targetPage && session === readerSession) {
    await rendition.next();
    const next = await Promise.resolve(rendition.currentLocation());
    if (!next || !next.start || next.start.index !== index || !next.start.displayed || next.start.displayed.page <= currentPage) break;
    currentPage = next.start.displayed.page;
  }
}

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
    const [response, progress] = await Promise.all([
      api(`/api/books/${encodeURIComponent(book.id)}/file`), getProgress(book.id)
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
      $('position').textContent = chapter + page;
      queueProgress(location);
    });
    applyReaderSettings();
    await restoreProgress(progress, session);
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
  localStorage.setItem(tokenKey, token);
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

if (token) { show('library'); loadLibrary(); }
else show('login');
