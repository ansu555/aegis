// Aegis website: asset slots, scroll reveal, nav, and the payment demo.
(() => {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---- Asset slots ----------------------------------------------------------
  // <div class="asset" data-src="assets/img/x.webp" [data-video="…mp4"] data-label="…">
  // Shows the image when the file exists, otherwise a labelled placeholder.
  document.querySelectorAll('.asset[data-src]').forEach((slot) => {
    const img = new Image();
    img.alt = '';
    img.decoding = 'async';
    img.loading = slot.closest('.hero') ? 'eager' : 'lazy';
    img.onload = () => {
      slot.classList.remove('is-empty');
      slot.prepend(img);
      const v = slot.dataset.video;
      if (v && !reduce) addVideo(slot, v);
    };
    img.onerror = () => slot.classList.add('is-empty');
    img.src = slot.dataset.src;
  });

  function addVideo(slot, src) {
    const video = document.createElement('video');
    Object.assign(video, { muted: true, loop: true, playsInline: true, autoplay: true, preload: 'metadata' });
    video.setAttribute('aria-hidden', 'true');
    video.style.opacity = '0';
    video.style.transition = 'opacity .8s';
    video.addEventListener('canplay', () => { video.style.opacity = '1'; video.play().catch(() => {}); }, { once: true });
    video.addEventListener('error', () => video.remove(), { once: true });
    const source = document.createElement('source');
    source.src = src;
    source.type = 'video/mp4';
    source.addEventListener('error', () => video.remove(), { once: true });
    video.append(source);
    slot.append(video);
  }

  // ---- Reveal on scroll -----------------------------------------------------
  const reveals = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window && !reduce) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        if (!e.isIntersecting) return;
        e.target.classList.add('in');
        io.unobserve(e.target);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    reveals.forEach((el) => io.observe(el));
  } else {
    reveals.forEach((el) => el.classList.add('in'));
  }

  // ---- Nav ------------------------------------------------------------------
  const nav = document.querySelector('.nav');
  const menu = document.querySelector('.menu');
  const onScroll = () => nav.classList.toggle('scrolled', window.scrollY > 8);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
  menu.addEventListener('click', () => {
    const open = nav.classList.toggle('open');
    menu.setAttribute('aria-expanded', String(open));
  });
  nav.querySelectorAll('.nav-links a').forEach((a) => a.addEventListener('click', () => {
    nav.classList.remove('open');
    menu.setAttribute('aria-expanded', 'false');
  }));

  // ---- Payment demo ---------------------------------------------------------
  const demo = document.querySelector('.demo');
  const steps = [...document.querySelectorAll('.step')];
  const DURATIONS = [3200, 3600, 3600, 3400];
  let stage = Number(demo.dataset.stage) || 0;
  let timer = null;
  let visible = false;
  let paused = false;

  steps.forEach((s, i) => {
    const bar = document.createElement('span');
    bar.className = 'bar';
    s.querySelector('div').append(bar);
    s.style.setProperty('--dur', `${DURATIONS[i]}ms`);
    s.addEventListener('click', () => { paused = true; go(i); });
    s.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); paused = true; go(i); }
      if (e.key === 'ArrowDown' || e.key === 'ArrowRight') { e.preventDefault(); paused = true; go((i + 1) % steps.length, true); }
      if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') { e.preventDefault(); paused = true; go((i - 1 + steps.length) % steps.length, true); }
    });
  });

  function go(i, focus = false) {
    stage = i;
    demo.dataset.stage = String(i);
    steps.forEach((s, j) => {
      const on = j === i;
      s.classList.toggle('is-active', on);
      s.setAttribute('aria-selected', String(on));
      s.tabIndex = on ? 0 : -1;
      // Restart the progress bar animation.
      const bar = s.querySelector('.bar');
      bar.style.display = 'none';
      void bar.offsetWidth;
      bar.style.display = '';
    });
    if (focus) steps[i].focus();
    schedule();
  }

  function schedule() {
    clearTimeout(timer);
    if (!visible || paused || reduce) return;
    timer = setTimeout(() => go((stage + 1) % steps.length), DURATIONS[stage]);
  }

  if ('IntersectionObserver' in window) {
    new IntersectionObserver(([e]) => {
      visible = e.isIntersecting;
      if (visible) go(stage); else clearTimeout(timer);
    }, { threshold: 0.35 }).observe(demo);
  }
})();
