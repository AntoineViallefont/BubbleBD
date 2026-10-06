(function () {
  if (window.bubbleBdMetadata || !/^https:\/\/(www\.)?bdtheque\.com\/(series|albums)\/\d+\/[^/?#]+\/?$/.test(location.href)) return;
  window.bubbleBdMetadata = true;
  let previous = '', pending = false;
  function capture() {
    pending = false;
    const heading = document.querySelector('h1');
    if (!heading || document.querySelector('#challenge-running,#challenge-form')) return;
    const root = document.createElement('div');
    // Copy bibliographic fields only: no reviews, user forms, scripts or account content.
    [heading, ...document.querySelectorAll('table'), ...document.querySelectorAll('p.lead,#storyParagraph')].forEach(e => root.appendChild(e.cloneNode(true)));
    const albums = document.querySelector('#albums');
    if (albums) { const holder = document.createElement('div'); holder.id = 'albums'; albums.querySelectorAll('.card').forEach(e => holder.appendChild(e.cloneNode(true))); root.appendChild(holder); }
    const rating = [...document.querySelectorAll('img[alt^="Note:"]')].find(e => !e.closest('#series_comments,#albumsModal') && /pour\s+\d+\s+avis/.test(e.parentElement.textContent));
    if (rating) { const aggregate = document.createElement('div'); aggregate.id = 'bubble-global-rating'; aggregate.appendChild(rating.cloneNode(true)); aggregate.appendChild(document.createTextNode(rating.parentElement.textContent)); root.appendChild(aggregate); }
    document.querySelectorAll('script[type="application/ld+json"]').forEach(e => root.appendChild(e.cloneNode(true)));
    root.querySelectorAll('script:not([type="application/ld+json"]),form,input,textarea,button').forEach(e => e.remove());
    const html = root.innerHTML;
    if (html !== previous && html.length <= 160000) { previous = html; if (window.BubbleBdHost) window.BubbleBdHost.metadataFound(location.href,html); }
  }
  const observer = new MutationObserver(() => { if (!pending) { pending = true; setTimeout(capture,120); } });
  observer.observe(document.documentElement,{childList:true,subtree:true});
  setTimeout(() => observer.disconnect(),30000);
  capture();
})();
