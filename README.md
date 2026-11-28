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

`-api-base` 走默认 API 路径拉节点时**必须**带 `CC_AUTH`/`CC_COMM`（CC 加速器
账号登入态），否则启动即退出（日志 `api returned no nodes`；这是「不留凭据进
仓」与「开箱即用」的取舍，非缺陷）。零凭据可用路径：`-node host:port` 固定节点，
或 `-api-base '' -nodes-file nodes.txt` 本地列表。

### 全部参数

```text
-listen       SOCKS5 监听地址        默认 127.0.0.1:1081
-web          Web 控制台监听地址     默认 127.0.0.1:28981（空=禁用）支持 host:port 与 :port 两种形态；端口须在 0-65535
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

## 5b. Android 薄壳 APK（`android/`）

装上即一个「本地 CC 加速器」：APK 只做**启停 + VpnService 流量接管 +
打开 Web 控制台**，真正的节点客户端是 APK 内嵌的 `ccspeed-linux-arm64`
（真机）或 `ccspeed-linux-amd64`（模拟器 / x86_64 设备），与 Release 的
`ccspeed-v1.0.1` 同一产物，sha256 见 CI 的
`Verify embedded binaries` 步骤。凭据填写、节点选择、日志、切换
SOCKS5 端口——**全在 Web 控制台完成，Kotlin 壳不复制任何业务 UI**。

### 用法

1. **安装**：CI 的 `android` job 产出 `app-debug.apk`（Release 附件）。
   `adb install app-debug.apk`
2. **授权**：点「启动」，系统弹 VpnService 授权对话框，同意。
3. **接管**：壳建 TUN 并拉起 tun2socks（[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
   2.20.0 的 `libhev-socks5-tunnel.so`，arm64 + x86_64 两套 JNI），把全流量转发到
   `127.0.0.1:1081` 的 SOCKS5。TCP 走 CONNECT，DNS 与 UDP 走
   UDP ASSOCIATE（`udp.go` 实测支持）。
4. **填凭据**：点「打开控制台」，手机浏览器开
   `http://127.0.0.1:28981/`，在表单里填 CC API 凭据
   （写 `ccspeed.json` 到 app-internal，权限 600）。
   没有独立浏览器时壳内兜底 WebView 打开控制台。
5. **停止**：点「停止」或下拉通知栏「停止」。VpnService 关闭、native
   进程退出、端口释放。

### 内嵌二进制与 ABI 选择

assets 里带**两套**未压缩 ELF，`CcspeedRuntime` 启动时按
`Build.SUPPORTED_ABIS` 解压对应的一份执行：

| 设备/模拟器 ABI | 选用二进制 | 备注 |
|---|---|---|
| `arm64-v8a` | `ccspeed-linux-arm64` | 真机（含 Termux 生态同款形态） |
| `x86_64` | `ccspeed-linux-amd64` | CI AVD 与 x86_64 Android 设备 |
| 其他 | 回落 `ccspeed-linux-arm64` | 会 `exec format error`，壳不兜底 |

用的是 `GOOS=linux` 静态 ELF 而不是 `GOOS=android` tag：Termux / v2rayNG /
Xray for Android 生态实证静态 linux ELF 能在 Android 直接 exec，
`android` tag 只服务 gomobile c-shared，这边不需要。assets 必须先解压到
`context.filesDir` 再 `chmod 700` + `ProcessBuilder` exec（同 v2rayNG 姿势），
SELinux 无需 root。

`libhev-socks5-tunnel.so` 同样按 JNI 库规则进 `jniLibs/<abi>/`：上游
`hev-socks5-tunnel.aar` 四 ABI（arm64-v8a / armeabi-v7a / x86 / x86_64）
用同一套 `RegisterNatives` 路径注册 `hev/htproxy/TProxyService`，所以 x86_64
上同一个 Kotlin JNI 声明直接可用，无需为 AVD 另写一层。

### 架构

```text
apps ──> TUN (VpnService) ──> hev-socks5-tunnel (libhev-socks5-tunnel.so)
                                   │
                                   ▼
                          127.0.0.1:1081 (SOCKS5)
                                   │
                                   ▼
                    ccspeed-linux-<abi> (assets 解压执行)
                                   │
                                   ▼
                              CC 节点 + Web 控制台 127.0.0.1:28981
```

### 验收：CI 内 Android 模拟器（真机暂不可达）

真机 `192.168.1.157` 暂不可达，装机验收降级到 **CI 内 AVD**：
`.github/workflows/build.yml` 的 `android-emulator` job 跑
`reactivecircus/android-emulator-runner`，AVD = API 34 / x86_64 / google_apis
（GitHub ubuntu runner 自带 KVM 硬件加速），链路：

```text
android job 产出 APK（libccspeed.so 从源码重建）→ boot AVD → adb install → 启动
  → uiautomator dump + input tap 触发 VpnService 授权链
  → adb logcat 抓壳的 ABI banner 与 ccspeed 的 web console banner
  → 硬门①tun0 接管 ②native 进程存活（libccspeed.so）③SOCKS5 127.0.0.1:1081 监听
  → 硬门④经 SOCKS5 从外部服务取真实出口 IP，且 ≠ 直连出口
  → 停止 → 进程/tun 释放 → APK 零凭据复核
```

**硬门与状态**：①~③ 任一失败 `exit 1`，CI 直接红；④ 只有在 runner 自身
无外网、或日志里选中的节点从 runner TCP 不可达时才显式 `RESULT: SKIP`
（永不打印 PASS）。`/api/status` 的 node-ip 是节点地址，不是出口 IP，
禁止作为隧道通网的证据。门禁逻辑自测：
`android/test-avd-verify.sh`（假 adb/curl，8 场景，秒级）。

资产验收判据同步在 CI 里收口：assets 按 ABI 选对（AVD 上跑 amd64）、
amd64 target 单独进 Release、CC_AUTH/CC_COMM 凭据形状 zero-hit。

**验收证据模板（每条判据贴原始命令输出）**

```text
A1 装机   : adb install -r app-debug.apk -> Success
A2 启动   : adb logcat -d -s ccspeed-runtime -> native binary verified / listen=127.0.0.1:1081
A3 控制台 : curl -s -m 5 -w '%{http_code}' http://127.0.0.1:28981/ -> 200
A4 出口   : 直连基线(外部服务)=<IP-A>；经 SOCKS5(外部服务)=<IP-B>；IP-B 有效且 ≠ IP-A
            出口 IP 来源：外部服务（ifconfig.me/ip）/ 非 node-ip
            node-ip(/api/status)=<IP-N> 仅作配置值，不作出口证据
A5 停止   : adb shell ip -o addr show tun0 -> 空；ps -A | grep libccspeed.so -> 空
A6 凭据   : APK credential-shape hits = 0
```

**真机回归项（收口后 backlog 单列）**：NAT 类型、长时保活、真 4G/5G 网络
下的 CC API——模拟器只能证明「壳 + 转译链 + 控制台 + TUN 接管 + 真实出口」
成立，不代表真机三项 PASS。

### 本地构建

```bash
cd android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求 JDK 17、Android SDK 34（CI 用
`actions/setup-java` + `gradle/actions/setup-gradle`）。

### 安全边界

- APK **不打包任何 CC 凭据**；assets 的 `ccspeed-linux-arm64` 与
  v1.0.1 Release sha256 一致，`ccspeed-linux-amd64` 由 CI 从同 commit
  交叉编译（`go build -trimpath -ldflags='-s -w'`），CI 每次都核对
  （`grep -aoE 'CC_AUTH=…'` zero-hit）。
- SOCKS5 只监听 `127.0.0.1`，Web 控制台只监听 `127.0.0.1`，壳不开对外端口。

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