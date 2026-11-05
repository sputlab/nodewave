/* NodeWave site: gallery (arrows, dots, centre emphasis, drag, lightbox) and timeline reveal. No dependencies. */
(function () {
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---------- gallery ----------
  var gallery = document.querySelector('.gallery');
  if (gallery) {
    var track = gallery.querySelector('.track');
    var shots = [].slice.call(track.querySelectorAll('.shot'));
    var prev = gallery.querySelector('.arrow.l'), next = gallery.querySelector('.arrow.r');
    var dots = document.querySelector('.dots');
    shots.forEach(function (s, i) {
      var b = document.createElement('button');
      b.setAttribute('aria-label', 'Screenshot ' + (i + 1));
      b.onclick = function () { go(i); touched(); };
      dots.appendChild(b);
    });
    var touchedOnce = false;
    function touched() { if (!touchedOnce) { touchedOnce = true; gallery.classList.add('touched'); } }
    function centerOf(el) { return el.offsetLeft + el.offsetWidth / 2; }
    function current() {
      var mid = track.scrollLeft + track.clientWidth / 2, best = 0, bd = 1e9;
      shots.forEach(function (s, i) { var d = Math.abs(centerOf(s) - mid); if (d < bd) { bd = d; best = i; } });
      return best;
    }
    function go(i) {
      i = Math.max(0, Math.min(shots.length - 1, i));
      track.scrollTo({ left: centerOf(shots[i]) - track.clientWidth / 2, behavior: reduce ? 'auto' : 'smooth' });
    }
    function update() {
      var c = current();
      shots.forEach(function (s, i) { s.classList.toggle('active', i === c); });
      [].forEach.call(dots.children, function (d, i) { d.classList.toggle('on', i === c); });
      prev.classList.toggle('hidden', c === 0);
      next.classList.toggle('hidden', c === shots.length - 1);
    }
    var raf;
    track.addEventListener('scroll', function () { cancelAnimationFrame(raf); raf = requestAnimationFrame(update); }, { passive: true });
    prev.onclick = function () { go(current() - 1); touched(); };
    next.onclick = function () { go(current() + 1); touched(); };
    track.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight') { go(current() + 1); touched(); e.preventDefault(); }
      if (e.key === 'ArrowLeft') { go(current() - 1); touched(); e.preventDefault(); }
    });
    // drag with the mouse
    var down = false, sx = 0, sl = 0, moved = false;
    track.addEventListener('mousedown', function (e) { down = true; moved = false; sx = e.pageX; sl = track.scrollLeft; });
    window.addEventListener('mouseup', function () { if (down) { down = false; track.classList.remove('drag'); } });
    window.addEventListener('mousemove', function (e) {
      if (!down) return;
      var dx = e.pageX - sx;
      if (Math.abs(dx) > 4) { moved = true; track.classList.add('drag'); touched(); }
      track.scrollLeft = sl - dx;
    });
    track.addEventListener('wheel', touched, { passive: true });
    track.addEventListener('touchstart', touched, { passive: true });

    // a slow hint when the gallery first comes into view: it drifts a little and comes back
    if (!reduce && 'IntersectionObserver' in window) {
      var hinted = false;
      new IntersectionObserver(function (es) {
        if (es[0].isIntersecting && !hinted && !touchedOnce) {
          hinted = true;
          var start = track.scrollLeft, t0 = null;
          setTimeout(function () {
            if (touchedOnce) return;
            track.style.scrollSnapType = 'none';
            (function step(t) {
              if (touchedOnce) { track.style.scrollSnapType = ''; return; }
              if (t0 === null) t0 = t;
              var p = (t - t0) / 2600;
              if (p >= 1) { track.scrollLeft = start; track.style.scrollSnapType = ''; return; }
              track.scrollLeft = start + 70 * Math.sin(Math.PI * p);
              requestAnimationFrame(step);
            })(performance.now());
          }, 700);
        }
      }, { threshold: 0.6 }).observe(gallery);
    }

    // lightbox
    var box = document.querySelector('.lightbox'), bimg = box.querySelector('img'), bcap = box.querySelector('p'), li = 0;
    function show(i) {
      li = (i + shots.length) % shots.length;
      var im = shots[li].querySelector('img');
      bimg.src = im.getAttribute('data-full') || im.src; bimg.alt = im.alt;
      bcap.textContent = shots[li].querySelector('figcaption').innerText.replace('\n', ' - ');
    }
    function open(i) { show(i); box.classList.add('open'); document.body.style.overflow = 'hidden'; }
    function close() { box.classList.remove('open'); document.body.style.overflow = ''; }
    shots.forEach(function (s, i) {
      s.querySelector('.phone').addEventListener('click', function () {
        if (moved) { moved = false; return; }
        if (i !== current()) { go(i); return; }
        open(i);
      });
    });
    box.querySelector('.x').onclick = close;
    box.addEventListener('click', function (e) { if (e.target === box) close(); });
    box.querySelector('.arrow.l').onclick = function (e) { e.stopPropagation(); show(li - 1); };
    box.querySelector('.arrow.r').onclick = function (e) { e.stopPropagation(); show(li + 1); };
    document.addEventListener('keydown', function (e) {
      if (!box.classList.contains('open')) return;
      if (e.key === 'Escape') close();
      if (e.key === 'ArrowRight') show(li + 1);
      if (e.key === 'ArrowLeft') show(li - 1);
    });
    window.addEventListener('load', function () { go(1); update(); });
    window.addEventListener('resize', update);
    update();
  }

  // ---------- timeline ----------
  var items = document.querySelectorAll('.tl');
  if (items.length) {
    if (/[?&]reveal=all/.test(location.search)) document.documentElement.classList.add('still'); // for printing and previews
    if (!('IntersectionObserver' in window) || /[?&]reveal=all/.test(location.search)) items.forEach(function (i) { i.classList.add('in'); });
    else {
      var io = new IntersectionObserver(function (es) {
        es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } });
      }, { threshold: 0.2 });
      items.forEach(function (i) { io.observe(i); });
    }
    // where "now" sits on the line
    var tl = document.querySelector('.timeline'), now = tl && tl.querySelector('.tl.now');
    if (tl && now) {
      var place = function () { tl.style.setProperty('--done', Math.round((now.offsetTop + 14) / tl.offsetHeight * 100) + '%'); };
      place(); window.addEventListener('load', place); window.addEventListener('resize', place);
    }
  }
})();
