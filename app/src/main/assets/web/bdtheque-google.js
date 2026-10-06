(function () {
    window.findBubbleBdGoogleWork = function (title) {
        const googleHosts = ['google.com','www.google.com','google.fr','www.google.fr'];
        if (location.protocol !== 'https:' || !googleHosts.includes(location.hostname) || window.bubbleBdGoogleWork) return;
        const normal = value => (value || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/[’']/g,"'").replace(/\s+/g,' ').trim();
        const expected = normal(title);
        let observer, timer, pending = false;
        const state = {done:false,stop:()=>{if(observer)observer.disconnect();clearTimeout(timer);}};
        window.bubbleBdGoogleWork=state;
        function target(anchor) {
            try {
                let url = new URL(anchor.href,location.href);
                if (googleHosts.includes(url.hostname) && url.pathname === '/url') url = new URL(url.searchParams.get('q') || url.searchParams.get('url'));
                if (url.protocol !== 'https:' || !['bdtheque.com','www.bdtheque.com'].includes(url.hostname) || url.username || url.password || (url.port && url.port !== '443') || !/^\/(series|albums)\/\d+\/[^/]+\/?$/.test(url.pathname)) return;
                url.search='';url.hash='';return url.href;
            } catch (_) {return;}
        }
        function attempt() {
            pending=false;
            if(state.done || !expected || document.querySelector('form[action*="sorry"],#captcha-form'))return;
            const candidates=[...document.querySelectorAll('a[href]')].filter(a=>a.getClientRects().length>0 && getComputedStyle(a).visibility!=='hidden').map(a=>{
                const heading=a.querySelector('h3,[role="heading"]');
                if(!heading)return;
                const label=normal(heading.textContent).replace(/\s[-–—|]\s*(bd\b|avis\b|informations\b).*$/,'').trim();
                return label===expected ? target(a) : undefined;
            }).filter(Boolean);
            const urls=[...new Set(candidates)];
            if(urls.length!==1)return;
            state.done=true;state.stop();
            if(window.BubbleBdHost)window.BubbleBdHost.workFound(urls[0]);
        }
        observer=new MutationObserver(()=>{if(!pending){pending=true;setTimeout(attempt,60);}});
        observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['href','class','style','hidden']});
        timer=setTimeout(state.stop,30000);attempt();
    };
})();
