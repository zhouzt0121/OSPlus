/**
 * WebUI 冒烟测试。
 *
 * 用最小 DOM 垫片在 Node 里真实执行 webroot/core.js + app.js，覆盖两条关键路径：
 *   A. 无桥接（普通浏览器打开）→ 必须给出明确提示，且页面骨架仍完整，不白屏
 *   B. 有桥接（KernelSU / MMRL 注入 ksu）→ 必须正确渲染档位、规则、进程状态
 *
 * 之所以不用 jsdom：本机网络受限装不上；而这里需要断言的只是
 * 「不抛异常 + 关键节点被填充」，自建垫片足够且启动更快。
 *
 * 用法：node smoke.js <webroot 目录> <uperf|asoul>
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const WEBROOT = process.argv[2];
const PAGE = process.argv[3];
if (!WEBROOT || !PAGE) {
    console.error('用法: node smoke.js <webroot 目录> <uperf|asoul>');
    process.exit(2);
}

const CORE = fs.readFileSync(path.join(WEBROOT, 'core.js'), 'utf8');
const APP = fs.readFileSync(path.join(WEBROOT, 'app.js'), 'utf8');
const HTML = fs.readFileSync(path.join(WEBROOT, 'index.html'), 'utf8');

const htmlIds = new Set();
for (const m of HTML.matchAll(/id="([A-Za-z0-9_]+)"/g)) htmlIds.add(m[1]);

/* ------------------------------------------------------------------ DOM 垫片 */

function makeElement(tag) {
    const el = {
        tagName: String(tag).toUpperCase(),
        children: [],
        className: '',
        _text: '',
        _html: '',
        style: {},
        attrs: {},
        listeners: {},
        type: '',
        title: '',
        src: '',
        alt: '',
        value: '',
        selected: false,
        scrollTop: 0,
        scrollHeight: 0,
        parentNode: null,
        classList: {
            _set: new Set(),
            add(c) { this._set.add(c); },
            remove(c) { this._set.delete(c); },
            contains(c) { return this._set.has(c); },
        },
        appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
        removeChild(c) {
            const i = this.children.indexOf(c);
            if (i >= 0) { this.children.splice(i, 1); c.parentNode = null; }
        },
        remove() { if (this.parentNode) this.parentNode.removeChild(this); },
        setAttribute(k, v) { this.attrs[k] = v; },
        getAttribute(k) { return this.attrs[k]; },
        addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
        removeEventListener() {},
        focus() {},
        querySelector() { return null; },
        get textContent() { return this._text; },
        set textContent(v) { this._text = String(v); this.children = []; },
        get innerHTML() { return this._html; },
        // 真实 DOM 里给 innerHTML 赋值会清空既有子节点，垫片必须一致，
        // 否则「重复渲染」会被误判成「渲染了两份」
        set innerHTML(v) { this._html = String(v); this.children = []; },
    };
    return el;
}

function buildContext(useBridge) {
    const byId = new Map();
    const listeners = {};

    const document = {
        documentElement: makeElement('html'),
        body: makeElement('body'),
        createElement: makeElement,
        getElementById(id) {
            if (!byId.has(id)) byId.set(id, makeElement('div'));
            return byId.get(id);
        },
        querySelector() { return null; },
        addEventListener(type, fn) { (listeners[type] ||= []).push(fn); },
    };

    const sandbox = {
        console, setTimeout, clearTimeout, setInterval, clearInterval,
        TextEncoder, TextDecoder, btoa, atob,
        Promise, Math, Date, JSON, Object, Array, String, Number, RegExp, Error,
        Map, Set, Uint8Array,
        document,
        localStorage: {
            _m: new Map(),
            getItem(k) { return this._m.has(k) ? this._m.get(k) : null; },
            setItem(k, v) { this._m.set(k, String(v)); },
            removeItem(k) { this._m.delete(k); },
        },
    };
    sandbox.window = sandbox;
    sandbox.globalThis = sandbox;
    sandbox.window.matchMedia = () => ({ matches: false, addEventListener() {} });

    if (useBridge) {
        sandbox.ksu = {
            exec(cmd, _opts, cb) {
                let out = '';
                if (cmd.includes('pidof') || cmd.includes('pgrep -f')) {
                    out = '12345\n';
                } else if (cmd.includes('asopt.conf')) {
                    out = '# asopt config\nmode=1\nrt=0\n'
                        + 'com.miHoYo.Yuanshen 0 0\n'
                        + 'com.tencent.tmgp.sgame 2 1\n'
                        + 'com.foo.bar 1 0\n';
                } else if (cmd.includes('cur_powermode.txt')) {
                    out = 'performance\n';
                } else if (cmd.includes('uperf.json')) {
                    out = '{\n "meta": {\n  "name": "SM8750"\n }\n}';
                } else if (cmd.includes('perapp_powermode.txt')) {
                    out = 'com.foo.bar performance\n- powersave\n* performance\n';
                } else if (cmd.includes('tail -n 150')) {
                    out = 'uperf log line 1\nuperf log line 2\n';
                } else if (cmd.trimStart().startsWith('[ -d') || cmd.trimStart().startsWith('[ -e')) {
                    out = '1\n';
                }
                sandbox.window[cb](0, out, '');
            },
            toast(msg) { (sandbox.__toasts ||= []).push(String(msg)); },
            moduleInfo() {
                return JSON.stringify({
                    id: PAGE === 'uperf' ? 'uperf' : 'asoul_affinity_opt',
                    name: PAGE === 'uperf' ? 'Uperf Game Turbo' : 'A-SOUL Games Optimization',
                    version: PAGE === 'uperf' ? '1.51' : 'Kana',
                });
            },
            listPackages() { return '[]'; },
            getPackagesInfo() { return '[]'; },
        };
    }

    return { sandbox, byId, listeners };
}

/* ------------------------------------------------------------------ 断言 */

let failures = 0;
function check(name, cond, extra) {
    if (cond) {
        console.log('  [PASS] ' + name);
    } else {
        failures++;
        console.log('  [FAIL] ' + name + (extra ? '  -> ' + extra : ''));
    }
}

function idText(byId, id) {
    const el = byId.get(id);
    return el ? el.textContent : '<未创建>';
}

/** 子节点数量；节点从未被访问过时返回 -1，避免垫片缺失导致测试脚本自身崩溃 */
function childCount(byId, id) {
    const el = byId.get(id);
    return el ? el.children.length : -1;
}

function hasSelected(byId, id) {
    const el = byId.get(id);
    if (!el) return false;
    return el.children.some((c) => String(c.className).includes(' on'));
}

function runScenario(useBridge) {
    const { sandbox, byId, listeners } = buildContext(useBridge);
    const ctx = vm.createContext(sandbox);
    vm.runInContext(CORE + '\n;globalThis.__KSU = KSU;', ctx, { filename: 'core.js' });
    vm.runInContext(APP + '\n;globalThis.__APP = APP;', ctx, { filename: 'app.js' });

    const fire = (listeners.DOMContentLoaded || [])[0];
    if (fire) fire();

    return new Promise((resolve) => setTimeout(() => resolve({ sandbox, byId }), 60));
}

/** 各页面在「有桥接」场景下应当呈现的结果 */
const EXPECT = {
    uperf: {
        modeGridCount: 6,
        ruleListCount: 3,
        name: 'Uperf Game Turbo',
    },
    asoul: {
        modeGridCount: 3,
        rtGridCount: 2,
        gameListCount: 3,
        name: 'A-SOUL Games Optimization',
    },
}[PAGE];

(async function main() {
    console.log('页面: ' + PAGE + '   webroot: ' + WEBROOT);

    console.log('\n[1] DOM id 一致性');
    const referenced = new Set();
    for (const m of (CORE + APP).matchAll(/Util\.el\('([A-Za-z0-9_]+)'\)/g)) referenced.add(m[1]);
    let idBad = 0;
    for (const id of referenced) {
        if (!htmlIds.has(id)) { idBad++; check('id 存在: ' + id, false, 'HTML 中未定义'); }
    }
    check('JS 引用的 ' + referenced.size + ' 个 id 全部在 HTML 中定义', idBad === 0);

    console.log('\n[2] 场景 A：无原生桥接（普通浏览器打开）');
    const a = await runScenario(false);
    check('envBanner 已展开并给出提示', a.byId.get('envBanner').classList.contains('hidden') === false);
    check('envBanner 已注入提示节点', a.byId.get('envBanner').children.length > 0);
    check('档位网格骨架已渲染（未白屏）',
        childCount(a.byId, 'modeGrid') === EXPECT.modeGridCount,
        '实际 ' + childCount(a.byId, 'modeGrid'));

    console.log('\n[3] 场景 B：KernelSU / MMRL 注入 ksu');
    const b = await runScenario(true);
    const pill = b.byId.get('daemonPill');
    check('守护进程徽章为 ok', String(pill.className).includes('ok'), 'class=' + pill.className);
    check('徽章文案含 PID', String(pill.innerHTML).includes('12345'), String(pill.innerHTML));
    check('模块名取自 moduleInfo', idText(b.byId, 'modName') === EXPECT.name,
        idText(b.byId, 'modName'));
    check('档位网格渲染 ' + EXPECT.modeGridCount + ' 个档位',
        childCount(b.byId, 'modeGrid') === EXPECT.modeGridCount,
        '实际 ' + childCount(b.byId, 'modeGrid'));
    check('档位网格中有选中项', hasSelected(b.byId, 'modeGrid'));

    if (PAGE === 'uperf') {
        check('平台配置读取到 SM8750', idText(b.byId, 'infoSoc') === 'SM8750', idText(b.byId, 'infoSoc'));
        check('当前档位显示为「性能」', idText(b.byId, 'infoMode').includes('性能'), idText(b.byId, 'infoMode'));
        check('分应用规则渲染 ' + EXPECT.ruleListCount + ' 条',
            childCount(b.byId, 'ruleList') === EXPECT.ruleListCount,
            '实际 ' + childCount(b.byId, 'ruleList'));
        check('日志已渲染', idText(b.byId, 'logView').includes('uperf log line'));
    } else {
        check('配置文件状态为「已加载」', idText(b.byId, 'confState') === '已加载', idText(b.byId, 'confState'));
        check('全局 mode 解析为软迁移', idText(b.byId, 'modeNow') === '软迁移', idText(b.byId, 'modeNow'));
        check('全局 rt 解析为默认', idText(b.byId, 'rtNow') === '默认', idText(b.byId, 'rtNow'));
        check('实时模式网格渲染 ' + EXPECT.rtGridCount + ' 个选项',
            childCount(b.byId, 'rtGrid') === EXPECT.rtGridCount,
            '实际 ' + childCount(b.byId, 'rtGrid'));
        check('分游戏规则渲染 ' + EXPECT.gameListCount + ' 条',
            childCount(b.byId, 'gameList') === EXPECT.gameListCount,
            '实际 ' + childCount(b.byId, 'gameList'));
        check('游戏计数显示为 3 个', idText(b.byId, 'gameCount') === '3 个', idText(b.byId, 'gameCount'));
    }

    console.log('\n' + (failures === 0 ? '全部通过' : failures + ' 项未通过'));
    process.exit(failures === 0 ? 0 : 1);
})();
