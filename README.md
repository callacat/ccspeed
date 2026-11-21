# ccspeed

纯 Go 单包 stdlib、零第三方依赖的 CC 加速器 SOCKS5 客户端 + 内嵌 Web 控制台。
启动时从客户端 API 随机采样节点，并发测延迟选最快；Web 控制台可以改凭据、
选节点、看日志、开 SOCKS5 用户名口令鉴权。交叉编译 Windows / Linux /
Linux-ARM64（Termux Android）零障碍，不需要 cgo。

## 1. 文件

```text
main.go      # API 拉节点、测速、SOCKS5 TCP、state 运行时状态
udp.go       # SOCKS5 UDP ASSOCIATE
secrets.go   # 默认凭据（默认空，勿提交本地值）
web.go       # 内嵌 Web 控制台 + 6 个 API + 配置文件读写
ui.go        # 内嵌单页 uiHTML（原生 fetch，零 JS 依赖）
go.mod
```

## 2. 编译

需要 Go 1.22+。

```bash
go build -o ccspeed .
```

交叉编译（CI 里也做，见 `.github/workflows/build.yml`）：

```bash
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -o ccspeed.exe .
CGO_ENABLED=0 GOOS=linux   GOARCH=amd64 go build -o ccspeed-linux-amd64 .
CGO_ENABLED=0 GOOS=linux   GOARCH=arm64 go build -o ccspeed-linux-arm64 .
```

## 3. 启动

```bash
./ccspeed -listen 127.0.0.1:1081
```

启动后会自动打开浏览器访问 `http://127.0.0.1:28981`（Web 控制台可以关，
服务在后台继续跑；关 tab ≠ 停服务）。

首次使用需要在控制台或环境变量里提供 API 凭据：

```bash
export CC_AUTH='...'      # Authorization 头（等价 -api-auth）
export CC_COMM='...'      # comm 头（等价 -api-comm）
```

凭据也可以由控制台表单写入 `<可执行文件>.json`（权限 600，路径见控制台
「凭据 / 控制台端口」卡片底部）。

### 全部参数

```text
-listen       SOCKS5 监听地址        默认 127.0.0.1:1081
-web          Web 控制台监听地址     默认 127.0.0.1:28981（空=禁用）
-socks-user   SOCKS5 用户名          默认空 = no-auth（保持原行为）
-socks-pass   SOCKS5 口令            默认空 = no-auth
-node         固定 CC 节点 host:port 空 = API 拉取+测速选最快
-api-base     CC API 基址            空 = 用 -nodes-file 本地列表
-api-game-id / -api-group-id / -api-group-min / -api-group-max
-api-scan-groups 采样 group 数        默认 30
-rotate       每连接随机节点            默认 false
-id           隧道 client id          默认随机 9 字符
-udp-node     UDP 网关               空 = 内置随机
-nodes-file   本地节点文件路径        -api-base '' 时使用
-quiet        隐藏日志                默认 true（Web 控制台始终可见）
```

## 4. Web 控制台

控制台默认只监听 `127.0.0.1`，不对外。API 一览：

```text
GET  /api/status   listen/node/latency/候选数/SOCKS5 是否需鉴权
GET  /api/nodes    候选节点列表（addr/name/group 分组）
POST /api/select   {"addr":"host:port"}  切换节点（只影响新连接，老连接不断）
GET  /api/logs     最近 200 行日志
GET  /api/config   凭据是否设置、控制台端口、配置文件路径
POST /api/config   写凭据/SOCKS5 账号/控制台端口到 ccspeed.json
GET  /             单页控制台
```

节点列表按 name 关键字映射到地区分组；节点元数据只有 name 字段，
**name 地域不等于真实出口**，请以实测延迟为准。

## 5. 使用代理

```bash
# 默认 no-auth
curl --socks5-hostname 127.0.0.1:1081 https://example.com/

# 配置了 SOCKS5 用户名口令后
curl --socks5-hostname user:pass@127.0.0.1:1081 https://example.com/
```

浏览器代理填 SOCKS5 `127.0.0.1:1081`；开启鉴权时同时填用户名口令。

SOCKS5 UDP ASSOCIATE 与 TCP CONNECT 都支持；UDP 走内置 UDP 网关。

## 6. Windows 未签名提示

CI 产出的二进制**没有代码签名证书**。首次运行 Windows 10/11 SmartScreen
会提示「已阻止未知应用」：点「更多信息」→「仍要运行」。这是免费分发
必然的摩擦，不是安全问题；可在文件属性里勾选「解除锁定」后不再提示。

## 7. 停止

```bash
kill "$(cat /tmp/ccspeed.pid)"   # 或直接关终端
```

## 8. 注意

- `secrets.go` 只留空默认值；本地凭据一律走环境变量 / Web 控制台
  写入的 `<exe>.json`（600 权限，**在 `.gitignore` 里，绝不提交**）。
- 节点来自客户端 API，不写本地文件（除非用 `-nodes-file`）。
- `udp.go` 协议逻辑、`sendConnect`、`xorConn` 属于 CC 隧道协议，别改。
- 只支持 TCP 和 SOCKS5 UDP ASSOCIATE；Google 等目标受节点出口策略限制。
- 节点选择和 API 返回有关，账号/地区/VIP 不同可能不同。