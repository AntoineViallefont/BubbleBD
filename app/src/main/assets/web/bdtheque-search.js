(function () {
    window.startBubbleBdSearch = function (query) {
        if (!query || location.protocol !== 'https:' || !['bdtheque.com', 'www.bdtheque.com'].includes(location.hostname)) return;
        if (window.bubbleBdSearch && window.bubbleBdSearch.query === query) return;
        if (window.bubbleBdSearch) window.bubbleBdSearch.stop();
        const state = {query: query, status: 'waiting', opened: false};
        window.bubbleBdSearch = state;
        const normal = value => (value || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim();
        const visible = element => element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden';
        let observer, timer, pending = false;
        state.stop = function () { if (observer) observer.disconnect(); clearTimeout(timer); };
        function attempt() {
            pending = false;
            if (state.status === 'submitted' || document.querySelector('#challenge-running,#challenge-form')) return;
            const inputs = [...document.querySelectorAll('input:not([disabled]):not([readonly])')].filter(visible);
            const input = inputs.find(e => /serie ou auteur|titre.*auteur|recherch|search/.test(normal([e.placeholder, e.name, e.id, e.getAttribute('aria-label'), e.type === 'search' ? 'search' : ''].join(' ')))) || inputs.find(e => ['q','query','recherche','search'].includes(normal(e.name)));
            if (!input) {
                if (!state.opened) {
                    const opener = [...document.querySelectorAll('button,a[role=button],a[data-toggle],a[data-bs-toggle]')].filter(visible).find(e => /^(recherche|rechercher|search)$/.test(normal(e.getAttribute('aria-label') || e.title || e.textContent)));
                    if (opener) { state.opened = true; opener.click(); }
                }
                return;
            }
            const form = input.form;
            if (form) {
                const action = new URL(form.action || location.href, location.href);
                if (action.protocol !== 'https:' || !['bdtheque.com','www.bdtheque.com'].includes(action.hostname)) return;
            }
            const scope = form || input.closest('[role=dialog],.modal,[role=search]') || input.parentElement;
            const button = [...scope.querySelectorAll('button,input[type=submit],a[role=button]')].filter(visible).find(e => e.type === 'submit' || /^(recherche|rechercher|search|lancer la recherche)$/.test(normal(e.getAttribute('aria-label') || e.title || e.textContent || e.value)));
            // Use the native setter so React/Vue and plain input handlers receive the change.
            Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, query);
            input.dispatchEvent(new Event('input', {bubbles: true}));
            input.dispatchEvent(new Event('change', {bubbles: true}));
            state.status = 'submitted'; state.stop();
            if (window.BubbleBdHost) window.BubbleBdHost.searchSubmitted();
            if (button) button.click();
            else if (form) { if (form.requestSubmit) form.requestSubmit(); else HTMLFormElement.prototype.submit.call(form); }
            else ['keydown','keypress','keyup'].forEach(type => input.dispatchEvent(new KeyboardEvent(type, {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true})));
            input.blur();
        }
        observer = new MutationObserver(function () { if (!pending) {pending = true; setTimeout(attempt, 60);} });
        observer.observe(document.documentElement, {childList: true, subtree: true, attributes: true, attributeFilter: ['class','style','hidden']});
        timer = setTimeout(state.stop, 30000);
        attempt();
    };
})();
