"""
T18 边界验证：重抢(R5)、支付、重复支付拦截、401。
场景: ptest0110 充值 -> 秒杀 69201(库存50限购1) -> 主动取消 -> 重抢成功 -> 支付成功 -> 重复支付被拦。
"""
import json
import urllib.request
import urllib.error

GW = "http://localhost:8080"

def call(method, path, payload=None, token=None):
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(GW + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        # HTTP 层 4xx（如未登录 401）仍带业务 JSON body，读出来
        return json.loads(e.read().decode())


def login(u, pwd="123456"):
    r = call("POST", "/api/user/login", {"username": u, "password": pwd})
    assert r.get("code") == 0, r
    return r["data"]["token"]


def main():
    # 401 未登录
    r = call("POST", "/api/seckill/do/69201", {})
    print("seckill-no-token -> code=%s (期望401)" % r.get("code"))

    t = login("ptest0110")
    # 充值 500
    r = call("POST", "/api/user/wallet/recharge", {"amount": 500}, t)
    print("recharge -> code=%s balance=%s" % (r.get("code"), r["data"]["balance"]))

    # 首次秒杀
    r = call("POST", "/api/seckill/do/69201", {}, t)
    print("seckill#1 -> code=%s" % r.get("code"))
    on = r["data"]["orderNo"]
    # 重复点击（同用户再点）
    r2 = call("POST", "/api/seckill/do/69201", {}, t)
    print("seckill#2 dup -> code=%s (期望4002)" % r2.get("code"))

    # 主动取消
    r = call("POST", "/api/order/cancel/" + on, {}, t)
    print("cancel -> code=%s" % r.get("code"))
    # 取消后可重抢（R5）
    r = call("POST", "/api/seckill/do/69201", {}, t)
    print("seckill#3 retry-after-cancel -> code=%s (期望0 重抢成功)" % r.get("code"))
    on = r["data"]["orderNo"]

    # 支付成功
    r = call("POST", "/api/order/pay/" + on, {}, t)
    print("pay#1 -> code=%s paid=%s bal=%s" % (r.get("code"),
          r["data"].get("paidAmount"), r["data"].get("balanceAfter")))
    # 重复支付（应 4006 非待支付）
    r = call("POST", "/api/order/pay/" + on, {}, t)
    print("pay#2 dup -> code=%s (期望4006)" % r.get("code"))


if __name__ == "__main__":
    main()