# -*- coding: utf-8 -*-
"""只读检查：现有部门树 + 张三的角色（诊断'组长无权限'问题）。走 8087 HTTP API。"""
import base64
import json
import os
import sys
import urllib.request

from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import serialization

PRIV_PEM = (
    "-----BEGIN PRIVATE KEY-----\n"
    "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC9XDIDItpuGLlJ5R2BxV0Z8O5oWYIs5LQw"
    "KAqm+Us0+HuWn+VeiPhcRFJJCDOHvAEEgieWrK1hmA5kCr6F1Kegoz6GETvK2jArvuR1tnDjXxfErcvH4l+3"
    "b7mXpLGxkAT3XoEnjpToQrxCkAGr20s/WtQpOjBPKAXLpJaUGX5Fu6VFU9u3ibSAU5tcz96Hlkib3uEuxBwC"
    "5p1DQqpv7TaewXZuZWIG0wwrZiPohHBf1hX43xyOwld5J2xUbAX98a0nbEqr8VcTiuu0sr30ErDUvuu7Y+jq"
    "1kohU4ZmBmetFfojD+8ypJr4WdPGB+w1kNxY6pnwr5qpDth294bO9mRzAgMBAAECggEBAJF3BrBkENpswcIj"
    "cLRlEi1AaVTeFeM42bb4u54jegO6Mu617HTf0bLHhVK3KybFZR66gYD9K8ACGGP/4PZcM11yqjBBguZFEKY6"
    "YbSPr07rmQ2s2RO3MgJvoGn+ycZ2tWn2Pk9N99QomAimKbKEptyHgN4e5keYnkMfL9Gbd+ZGenRoCo+VOsr9"
    "jR11ila2g//KeeV4o5dpUhapwd2HXl9LAwCbN4iUTcTq3jDH8MTmGdnZpWaLqakDp9BdnAAbX3o5CeD6SjHz"
    "QSwUmFZwRUVf4HwQ/+tS6F2mw3x0S216RWfyzmVycaUzTj8dOU3CW8+jSGyBqT+1VYWiAgY0d9ECgYEA5zej"
    "WtNkVwgvk2QiDgiyGNjkzOpQpwCtMUYM+QSGysSstErEdcnfMoe6eYkiFNGgBdl3PLrQJwau27/9Wqa/MzvN"
    "YNTnWPy25aqW66ZOjFTBsIkAa0ViKajtpucIKNvaXSOwlCPoD5yTY4MIIH8zIevQZOeNb1tkHJPvbOGJL0kC"
    "gYEA0agKHXZzPmY8KeH0y7g6xiGS/ooxZgxyB5Zp9UvtqQE5CafE5yd60oTzCW060dOLbgaqRZiuWbFJ0zpI"
    "6ChaYTMYxdvrcQWzL0GPk9NEe3GRT0ruWYbJkfpeIMUezLKfiueAuJ0Vmy3x8AxuO528jH6B7xvoE0Pe0+SU"
    "cQqGadsCgYA0qcCEPGe7RvsHGCSFi8d8z1H1tlzeXNIVyf3EbhqBbqBjhDARIAS9TprTeb+QfFp1Wp3E8Eve"
    "x6/mD2mWTyp3ceSKbJOw+gZycxNi4wM7BUcEfX/h7vC3ymkuvapnHAQ1eJ6Mb0042RHc9YhRVod/72UMxoy5"
    "U1iPBcbfxtLnOQKBgEh6TwTgbfakYSgZdQb4KVlVQfu8ylb89m6pEPg7x20lfxJXbTp763nbfClGGY9wEkN3"
    "CmYE4kEfiOX8wDeBu7zebTH5VOs9jTRI9dmkr4f9Or6uqLdKYWSVqPSrMHqTRZQ/c8BejZmXyIuzwGfbn9Lx"
    "6PlALHp8fAvEeYyalt0BAoGAC9QL212Gipj9x9tIoSpbitMA6+ut7nGEjr0/geSbe2Y0R5qk2QPk5SMPYZ/k"
    "ZBZ0DTcRkIIGJ2x+MAUjn+1CNAggJZlqValgVXZVIkZQHKYxbdKOjI3W/4LSFn/7RHqx1N9oBgZdFmWBJYuR"
    "BrffIgZ9skWurSupHYqTKNA49Hc=\n"
    "-----END PRIVATE KEY-----\n"
)


def rsa_encrypt(password):
    priv = serialization.load_pem_private_key(PRIV_PEM.encode(), password=None)
    return base64.b64encode(priv.public_key().encrypt(password.encode(), padding.PKCS1v15())).decode()


def http(method, url, data=None, token=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def out(s):
    sys.stdout.buffer.write((s + "\n").encode("utf-8"))


def main():
    admin_pwd = os.environ.get("ADMIN_PASSWORD", "")
    r = http("POST", "http://localhost:8087/login", {"username": "admin", "password": rsa_encrypt(admin_pwd)})
    token = r.get("token")
    if not token:
        out("LOGIN FAIL: " + json.dumps(r))
        return

    # 部门树
    depts = http("GET", "http://localhost:8087/system/dept/list", token=token)
    out("== DEPTS ==")
    for d in depts.get("data", []):
        out("  id=%s parent=%s ancestors=%s name=%s" % (d.get("deptId"), d.get("parentId"), d.get("ancestors"), d.get("deptName")))

    # 张三
    users = http("GET", "http://localhost:8087/system/user/list?pageNum=1&pageSize=10&userName=" +
                 urllib.parse.quote("张三"), token=token)
    out("== ZHANGSAN by userName ==")
    for u in users.get("rows", []):
        out("  userId=%s userName=%s nick=%s dept=%s" % (u.get("userId"), u.get("userName"), u.get("nickName"),
                                                          (u.get("dept") or {}).get("deptName")))
        detail = http("GET", "http://localhost:8087/system/user/" + str(u.get("userId")), token=token)
        roles = detail.get("roleIds") or [x.get("roleId") for x in (detail.get("roles") or [])]
        out("  roleIds=%s postIds=%s" % (roles, detail.get("postIds")))

    # 张三组长的课题
    projects = http("GET", "http://localhost:8087/biz/project/list?pageNum=1&pageSize=20", token=token)
    out("== PROJECTS (leader=zhangsan) ==")
    for p in projects.get("rows", []):
        if p.get("leaderName") and "张三" in p.get("leaderName"):
            out("  projectId=%s no=%s name=%s leaderId=%s status=%s" % (
                p.get("projectId"), p.get("projectNo"), p.get("projectName"), p.get("leaderId"), p.get("status")))


if __name__ == "__main__":
    import urllib.parse
    main()
