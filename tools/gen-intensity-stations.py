#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""気象庁の震度観測点 CSV を生成する (B-2 第3歩)。

データ源 (いずれも気象庁の無料公開データ・キー不要):
  1. 震度観測点コード表 code_p.zip (気象庁「震度データ」ページから配布)
     https://www.data.jma.go.jp/eqev/data/bulletin/data/shindo/code_p.zip
     観測終了が空の現行観測点のみを採用する (2026-11-22 時点で 4,361 件)。
     座標は分単位の精度 (約1〜2kmの誤差がありうる)。
  2. 気象庁震度観測点一覧表 (HTML)。名称が一致する観測点は小数分までの
     座標で精度を補完し、所在地 (addr) も付ける (無い観測点は addr=観測点名)。
     https://www.data.jma.go.jp/eqev/data/kyoshin/jma-shindo.html

旧版は一覧表のみ (気象庁自身の670点) だったが、全観測点 (地方公共団体等を含む)
を対象にするため code_p へ差し替えた。

出力: src/main/resources/aegiswin/intensity-stations.csv
  形式: name,addr,lat,lon (緯度経度は世界測地系の10進度に変換)

使い方: python3 tools/gen-intensity-stations.py
"""
import csv
import os
import sys

OUT_PATH = os.path.join(os.path.dirname(__file__), "..",
                        "src", "main", "resources", "aegiswin",
                        "intensity-stations.csv")
UA = "AEGIS-Windows/gen-stations"

import html as html_mod
import io
import re
import urllib.request
import zipfile

CODE_P_URL = "https://www.data.jma.go.jp/eqev/data/bulletin/data/shindo/code_p.zip"
HTML_URL = "https://www.data.jma.go.jp/eqev/data/kyoshin/jma-shindo.html"
SEIREI = ["札幌", "仙台", "さいたま", "千葉", "横浜", "川崎", "相模原", "新潟",
          "静岡", "浜松", "名古屋", "京都", "大阪", "堺", "神戸", "岡山", "広島",
          "北九州", "福岡", "熊本"]

def normalize_key(name):
    s = name.replace("＊", "").replace("（旧）", "").replace("(旧)", "")
    s = re.sub(r"\s+", "", s)
    s = s.translate(str.maketrans("０１２３４５６７８９", "0123456789"))
    for city in SEIREI:
        if s.startswith(city + "市"):
            s = city + s[len(city) + 1:]
            break
    return s

def fetch(url, ua):
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

def load_stations(ua):
    """code_p の現行観測点 (全件) に一覧表HTMLの詳細・高精度座標を重ねて返す。"""
    zf = zipfile.ZipFile(io.BytesIO(fetch(CODE_P_URL, ua)))
    dat = [n for n in zf.namelist() if n.endswith(".dat")][0]
    text = zf.read(dat).decode("utf-8", errors="replace")
    stations = {}
    for line in text.splitlines():
        p = line.split("\t")
        if len(p) < 6 or p[5].strip() != "":
            continue
        name = p[1].replace("＊", "").replace("（旧）", "").strip()
        try:
            lr, lo_r = p[2].strip(), p[3].strip()
            lat = int(lr[:-2]) + int(lr[-2:]) / 60.0
            lon = int(lo_r[:-2]) + int(lo_r[-2:]) / 60.0
        except (ValueError, IndexError):
            continue
        if not name or not (20.0 <= lat <= 46.0 and 122.0 <= lon <= 154.0):
            continue
        stations[normalize_key(name)] = {"name": name, "lat": lat, "lon": lon, "addr": "", "area": ""}
    page = fetch(HTML_URL, ua).decode("utf-8", errors="replace")
    upgraded = 0
    for row in re.findall(r"<tr>(.*?)</tr>", page, re.S):
        cells = [html_mod.unescape(re.sub(r"<[^>]+>", "", c)).strip()
                 for c in re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)]
        if len(cells) < 9 or cells[8]:
            continue
        key = normalize_key(cells[1])
        if key not in stations:
            continue
        try:
            lat = int(cells[3]) + float(cells[4]) / 60.0
            lon = int(cells[5]) + float(cells[6]) / 60.0
        except ValueError:
            continue
        stations[key]["lat"], stations[key]["lon"] = lat, lon
        stations[key]["addr"], stations[key]["area"] = cells[2], cells[0]
        upgraded += 1
    rows = sorted(stations.values(), key=lambda s: s["name"])
    return rows, upgraded

def main() -> int:
    stations, upgraded = load_stations(UA)
    if len(stations) < 3000:
        print(f"観測点が少なすぎます ({len(stations)} 件)", file=sys.stderr)
        return 1
    rows = []
    for s in stations:
        addr = s["addr"] or s["name"]
        if "," in s["name"]:
            continue
        if "," in addr:
            addr = s["name"]
        rows.append((s["name"], addr, round(s["lat"], 5), round(s["lon"], 5)))
    rows.sort(key=lambda r: (r[1], r[0]))
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8", newline="") as f:
        f.write("# 気象庁震度観測点 (出典: 気象庁 震度観測点コード表 code_p.zip "
                + CODE_P_URL + " / 気象庁震度観測点一覧表 " + HTML_URL + ")\n")
        f.write(f"# 生成: tools/gen-intensity-stations.py / 形式: name,addr,lat,lon / 件数: {len(rows)} (うち座標精度補完 {upgraded})\n")
        w = csv.writer(f)
        for r in rows:
            w.writerow(r)
    print(f"{len(rows)} 件を {os.path.normpath(OUT_PATH)} に出力しました (座標精度補完 {upgraded} 件)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
