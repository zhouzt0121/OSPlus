#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打包 Uperf / A-SOUL 的 WebUI 变体模块。

要点：
  1. 保持原包目录结构（module.prop 位于 zip 根，META-INF 保留），
     确保 Magisk / KernelSU / APatch 均可正常刷入。
  2. 复刻原包权限策略并做了增强：原包所有条目权限位为 0o0，
     安装器会自行 set_perm；这里显式写入 0755/0644，兼容性更好。
  3. webroot/ 由管理器在安装时自动设置权限与 SELinux 上下文，
     因此只写常规文件权限，不做特殊处理。
"""

import os
import stat
import sys
import zipfile

DIST = os.path.dirname(os.path.abspath(__file__))

# 需要 0755 的条目（可执行 / 安装脚本）
EXEC_NAMES = {"update-binary", "updater-script", "customize.sh", "service.sh",
              "post-fs-data.sh", "uninstall.sh", "AsoulOpt", "uperf", "busybox"}
EXEC_PREFIXES = ("bin/", "script/")


def mode_for(relpath: str, is_dir: bool) -> int:
    if is_dir:
        return 0o755
    base = os.path.basename(relpath)
    if base in EXEC_NAMES or relpath.startswith(EXEC_PREFIXES):
        return 0o755
    return 0o644


def add_dir(zf: zipfile.ZipFile, relpath: str) -> None:
    name = relpath.rstrip("/") + "/"
    zi = zipfile.ZipInfo(name)
    zi.external_attr = (stat.S_IFDIR | 0o755) << 16
    zi.compress_type = zipfile.ZIP_STORED
    zf.writestr(zi, b"")


def pack(src_dir: str, out_zip: str) -> None:
    src_dir = os.path.abspath(src_dir)
    if not os.path.isdir(src_dir):
        raise SystemExit("源目录不存在: " + src_dir)

    # 先收集全部相对路径，保证目录条目先于文件写入
    dirs, files = [], []
    for root, dnames, fnames in os.walk(src_dir):
        dnames.sort()
        rel_root = os.path.relpath(root, src_dir).replace(os.sep, "/")
        if rel_root != ".":
            dirs.append(rel_root)
        for fn in sorted(fnames):
            rel = fn if rel_root == "." else rel_root + "/" + fn
            files.append(rel)

    dirs.sort(key=lambda p: (p.count("/"), p))

    if os.path.exists(out_zip):
        os.remove(out_zip)

    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for d in dirs:
            add_dir(zf, d)
        for rel in files:
            full = os.path.join(src_dir, rel.replace("/", os.sep))
            m = mode_for(rel, False)
            zi = zipfile.ZipInfo(rel)
            zi.external_attr = (stat.S_IFREG | m) << 16
            zi.compress_type = zipfile.ZIP_DEFLATED
            with open(full, "rb") as fh:
                zf.writestr(zi, fh.read())

    size = os.path.getsize(out_zip)
    print("  -> %s  (%d 文件, %.2f MB)" % (os.path.basename(out_zip), len(files), size / 1048576))


def bump_module_prop(path: str, *, version_suffix: str, desc_suffix: str) -> None:
    """给 module.prop 追加 WebUI 标识；保留原有换行风格。"""
    with open(path, "r", encoding="utf-8") as fh:
        raw = fh.read()
    trailing_nl = raw.endswith("\n")
    lines = raw.rstrip("\n").split("\n")

    out = []
    for ln in lines:
        if ln.startswith("version=") and version_suffix and not ln.endswith(version_suffix):
            ln = ln + version_suffix
        elif ln.startswith("description=") and desc_suffix and desc_suffix not in ln:
            ln = ln + desc_suffix
        out.append(ln)

    text = "\n".join(out) + ("\n" if trailing_nl else "")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print("  module.prop 已更新:", path)


def main() -> None:
    jobs = [
        ("modules/uperf-webui", "Uperf_Game_Turbo-1.51-WebUI.zip", " · WebUI",
         " · 内置 WebUI 控制面板"),
        ("modules/asoul-webui", "A-SOUL Games Optimization-Kana-WebUI.zip", " · WebUI",
         " With WebUI control panel."),
    ]

    for src, out, vsuf, dsuf in jobs:
        print("[打包]", out)
        src_dir = os.path.join(DIST, src)
        bump_module_prop(os.path.join(src_dir, "module.prop"),
                         version_suffix=vsuf, desc_suffix=dsuf)
        pack(src_dir, os.path.join(DIST, out))


if __name__ == "__main__":
    main()
