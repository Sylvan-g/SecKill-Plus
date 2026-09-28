"""
T18 压测准备：批量注册压测用户并缓存 token（经 gateway）。
用法: python tools_pressure_prepare.py 1000
将每个已注册/可登录用户的 token 写入 tokens.txt（每次全量重写，供压测脚本使用）。
"""
import sys
import json
import urllib.request

GATEWAY = "http://localhost:8080"
TOKENS = r"D:\AI_study\SecKill-Plus\scripts-压测\tokens.txt"


def call(url, payload):
    body = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode())


def ensure_user(i):
    """注册（4008 已存在忽略），登录拿 token。"""
    u = "ptest%04d" % i
    try:
        call(GATEWAY + "/api/user/register",
             {"username": u, "password": "123456", "phone": "13900000000", "nickname": u})
    except Exception:
        pass
    try:
        r = call(GATEWAY + "/api/user/login", {"username": u, "password": "123456"})
        if r.get("code") == 0:
            return r["data"]["token"]
    except Exception:
        pass
    return None


def main(n):
    tokens = []
    missing = 0
    for i in range(1, n + 1):
        t = ensure_user(i)
        if t:
            tokens.append(t)
        else:
            missing += 1
    with open(TOKENS, "w", encoding="utf-8") as f:
        f.writelines(t + "\n" for t in tokens)
    print("tokens saved=%d missing=%d" % (len(tokens), missing))


if __name__ == "__main__":
    main(int(sys.argv[1]))