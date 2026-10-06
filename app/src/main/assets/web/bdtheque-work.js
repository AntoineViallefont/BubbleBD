(function () {
    window.openBubbleBdWork = function (query, associated) {
        if (location.protocol !== 'https:' || !['bdtheque.com', 'www.bdtheque.com'].includes(location.hostname)) return;
        if (window.bubbleBdWork) return;
        const normal = value => (value || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[’']/g, "'").replace(/\s+/g, ' ').trim();
        const expected = normal(query);
        const visible = element => element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden';
        const valid = value => {
            try { const u = new URL(value, location.href); return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443') && ['bdtheque.com', 'www.bdtheque.com'].includes(u.hostname) && /^\/(series|albums)\/\d+\/[^/]+\/?$/.test(u.pathname); }
            catch (_) { return false; }
        };
        let observer, timer, pending = false;
        const detailLabel = element => /^voir la fiche (de cet album|de cette serie|de l'album|de la serie)$/.test(normal(element.textContent));
        const state = {done: false, stop: () => { if (observer) observer.disconnect(); clearTimeout(timer); }};
        window.bubbleBdWork = state;
        function attempt() {
            pending = false;
            if (state.done || document.querySelector('#challenge-running,#challenge-form')) return;
            const links = [...document.querySelectorAll('a[href]')].filter(a => visible(a) && valid(a.href) && !a.getAttribute('href').startsWith('#'));
            const matchingHeading = expected && [...document.querySelectorAll('h1,h2')].some(h => normal(h.textContent) === expected);
            // Reviews/results can be an intermediate page: follow the site's own detail link.
            const detail = associated || matchingHeading ? links.filter(detailLabel) : [];
            const candidates = detail.length ? detail : links.filter(a => expected && normal(a.textContent) === expected);
            const targets = [...new Set(candidates.map(a => a.href))];
            if (targets.length === 0 && (associated || matchingHeading)) {
                // Some album sheets are opened by the site's own modal button.
                const buttons = [...document.querySelectorAll('button,[role="button"],a[href^="#"]')].filter(b => visible(b) && detailLabel(b) && !b.disabled && (!b.hasAttribute('href') || b.getAttribute('href').startsWith('#')));
                if (buttons.length === 1) { state.done = true; state.stop(); buttons[0].click(); }
                return;
            }
            if (targets.length !== 1 || targets[0] === location.href) return;
            state.done = true; state.stop();
            if (window.BubbleBdHost) window.BubbleBdHost.workFound(targets[0]);
        }
        observer = new MutationObserver(() => { if (!pending) { pending = true; setTimeout(attempt, 60); } });
        observer.observe(document.documentElement, {childList: true, subtree: true, attributes: true, attributeFilter: ['class','style','hidden','href']});
        timer = setTimeout(state.stop, 30000);
        attempt();
    };
})();
