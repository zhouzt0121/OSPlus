/* ============================================================
 * app.js —— Uperf Game Turbo 控制面板
 *
 * 控制原理（与模块自身脚本等价，非另起一套机制）：
 *   电源模式 = 写入 switchInode
 *     /data/media/0/Android/yc/uperf/cur_powermode.txt
 *   该文件正是 uperf.json 中 modules.switcher.switchInode，
 *   uperf 守护进程通过 inotify 监听它并即时切换调度档位。
 *   模块自带的 script/powercfg_main.sh 做的也只是 echo "$1" > 该文件。
 *   分应用规则 = /sdcard/Android/yc/uperf/perapp_powermode.txt
 * ============================================================ */

const APP = (function () {
    'use strict';

    /* ---------------- 路径 ---------------- */

    const P = {
        user: '/sdcard/Android/yc/uperf',
        userDirect: '/data/media/0/Android/yc/uperf',
        state: '/sdcard/Android/yc/uperf/cur_powermode.txt',
        stateDirect: '/data/media/0/Android/yc/uperf/cur_powermode.txt',
        perapp: '/sdcard/Android/yc/uperf/perapp_powermode.txt',
        json: '/sdcard/Android/yc/uperf/uperf.json',
        log: '/sdcard/Android/yc/uperf/uperf_log.txt',
        moddir: '/data/adb/modules/uperf',
        initsvc: '/data/adb/modules/uperf/script/initsvc.sh',
        binary: '/data/adb/modules/uperf/bin/uperf'
    };

    /* ---------------- 档位定义 ---------------- */

    const MODES = [
        { id: 'powersave',   name: '省电',   desc: '压低频率与并发，续航优先' },
        { id: 'balance',     name: '均衡',   desc: '默认档，性能与功耗折中' },
        { id: 'performance', name: '性能',   desc: '提高调频响应，日常游戏' },
        { id: 'fast',        name: '极速',   desc: '放开上限，需自行散热' },
        { id: 'auto',        name: '自动',   desc: '由 uperf 按负载动态决策' },
        { id: 'crazy',       name: '疯狂',   desc: 'pedestal 档，接近满频' }
    ];

    /* 分应用规则允许的档位（crazy 属全局专用档，不下放到单应用） */
    const PERAPP_MODES = ['powersave', 'balance', 'performance', 'fast', 'auto'];

    const MODE_LABEL = {};
    MODES.forEach(function (m) { MODE_LABEL[m.id] = m.name; });

    /* ---------------- 读取封装 ----------------
     * 读取路径一律不抛异常：桥接不可用（普通浏览器打开）或某条命令超时，
     * 都只让对应字段为空，而不是让整个 load() 中断——
     * 否则页面会停在「只有标题、档位网格空着」的半渲染状态。
     */

    function safeExec(cmd, opts, timeout) {
        return KSU.exec(cmd, opts, timeout).catch(function (e) {
            return { errno: -1, stdout: '', stderr: (e && e.message) ? e.message : String(e) };
        });
    }

    function safeRead(path) {
        return safeExec('cat ' + Util.q(path) + ' 2>/dev/null').then(function (r) {
            return r.stdout || '';
        });
    }

    /* ---------------- 状态 ---------------- */

    let rules = [];          // [{ target, mode, special }]
    let currentMode = '';
    let busy = false;

    /* ---------------- 解析 / 序列化 ---------------- */

    const HEADER = [
        '# 分应用性能模式配置',
        '# Per-app dynamic power mode rule',
        '# "-" 为息屏规则 / offscreen rule',
        '# "*" 为默认规则 / default rule',
        '# 格式 / Format: <包名> <模式>   例 / e.g.: com.miHoYo.Yuanshen performance',
        '# 未命中的应用沿用全局电源模式 / unmatched apps fall back to global mode',
        ''
    ].join('\n');

    function parseRules(text) {
        const out = [];
        String(text || '').split('\n').forEach(function (raw) {
            const line = raw.replace(/\r$/, '').trim();
            if (!line || line.charAt(0) === '#') return;
            const parts = line.split(/\s+/);
            if (parts.length < 2) return;
            const target = parts[0];
            const mode = parts[1];
            out.push({
                target: target,
                mode: mode,
                special: (target === '-' || target === '*')
            });
        });
        return out;
    }

    function serializeRules(list) {
        const normal = list.filter(function (r) { return !r.special; });
        const special = list.filter(function (r) { return r.special; });
        let s = HEADER;
        normal.forEach(function (r) { s += r.target + ' ' + r.mode + '\n'; });
        special.forEach(function (r) { s += r.target + ' ' + r.mode + '\n'; });
        return s;
    }

    /* ---------------- 读取 ---------------- */

    async function readState() {
        // 优先读 inode 本体所在路径，其次读 /sdcard 映射路径
        let v = (await safeRead(P.stateDirect)).trim();
        if (!v) v = (await safeRead(P.state)).trim();
        return v.split(/\s+/)[0] || '';
    }

    async function load() {
        const [state, perapp, pidOut, jsonText] = await Promise.all([
            readState(),
            safeRead(P.perapp),
            safeExec('pidof uperf 2>/dev/null || pgrep -f "[u]perf" 2>/dev/null'),
            safeRead(P.json)
        ]);

        currentMode = state;
        rules = parseRules(perapp);

        let socName = '';
        try {
            const j = JSON.parse(jsonText);
            if (j && j.meta && j.meta.name) socName = j.meta.name;
        } catch (e) { /* 忽略：文件可能尚未生成 */ }

        renderModes();
        renderRules();

        const pid = (pidOut.stdout || '').trim();
        setStatus(pid, socName, rules.length);
    }

    /* ---------------- 渲染：档位 ---------------- */

    function renderModes() {
        const box = Util.el('modeGrid');
        box.innerHTML = '';
        MODES.forEach(function (m) {
            const b = Util.make('button', 'mode' + (m.id === currentMode ? ' on' : ''));
            b.type = 'button';
            b.appendChild(Util.make('div', 'mode-name', m.name));
            b.appendChild(Util.make('div', 'mode-desc', m.desc));
            b.addEventListener('click', function () { applyMode(m.id); });
            box.appendChild(b);
        });

        const cur = MODE_LABEL[currentMode] || currentMode || '未知';
        Util.el('modeNow').textContent = currentMode ? cur : '未读取到';
        Util.el('modeNow').className = 'pill ' + (currentMode ? 'brand' : 'warn');
    }

    async function applyMode(id) {
        if (busy) return;
        busy = true;
        try {
            // 双路径写入：先写 uperf 实际监听的 inode，再写 /sdcard 映射，
            // 保证在 FUSE 与直连两种挂载视图下都能触发 inotify。
            const cmd = [
                'F1=' + Util.q(P.stateDirect),
                'F2=' + Util.q(P.state),
                '[ -w "$F1" ] && printf "%s\\n" ' + Util.q(id) + ' > "$F1"',
                '[ -w "$F2" ] && printf "%s\\n" ' + Util.q(id) + ' > "$F2"',
                'cat "$F1" 2>/dev/null || cat "$F2" 2>/dev/null'
            ].join('; ');

            const r = await safeExec(cmd);
            const back = (r.stdout || '').trim().split(/\s+/)[0];

            if (back === id) {
                currentMode = id;
                renderModes();
                Util.tip('已切换到「' + (MODE_LABEL[id] || id) + '」');
            } else if (!back) {
                Util.tip('写入失败：状态文件不可写，请确认模块已安装并授予 root');
            } else {
                currentMode = back;
                renderModes();
                Util.tip('内核回读为「' + back + '」，未生效');
            }
        } catch (e) {
            Util.tip('执行失败：' + describeError(e));
        } finally {
            busy = false;
        }
    }

    /* ---------------- 渲染：规则 ---------------- */

    function ruleMeta(target) {
        if (target === '-') return { label: '息屏时', pkg: 'offscreen rule', icon: '☾' };
        if (target === '*') return { label: '默认规则', pkg: 'default rule', icon: '✳' };
        return null;
    }

    function renderRules() {
        const box = Util.el('ruleList');
        box.innerHTML = '';

        if (!rules.length) {
            box.appendChild(Util.make('div', 'empty', '暂无分应用规则，所有应用使用全局电源模式'));
            return;
        }

        rules.forEach(function (r, idx) {
            const meta = ruleMeta(r.target);
            const row = Util.make('div', 'rule');

            const ico = Util.make('div', 'rule-ico');
            if (meta) {
                ico.textContent = meta.icon;
            } else {
                const img = document.createElement('img');
                img.src = Util.iconUrl(r.target, true);
                img.alt = '';
                img.addEventListener('error', function () {
                    ico.textContent = Util.initialOf(r.target);
                });
                ico.appendChild(img);
            }
            row.appendChild(ico);

            const body = Util.make('div', 'rule-body');
            body.appendChild(Util.make('div', 'rule-name',
                meta ? meta.label : (r.label || r.target)));
            body.appendChild(Util.make('div', 'rule-pkg',
                meta ? meta.pkg : r.target));
            row.appendChild(body);

            const sel = document.createElement('select');
            sel.className = 'mode-sel';
            PERAPP_MODES.forEach(function (m) {
                const o = document.createElement('option');
                o.value = m;
                o.textContent = MODE_LABEL[m] || m;
                if (m === r.mode) o.selected = true;
                sel.appendChild(o);
            });
            if (PERAPP_MODES.indexOf(r.mode) < 0) {
                const o = document.createElement('option');
                o.value = r.mode;
                o.textContent = r.mode;
                o.selected = true;
                sel.appendChild(o);
            }
            sel.addEventListener('change', function () {
                rules[idx].mode = sel.value;
                saveRules('已更新「' + (meta ? meta.label : r.target) + '」为' + (MODE_LABEL[sel.value] || sel.value));
            });
            row.appendChild(sel);

            if (!meta) {
                const del = Util.make('button', 'rule-del', '✕');
                del.type = 'button';
                del.title = '删除该规则';
                del.addEventListener('click', function () {
                    rules.splice(idx, 1);
                    renderRules();
                    saveRules('已删除该规则');
                });
                row.appendChild(del);
            }

            box.appendChild(row);
        });
    }

    async function saveRules(okMsg) {
        try {
            const ok = await Util.writeText(P.perapp, serializeRules(rules));
            if (ok) {
                Util.tip(okMsg || '已保存');
            } else {
                Util.tip('保存失败：无写入权限');
            }
        } catch (e) {
            Util.tip('保存失败：' + describeError(e));
        }
    }

    /* ---------------- 添加应用 ---------------- */

    let pkgCacheList = null;

    async function openPicker() {
        if (rules.some(function (r) { return r.target === '*'; }) === false) {
            // 不强制，允许没有默认规则
        }

        let list;
        try {
            list = await Util.packages(false);
        } catch (e) {
            list = [];
        }
        if (!list.length) {
            Util.tip('无法获取应用列表');
            return;
        }
        pkgCacheList = list;

        let sh = null;
        sh = UI.sheet({
            title: '选择要指定模式的应用',
            search: true,
            placeholder: '搜索应用名或包名…',
            render: function (host) {
                const render = function (kw) {
                    host.innerHTML = '';
                    const used = Object.create(null);
                    rules.forEach(function (r) { used[r.target] = 1; });

                    const filtered = list.filter(function (p) {
                        if (used[p.packageName]) return false;
                        if (!kw) return true;
                        const k = kw.toLowerCase();
                        return p.packageName.toLowerCase().indexOf(k) >= 0
                            || String(p.label).toLowerCase().indexOf(k) >= 0;
                    }).slice(0, 300);

                    if (!filtered.length) {
                        host.appendChild(Util.make('div', 'empty', '没有匹配的应用'));
                        return;
                    }

                    const wrap = Util.make('div', 'pkg-list');
                    filtered.forEach(function (p) {
                        const row = Util.make('div', 'pkg');
                        const ico = Util.make('div', 'rule-ico');
                        const img = document.createElement('img');
                        img.src = Util.iconUrl(p.packageName, p.hasIcon);
                        img.alt = '';
                        img.addEventListener('error', function () {
                            ico.textContent = Util.initialOf(p.label);
                        });
                        ico.appendChild(img);
                        row.appendChild(ico);

                        const body = Util.make('div', 'rule-body');
                        body.appendChild(Util.make('div', 'rule-name', p.label));
                        body.appendChild(Util.make('div', 'rule-pkg', p.packageName));
                        row.appendChild(body);

                        row.addEventListener('click', function () {
                            rules.push({
                                target: p.packageName,
                                mode: 'performance',
                                special: false,
                                label: p.label
                            });
                            renderRules();
                            saveRules('已添加「' + p.label + '」');
                            sh.destroy();
                        });
                        wrap.appendChild(row);
                    });
                    host.appendChild(wrap);

                    const manual = Util.make('div', 'pkg-add', '手动输入包名…');
                    manual.addEventListener('click', function () {
                        const pn = window.prompt('请输入包名，例如 com.miHoYo.Yuanshen');
                        if (!pn) return;
                        const v = pn.trim();
                        if (!v) return;
                        rules.push({ target: v, mode: 'performance', special: false });
                        renderRules();
                        saveRules('已添加 ' + v);
                        sh.destroy();
                    });
                    host.appendChild(manual);
                };

                sh.onSearch = render;
                render('');
            }
        });
    }

    /* ---------------- 运行状态 ---------------- */

    function setStatus(pid, socName, ruleCount) {
        const pill = Util.el('daemonPill');
        if (pid) {
            pill.className = 'pill ok';
            pill.innerHTML = '<span class="dot"></span>运行中 · PID ' + Util.escapeHtml(pid.split(/\s+/)[0]);
        } else {
            pill.className = 'pill danger';
            pill.innerHTML = '<span class="dot"></span>未运行';
        }

        Util.el('infoSoc').textContent = socName || '未识别';
        Util.el('infoMode').textContent = currentMode
            ? (MODE_LABEL[currentMode] || currentMode) + '（' + currentMode + '）'
            : '未读取到';
        Util.el('infoRules').textContent = ruleCount + ' 条';
    }

    async function refreshStatus() {
        const [pidOut, jsonText, state] = await Promise.all([
            safeExec('pidof uperf 2>/dev/null || pgrep -f "[u]perf" 2>/dev/null'),
            safeRead(P.json),
            readState()
        ]);
        let socName = '';
        try {
            const j = JSON.parse(jsonText);
            if (j && j.meta && j.meta.name) socName = j.meta.name;
        } catch (e) { /* ignore */ }
        currentMode = state;
        setStatus((pidOut.stdout || '').trim(), socName, rules.length);
    }

    async function loadLog() {
        const pre = Util.el('logView');
        pre.textContent = '读取中…';
        try {
            const r = await safeExec('tail -n 150 ' + Util.q(P.log) + ' 2>/dev/null');
            const t = (r.stdout || '').trim();
            pre.textContent = t || '（日志为空或文件尚未生成）';
            pre.scrollTop = pre.scrollHeight;
        } catch (e) {
            pre.textContent = '读取失败：' + describeError(e);
        }
    }

    /* ---------------- 服务操作 ---------------- */

    /*
     * 重启 uperf 守护进程。
     *
     * 注意不能直接调用 script/initsvc.sh：该脚本里的 uperf_start() 是
     * `$BIN_PATH/uperf ... `（前台执行，不后台化），它会一直阻塞在守护进程上，
     * 因此 initsvc.sh 从 shell 里同步调用会永久挂起，并且会再拉起第二个 uperf 实例。
     * 这里只做「终止 + 分离重启」，等价于 uperf_start 去掉一次性的系统统一化步骤
     * （inotify 上限、cgroup 归置在开机时已完成，无需重做）。
     */
    async function reloadService() {
        if (busy) return;
        busy = true;
        Util.tip('正在重启 uperf…', 4000);
        try {
            const cmd = [
                'M=/data/adb/modules/uperf',
                'U=/sdcard/Android/yc/uperf',
                '[ -f "$U/uperf.json" ] || U=/data/media/0/Android/yc/uperf',
                '[ -x "$M/bin/uperf" ] || { echo __NOBIN__; exit 0; }',
                'killall uperf 2>/dev/null',
                'sleep 1',
                'cd "$M/bin" || exit 1',
                'S=""',
                'command -v setsid >/dev/null 2>&1 && S=setsid',
                'PATH="$M/bin/busybox:$PATH" nohup $S ./uperf "$U/uperf.json" -o "$U/uperf_log.txt" >/dev/null 2>&1 &',
                'sleep 2',
                'pidof uperf 2>/dev/null'
            ].join('\n');

            const r = await safeExec(cmd, {}, 30000);

            if ((r.stdout || '').indexOf('__NOBIN__') >= 0) {
                Util.tip('未找到 ' + P.binary + '，请确认模块已完整安装');
                return;
            }
            await refreshStatus();
            const pid = (r.stdout || '').trim().split(/\s+/).pop();
            Util.tip(pid && pid !== '__NOBIN__' ? '已重启，PID ' + pid : '重启命令已下发，请稍后刷新查看');
        } catch (e) {
            Util.tip('重启失败：' + describeError(e));
        } finally {
            busy = false;
        }
    }

    async function reloadAll() {
        await load();
        await loadLog();
    }

    /* ---------------- 错误描述 ---------------- */

    function describeError(e) {
        const msg = (e && e.message) ? e.message : String(e);
        if (msg === 'NO_BRIDGE') return '当前 root 管理器未提供 WebUI 接口';
        if (msg === 'TIMEOUT') return '命令超时';
        return msg;
    }

    /* ---------------- 启动 ---------------- */

    async function init() {
        Theme.init();

        Util.el('btnTheme').addEventListener('click', function () { Theme.cycle(); });
        Util.el('btnReload').addEventListener('click', reloadService);
        Util.el('btnRefresh').addEventListener('click', function () {
            reloadAll();
            Util.tip('已刷新');
        });
        Util.el('btnAdd').addEventListener('click', openPicker);
        Util.el('btnLog').addEventListener('click', loadLog);

        // 环境自检
        const bridgeOk = KSU.available();
        if (!bridgeOk) {
            Util.el('envBanner').appendChild(UI.banner('danger',
                '<b>未检测到 WebUI 桥接接口。</b>本页面需要支持 webroot 的 root 管理器'
                + '（KernelSU / KernelSU-Next / APatch，或配合 MMRL 使用），'
                + '且需以 WebUI 方式打开，而非普通浏览器。'));
            Util.el('envBanner').classList.remove('hidden');
        } else {
            const installed = await Util.isDir(P.moddir);
            if (!installed) {
                Util.el('envBanner').appendChild(UI.banner('warn',
                    '<b>未在 ' + Util.escapeHtml(P.moddir) + ' 找到模块目录。</b>'
                    + '请确认 Uperf Game Turbo 已安装并重启过设备。'));
                Util.el('envBanner').classList.remove('hidden');
            }
        }

        const info = KSU.moduleInfo();
        if (info) {
            if (info.name) Util.el('modName').textContent = info.name;
            if (info.version) Util.el('modVersion').textContent = 'v' + info.version;
        }

        // 先渲染静态骨架（档位网格 / 规则列表容器），再读取状态。
        // 这样即便桥接不可用、状态全空，页面结构也是完整的，
        // 用户看到的是「档位可选但显示未知」，而不是一块空白。
        renderModes();
        renderRules();

        await load();
        await loadLog();
    }

    return { init: init, reload: reloadAll };
})();

document.addEventListener('DOMContentLoaded', function () {
    APP.init().catch(function (e) {
        console.error(e);
        try { Util.tip('初始化失败：' + (e && e.message ? e.message : e)); } catch (x) { /* ignore */ }
    });
});
