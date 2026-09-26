/* ============================================================
 * core.js —— KernelSU / KernelSU-Next / APatch / MMRL WebUI 桥接层
 *
 * 依据 KernelSU 官方 JS 库（js/index.js）的真实实现编写：
 *   ksu.exec(command, optionsJson, callbackName)
 *        -> window[callbackName](errno, stdout, stderr)
 *   ksu.toast(message)
 *   ksu.moduleInfo()      -> JSON 字符串
 *   ksu.listPackages(type)-> JSON 字符串数组
 *   ksu.getPackagesInfo(jsonArray) -> JSON 字符串数组
 *   ksu.fullScreen(bool)
 *   ksu.exit()
 *
 * 说明：官方 npm 包 kernelsu 是 ESM 模块，webroot 内不经打包无法 import，
 *       因此这里直接对接管理器注入的全局对象，并做多管理器兼容回退。
 * ============================================================ */

const KSU = (function () {
    'use strict';

    let seq = 0;

    /** 探测可用的原生桥接对象，按优先级回退。 */
    function bridge() {
        const cands = [window.ksu, window.$ksu, window.mmrl, window.$];
        for (let i = 0; i < cands.length; i++) {
            const c = cands[i];
            if (c && typeof c.exec === 'function') return c;
        }
        return null;
    }

    function available() { return bridge() !== null; }

    /**
     * 以 root 身份执行一条 shell 命令。
     * @returns {Promise<{errno:number, stdout:string, stderr:string}>}
     */
    function exec(command, options, timeoutMs) {
        const limit = timeoutMs || 30000;
        return new Promise(function (resolve, reject) {
            const api = bridge();
            if (!api) { reject(new Error('NO_BRIDGE')); return; }

            const name = '__osplus_cb_' + Date.now() + '_' + (seq++);
            let settled = false;

            window[name] = function (errno, stdout, stderr) {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                try { delete window[name]; } catch (e) { /* ignore */ }
                resolve({
                    errno: Number(errno) || 0,
                    stdout: stdout == null ? '' : String(stdout),
                    stderr: stderr == null ? '' : String(stderr)
                });
            };

            const timer = setTimeout(function () {
                if (settled) return;
                settled = true;
                try { delete window[name]; } catch (e) { /* ignore */ }
                reject(new Error('TIMEOUT'));
            }, limit);

            try {
                api.exec(command, JSON.stringify(options || {}), name);
            } catch (e) {
                settled = true;
                clearTimeout(timer);
                try { delete window[name]; } catch (e2) { /* ignore */ }
                reject(e);
            }
        });
    }

    function toast(msg) {
        const api = bridge();
        try { if (api && api.toast) { api.toast(String(msg)); return true; } } catch (e) { /* ignore */ }
        return false;
    }

    function moduleInfo() {
        const api = bridge();
        try {
            if (api && api.moduleInfo) return JSON.parse(api.moduleInfo());
        } catch (e) { /* ignore */ }
        return null;
    }

    function listPackages(type) {
        const api = bridge();
        try {
            if (api && api.listPackages) {
                const r = JSON.parse(api.listPackages(type || 'user'));
                return Array.isArray(r) ? r : [];
            }
        } catch (e) { /* ignore */ }
        return [];
    }

    function getPackagesInfo(pkgs) {
        const api = bridge();
        try {
            if (api && api.getPackagesInfo) {
                const r = JSON.parse(api.getPackagesInfo(JSON.stringify(pkgs)));
                return Array.isArray(r) ? r : [];
            }
        } catch (e) { /* ignore */ }
        return [];
    }

    function fullScreen(v) {
        const api = bridge();
        try { if (api && api.fullScreen) api.fullScreen(!!v); } catch (e) { /* ignore */ }
    }

    function exit() {
        const api = bridge();
        try { if (api && api.exit) api.exit(); } catch (e) { /* ignore */ }
    }

    return {
        available: available,
        exec: exec,
        toast: toast,
        moduleInfo: moduleInfo,
        listPackages: listPackages,
        getPackagesInfo: getPackagesInfo,
        fullScreen: fullScreen,
        exit: exit
    };
})();


/* ============================================================
 * 通用工具
 * ============================================================ */

const Util = (function () {
    'use strict';

    /** shell 单引号安全转义 */
    function q(s) {
        return "'" + String(s).replace(/'/g, "'\\''") + "'";
    }

    /** UTF-8 安全的 base64 编码（atob/btoa 只处理 Latin-1） */
    function b64encode(str) {
        const bytes = new TextEncoder().encode(String(str));
        let bin = '';
        const CH = 0x8000;
        for (let i = 0; i < bytes.length; i += CH) {
            bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
        }
        return btoa(bin);
    }

    function b64decode(b64) {
        const bin = atob(String(b64).replace(/\s+/g, ''));
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new TextDecoder().decode(bytes);
    }

    /** 读取文本文件；文件不存在或读失败返回 '' */
    async function readText(path) {
        const r = await KSU.exec('cat ' + q(path) + ' 2>/dev/null');
        return r.stdout || '';
    }

    /**
     * 以 root 写入文本文件。
     * 首选 base64 中转，避免引号 / 换行 / 中文被 shell 解析破坏；
     * 若目标环境缺少 base64（部分精简 toybox），回退为引号 heredoc 写入。
     */
    async function writeText(path, content) {
        const dir = path.replace(/\/[^/]*$/, '');
        const mk = 'mkdir -p ' + q(dir) + ' 2>/dev/null; ';

        const payload = b64encode(content);
        let r = await KSU.exec(mk
            + '{ printf %s ' + q(payload) + ' | base64 -d > ' + q(path) + '; } 2>/dev/null'
            + ' && echo __OK__ || echo __FAIL__');
        if ((r.stdout || '').indexOf('__OK__') >= 0) return true;

        const EOF = '__OSPLUS_EOF__';
        r = await KSU.exec(mk
            + '{ cat > ' + q(path) + " << '" + EOF + "'\n"
            + String(content) + '\n' + EOF + '\n} 2>/dev/null && echo __OK__ || echo __FAIL__');
        return (r.stdout || '').indexOf('__OK__') >= 0;
    }

    /** 文件是否存在（桥接不可用或超时时返回 false，不抛异常） */
    async function exists(path) {
        try {
            const r = await KSU.exec('[ -e ' + q(path) + ' ] && echo 1 || echo 0');
            return r.stdout.trim() === '1';
        } catch (e) {
            return false;
        }
    }

    /** 目录是否存在（同上，不抛异常） */
    async function isDir(path) {
        try {
            const r = await KSU.exec('[ -d ' + q(path) + ' ] && echo 1 || echo 0');
            return r.stdout.trim() === '1';
        } catch (e) {
            return false;
        }
    }

    /** 包名 -> 应用信息（带缓存） */
    const pkgCache = new Map();

    async function packages(includeSystem) {
        const key = includeSystem ? 'all' : 'user';
        if (pkgCache.has(key)) return pkgCache.get(key);

        let list = [];
        const raw = KSU.listPackages(includeSystem ? 'all' : 'user');

        if (raw && raw.length) {
            // 管理器支持 listPackages：拿标签与图标
            const CHUNK = 200;
            for (let i = 0; i < raw.length; i += CHUNK) {
                const info = KSU.getPackagesInfo(raw.slice(i, i + CHUNK));
                for (let j = 0; j < info.length; j++) {
                    const p = info[j];
                    if (!p || !p.packageName) continue;
                    list.push({
                        packageName: p.packageName,
                        label: p.appLabel || p.packageName,
                        isSystem: !!p.isSystem,
                        hasIcon: true
                    });
                }
            }
        }

        if (!list.length) {
            // 回退：直接问包管理器
            const flag = includeSystem ? '' : ' -3';
            const r = await KSU.exec('cmd package list packages' + flag + ' 2>/dev/null || pm list packages' + flag);
            const seen = Object.create(null);
            (r.stdout || '').split('\n').forEach(function (line) {
                const m = line.match(/^package:(.+)$/);
                if (!m) return;
                const pn = m[1].trim();
                if (!pn || seen[pn]) return;
                seen[pn] = 1;
                list.push({ packageName: pn, label: pn, isSystem: false, hasIcon: false });
            });
        }

        list.sort(function (a, b) {
            return String(a.label).localeCompare(String(b.label), 'zh-Hans-CN');
        });
        pkgCache.set(key, list);
        return list;
    }

    function iconUrl(pkg, hasIcon) {
        return hasIcon ? 'ksu://icon/' + pkg : '';
    }

    function initialOf(text) {
        const s = String(text || '?').trim();
        return s ? s.charAt(0).toUpperCase() : '?';
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function el(id) { return document.getElementById(id); }

    function make(tag, cls, text) {
        const n = document.createElement(tag);
        if (cls) n.className = cls;
        if (text != null) n.textContent = text;
        return n;
    }

    let toastTimer = null;
    function tip(msg, ms) {
        if (KSU.toast(msg)) return;
        const old = document.querySelector('.toast');
        if (old) old.remove();
        const n = make('div', 'toast', msg);
        document.body.appendChild(n);
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { n.remove(); }, ms || 2000);
    }

    return {
        q: q,
        b64encode: b64encode,
        b64decode: b64decode,
        readText: readText,
        writeText: writeText,
        exists: exists,
        isDir: isDir,
        packages: packages,
        iconUrl: iconUrl,
        initialOf: initialOf,
        escapeHtml: escapeHtml,
        el: el,
        make: make,
        tip: tip
    };
})();


/* ============================================================
 * 主题：跟随系统 + 手动覆盖（localStorage）
 * ============================================================ */

const Theme = (function () {
    'use strict';
    const KEY = 'osplus-webui-theme';

    function apply(mode) {
        const root = document.documentElement;
        if (mode === 'light' || mode === 'dark') {
            root.setAttribute('data-theme', mode);
        } else {
            const dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
            root.setAttribute('data-theme', dark ? 'dark' : 'light');
        }
        try { localStorage.setItem(KEY, mode); } catch (e) { /* ignore */ }
        const btn = document.getElementById('btnTheme');
        if (btn) btn.textContent = mode === 'light' ? '☀' : (mode === 'dark' ? '☾' : '◐');
    }

    function current() {
        try { return localStorage.getItem(KEY) || 'auto'; } catch (e) { return 'auto'; }
    }

    function cycle() {
        const order = ['auto', 'light', 'dark'];
        const next = order[(order.indexOf(current()) + 1) % order.length];
        apply(next);
        return next;
    }

    function init() {
        apply(current());
        if (window.matchMedia) {
            try {
                window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
                    if (current() === 'auto') apply('auto');
                });
            } catch (e) { /* ignore */ }
        }
    }

    return { init: init, apply: apply, cycle: cycle, current: current };
})();


/* ============================================================
 * 通用 UI 构件
 * ============================================================ */

const UI = (function () {
    'use strict';

    /**
     * 底部弹出层。
     * @param {object} opt {title, render(bodyEl, close), search:boolean, onSearch(q)}
     */
    function sheet(opt) {
        const mask = Util.make('div', 'sheet-mask');
        const box = Util.make('div', 'sheet');
        const head = Util.make('div', 'sheet-head');
        head.appendChild(Util.make('div', 'sheet-title', opt.title || ''));
        const close = Util.make('button', 'icon-btn', '✕');
        head.appendChild(close);
        box.appendChild(head);

        const body = Util.make('div', 'sheet-body');
        box.appendChild(body);

        let input = null;
        if (opt.search) {
            input = Util.make('input', 'search');
            input.type = 'search';
            input.placeholder = opt.placeholder || '搜索应用名或包名…';
            input.addEventListener('input', function () {
                if (opt.onSearch) opt.onSearch(input.value.trim());
            });
            body.appendChild(input);
        }

        const host = Util.make('div');
        body.appendChild(host);

        function destroy() { mask.remove(); }
        close.addEventListener('click', destroy);
        mask.addEventListener('click', function (e) { if (e.target === mask) destroy(); });

        if (opt.render) opt.render(host, destroy);
        document.body.appendChild(mask);
        if (input) setTimeout(function () { input.focus(); }, 120);
        return { destroy: destroy, host: host, input: input };
    }

    /** 顶部提示条 */
    function banner(kind, html) {
        const n = Util.make('div', 'banner ' + kind);
        n.innerHTML = html;
        return n;
    }

    return { sheet: sheet, banner: banner };
})();
