/* Shared helpers used by every page: API calls, navigation bar, movie cards, favorites. */
(function () {
  'use strict';

  var API = 'api/';

  /** Small DOM helper: el('div', {class:'x'}, child1, 'text') */
  function el(tag, attrs) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'class') node.className = attrs[k];
      else if (k === 'text') node.textContent = attrs[k];
      else if (k.indexOf('on') === 0) node.addEventListener(k.slice(2), attrs[k]);
      else node.setAttribute(k, attrs[k]);
    });
    for (var i = 2; i < arguments.length; i++) {
      var c = arguments[i];
      if (c == null) continue;
      node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    }
    return node;
  }

  function request(method, path, params, signal) {
    var qs = Object.keys(params || {})
      .filter(function (k) { return params[k] !== '' && params[k] != null; })
      .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); })
      .join('&');
    return fetch(API + path + (qs ? '?' + qs : ''), { method: method, credentials: 'same-origin', signal: signal })
      .then(function (res) {
        return res.json().then(function (data) {
          if (!res.ok) throw new Error(data.error || 'Request failed');
          return data;
        });
      });
  }
  var get = function (p, q, signal) { return request('GET', p, q, signal); };
  var post = function (p, q) { return request('POST', p, q); };

  /* ---------- navigation ---------- */
  var PAGES = [
    ['index.html', 'Browse'],
    ['recommendations.html', 'For You'],
    ['favorites.html', 'My Watchlist']
  ];

  function renderHeader() {
    var current = location.pathname.split('/').pop() || 'index.html';
    if (current === 'movie.html') current = 'index.html';
    var nav = el('nav', { class: 'nav' });
    PAGES.forEach(function (p) {
      var a = el('a', { href: p[0], text: p[1] });
      if (p[0] === current) a.className = 'active';
      if (p[0] === 'favorites.html') a.appendChild(el('span', { class: 'badge', id: 'fav-count', text: '0' }));
      nav.appendChild(a);
    });
    var header = el('header', { class: 'site-header' },
      el('div', { class: 'header-inner' },
        el('a', { class: 'logo', href: 'index.html' }, 'Movie', el('span', { text: 'Rec' })),
        nav));
    document.body.insertBefore(header, document.body.firstChild);
    document.body.appendChild(el('footer', { class: 'site-footer', text: 'Library posters via Wikipedia (film posters shown for identification). Online search results use the TMDB API and are not endorsed or certified by TMDB.' }));
    refreshFavCount();
  }

  function refreshFavCount() {
    get('favorites').then(function (list) {
      var b = document.getElementById('fav-count');
      if (b) b.textContent = list.length;
    }).catch(function () {});
  }

  /* ---------- toast ---------- */
  var toastTimer;
  function toast(msg) {
    var t = document.getElementById('toast');
    if (!t) { t = el('div', { class: 'toast', id: 'toast' }); document.body.appendChild(t); }
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('show'); }, 2000);
  }

  /* ---------- movie visuals ---------- */
  function tags(list) {
    var box = el('div', { class: 'tags' });
    list.forEach(function (g) { box.appendChild(el('span', { class: 'tag', text: g })); });
    return box;
  }

  function noPoster() {
    return el('div', { class: 'no-poster-msg' },
      el('span', { class: 'no-poster-icon', text: '🎬' }),
      el('span', { text: 'No Poster Available' }));
  }

  /** A 2:3 poster box: the real poster image, or a clean placeholder if there is none / it fails to load. */
  function posterBox(m, large) {
    var box = el('div', { class: 'poster' });
    var url = large ? m.posterLarge : m.poster;
    if (url) {
      var img = el('img', { class: 'poster-img', src: url, alt: m.title + ' poster' });
      if (!large) img.setAttribute('loading', 'lazy');
      img.addEventListener('error', function () {
        img.remove();
        box.classList.add('missing');
        box.insertBefore(noPoster(), box.firstChild);
      });
      box.appendChild(img);
    } else {
      box.classList.add('missing');
      box.appendChild(noPoster());
    }
    return box;
  }

  function yearText(m) { return m.year ? String(m.year) : 'Year unknown'; }

  function favButton(m) {
    var btn = el('button', { class: 'fav-btn' + (m.favorite ? ' on' : ''), type: 'button', 'aria-label': 'Toggle watchlist', title: 'Add / remove from watchlist', text: '♥' });
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      toggleFavorite(m).then(function (now) {
        btn.classList.toggle('on', now);
      });
    });
    return btn;
  }

  /** Toggles the watchlist on the server; resolves with the new state. */
  function toggleFavorite(m) {
    return post('favorites', { id: m.id }).then(function (r) {
      m.favorite = r.favorite;
      toast(r.favorite ? 'Added to watchlist' : 'Removed from watchlist');
      refreshFavCount();
      return r.favorite;
    }).catch(function (e) { toast(e.message); return m.favorite; });
  }

  /** Card used on browse / favorites / recommendation pages. */
  function movieCard(m) {
    var poster = posterBox(m, false);
    if (m.rating > 0) poster.appendChild(el('div', { class: 'rating-pill', text: '★ ' + m.rating.toFixed(1) }));
    poster.appendChild(favButton(m));
    var body = el('div', { class: 'card-body' },
      el('div', { class: 'card-title', text: m.title }),
      el('div', { class: 'meta', text: yearText(m) + ' · ' + m.language }),
      tags(m.genres.slice(0, 3)));
    if (m.score != null) {
      body.appendChild(el('div', { class: 'match', text: m.score + '% match' }));
      body.appendChild(el('div', { class: 'reason', text: m.reason }));
    }
    if (m.userRating) body.appendChild(el('div', { class: 'meta', text: 'Your rating: ' + '★'.repeat(m.userRating) }));
    return el('a', { class: 'card', href: 'movie.html?id=' + m.id }, poster, body);
  }

  function renderGrid(container, movies, emptyMessage) {
    container.textContent = '';
    if (!movies.length) {
      container.appendChild(el('div', { class: 'empty', text: emptyMessage }));
      return;
    }
    var grid = el('div', { class: 'grid' });
    movies.forEach(function (m) { grid.appendChild(movieCard(m)); });
    container.appendChild(grid);
  }

  window.App = {
    el: el, get: get, post: post, toast: toast, tags: tags,
    posterBox: posterBox, yearText: yearText,
    movieCard: movieCard, renderGrid: renderGrid,
    toggleFavorite: toggleFavorite, refreshFavCount: refreshFavCount
  };

  document.addEventListener('DOMContentLoaded', renderHeader);
})();
