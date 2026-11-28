# ccspeed 开发约定（所有 agent 必读）

## 分支纪律（2026-11-29 东哥点名，硬规则）

**卡做完必须合入 `main`（或开 PR 并盯到合并）。分支不能「躺着」。**

起因：UI 改版 / 分应用代理 / 保活都做在独立分支没合，CI 只构建 `main`，
东哥装的 APK 把已经做好的界面优化「覆盖」回旧界面 —— 代码没丢，但交付物是旧的。

执行：
```bash
# 接卡第一步：有未合并提交说明上一个活没闭环，先合再干新活
git log --oneline origin/main..HEAD

# 收尾必查：输出必须为空
git log --oneline origin/main..HEAD   # 空 = 已合入 main
```
- 不为空 → 开 PR → 等 CI 绿 → 合入 → 才能把卡置 done。
- **「卡 done」= 代码在 main 上。** 只在自己分支上不算完成。
- 多人并行改同一文件时，合入前 `git fetch && git rebase origin/main` 自己解冲突，别甩给下游。

## 发版

- tag 打 `v*` 才触发 release job（跨平台二进制 + APK 一起发布）。
- Release 产物必须来自 **tag 那次 CI 构建**，不要用更早的 artifact（CMP-442 修的坑）。

## APK 交付

- 固定 debug keystore（`android/keystore/ccspeed-debug.jks`）+ `versionCode` 由 CI 注入 `github.run_number`，
  保证覆盖安装可升级（CMP-441）。
- 内置节点列表（`secrets.go` fallbackNodes / `udp.go` udpNodes）会随 CC 服务端下线而失效，
  改这里之前先跑 `/root/ccspeed/cc-node-sync.py --dry-run`（CT103）确认节点活着。

## 验收

- 出口 IP 只能来自**外部服务**（ifconfig.me / httpbin），`/api/status` 的 node-ip 是节点地址，禁止当出口证据。
- 功能坏 CI 必须变红：`android/avd-verify.sh` 的核心探针是硬门，不要把它改回 WARN。