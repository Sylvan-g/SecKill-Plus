"""
T18 并发压测：1000 并发秒杀活动 69202（库存 100、限购 1）。
经 gateway(8080) 打 /api/seckill/do/69202，统计响应码分布与耗时。
用法: python pressure_seckill.py 1000
"""
import sys
import time
import json
import threading
import urllib.request

from collections import Counter

GATEWAY = "http://localhost:8080"
ACTIVITY = 69202
TOKENS = r"D:\AI_study\SecKill-Plus\scripts-压测\tokens.txt"

codes = Counter()
errs = []
lat_total = 0.0
lock = threading.Lock()
start_wall = time.time()


def worker(token):
    global lat_total
    body = b"{}"
    req = urllib.request.Request(
        GATEWAY + "/api/seckill/do/%d" % ACTIVITY,
        data=body, headers={"Content-Type": "application/json",
                            "Authorization": "Bearer " + token}, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            res = json.loads(resp.read().decode())
            code = res.get("code", -1)
    except Exception as e:
        code = "EXC"
        with lock:
            errs.append(str(e)[:80])
    lat = (time.time() - t0) * 1000
    with lock:
        codes[code] += 1
        lat_total += lat


def main(n):
    with open(TOKENS, encoding="utf-8") as f:
        tokens = [line.strip() for line in f if line.strip()]
    tokens = tokens[:n]
    threads = []
    for t in tokens:
        th = threading.Thread(target=worker, args=(t,))
        threads.append(th)
        th.start()
    for th in threads:
        th.join()
    wall = time.time() - start_wall
    total = sum(codes.values())
    print("=== seckill pressure result ===")
    print("requests=%d wall=%.2fs avg_qps=%.0f" % (total, wall, total / wall))
    for code, cnt in sorted(codes.items(), key=lambda kv: -kv[1]):
        print("  code %s : %d" % (code, cnt))
    if errs:
        print("err sample:", errs[:3])
    avg = lat_total / total if total else 0
    print("avg_lat=%.1fms" % avg)


if __name__ == "__main__":
    main(int(sys.argv[1]))