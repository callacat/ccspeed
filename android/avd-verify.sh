#!/usr/bin/env bash
# CMP-410/CMP-442 AVD 验收链：装机 → VpnService 授权 → 硬门判据 → 停止释放 → 零凭据复核。
# 在 CI 里由 reactivecircus/android-emulator-runner 的 script: 调起（adb 已在 PATH）。
# 真机 192.168.1.157 暂不可达 ⇒ 降级到 AVD；真机回归三项单列 backlog。
#
# CMP-442：核心探针从 WARN 升级为硬门（任一项失败 exit 1），并加一条
# 「经 SOCKS5 从外部服务取到真实出口 IP 且 ≠ 直连出口」的硬门。
# 出口 IP 只能来自外部服务；/api/status 的 node-ip 是节点地址，禁止当出口证据。
set -uo pipefail

# 自测用：test-avd-verify.sh 置 AVD_VERIFY_FAST=1 跳过所有等待。
[ "${AVD_VERIFY_FAST:-0}" = "1" ] && sleep() { :; }

PKG=com.callacat.ccspeed
APK=${APK_PATH_OVERRIDE:-app/build/outputs/apk/debug/app-debug.apk}
EGRESS_URL=${EGRESS_URL:-https://ifconfig.me/ip}

say() { echo "=== $* ==="; }

# CMP-446 UI 验收：控制台打开/返回 + 深/浅色主界面截图（产物随 CI artifact 上传）。
SHOTS="$(cd "$(dirname "$0")" && pwd)/screenshots"
mkdir -p "$SHOTS"

set_night() { adb shell cmd uimode night "$1" >/dev/null 2>&1 || true; }

capture_main() {
    local mode="$1" name="$2"
    set_night "$mode"
    adb shell am force-stop "$PKG" >/dev/null 2>&1
    adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 3
    adb exec-out screencap -p > "$SHOTS/$name.png"
    echo "screenshot: $SHOTS/$name.png ($(stat -c%s "$SHOTS/$name.png" 2>/dev/null || echo 0) bytes)"
    adb shell uiautomator dump /sdcard/du.xml >/dev/null 2>&1
    adb pull /sdcard/du.xml "$SHOTS/$name.xml" >/dev/null 2>&1
    echo "ui dump: $SHOTS/$name.xml"
}

FAILURES=()
fail() { echo "FAIL: $*"; FAILURES+=("$*"); }

is_ip() {
    local v=$1
    [[ "$v" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] && return 0
    [[ "$v" == *:* && "$v" =~ ^[0-9a-fA-F:]+$ ]]
}

# 只认 ccspeed 原生可执行（libccspeed.so / ccspeed-linux-*）。
# 不能 grep 'ccspeed|hev'：Kotlin 壳进程 com.callacat.ccspeed 也含 ccspeed，
# native 死了壳还在，旧脚本因此误判 PASS（run 45923510680 实锤）。
native_pids() {
    adb shell "ps -A 2>/dev/null | grep -E 'libccspeed\.so|ccspeed-linux' | grep -v grep" | tr -d '\r'
}

# SOCKS5 监听：同时看 IPv4/IPv6 的 /proc/net/tcp{,6}，端口 1081 = 0x0439。
socks5_listen() {
    adb shell "grep -i ':0439 ' /proc/net/tcp /proc/net/tcp6 2>/dev/null" | tr -d '\r'
}

tun0_up() {
    adb shell "ip -o addr show tun0 2>/dev/null" | tr -d '\r'
}

tap_via_uiautomator() {
    local dump="$1"; shift
    adb shell uiautomator dump "/sdcard/${dump}" >/dev/null
    adb pull "/sdcard/${dump}" "/tmp/${dump}" >/dev/null
    python3 - "$dump" "$@" <<'PY'
import re, subprocess, sys
dump = sys.argv[1]
xml = open(f'/tmp/{dump}').read()
for txt in sys.argv[2:]:
    m = re.search(
        rf'text="{re.escape(txt)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        print(f"tap {txt} at ({x},{y})")
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
        sys.exit(0)
print(f"button not found among {sys.argv[2:]}")
sys.exit(1)
PY
}

say "adb devices"
adb devices

say "wait-for-device"
adb wait-for-device

say "unlock + 保持唤醒"
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

say "install APK"
adb install -r "$APK" || fail "APK 安装失败"

say "launch MainActivity"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 5

say "tap 启动 -> VpnService.prepare 弹窗（uiautomator 起不来时退回 wakeup + 屏幕中心 tap）"
if ! tap_via_uiautomator ui.xml "启动" "Start"; then
    echo "WARN: uiautomator 首次未命中，用中心坐标兜底"
    SIZE=$(adb shell wm size | head -1 | awk '{print $NF}')
    echo "wm size=$SIZE"
    adb shell input keyevent KEYCODE_WAKEUP
    W=$(echo "${SIZE%%x*}")
    H=$(echo "${SIZE##*x}")
    adb shell input tap $((W/2)) $((H/3))
fi
sleep 2

say "tap 授权对话框（OK/确定/允许/Allow） — 最多重试 5 次，每次 2 秒"
AUTH_TAPPED=0
for i in {1..5}; do
    if tap_via_uiautomator "ui2_${i}.xml" "OK" "确定" "允许" "Allow"; then
        echo "Authorization button tapped at attempt $i"
        AUTH_TAPPED=1
        break
    fi
    echo "attempt $i: 授权按钮未找到，等待 2 秒..."
    sleep 2
done
if [ "$AUTH_TAPPED" = "0" ]; then
    echo "ERROR: 授权对话框按钮未找到，可能授权弹窗未出现或文案不匹配"
    SIZE=$(adb shell wm size | head -1 | awk '{print $NF}')
    W=$(echo "${SIZE%%x*}")
    H=$(echo "${SIZE##*x}")
    adb shell input tap $((W/2)) $((H/2))
fi
sleep 3

say "logcat banner / ABI / TUN 判据"
adb logcat -d -s ccspeed-runtime:D ccspeed-vpn:D ccspeed-main:D | tail -100
echo "--- full logcat grep (ccspeed / VpnService / native) ---"
adb logcat -d | grep -iE "ccspeed|VpnService|libccspeed|nativeLibraryDir|Permission denied|avc:.*denied|AndroidRuntime" | tail -60
echo "--- drain ccspeed stdout (任务 banner / relay) ---"
adb logcat -d | grep -E "ccspeed-linux|web console|listen=|relay |selected fastest|nodes loaded" | tail -30

say "分应用路由判据（默认全量必须打印 route mode=full allowed=0）"
ROUTE_LOG="$(adb logcat -d | grep -E 'ccspeed-vpn.*route mode=' | tail -5)"
if echo "$ROUTE_LOG" | grep -q .; then
    echo "$ROUTE_LOG"
    if echo "$ROUTE_LOG" | grep -q "route mode=full allowed=0"; then
        echo "OK: 默认全量路由（白名单未开启，无回归）"
    else
        echo "WARN: 默认模式不是 full/0，请确认 shared_prefs/perapp.xml"
    fi
else
    echo "WARN: 未见 route mode 日志（旧版 APK 或 VPN 未起）"
fi

say "核心硬门：tun0 / native 进程 / SOCKS5 127.0.0.1:1081（最多等待 60s 启动）"
TUN0=""; NATIVE=""; SOCKS=""
for i in {1..30}; do
    TUN0=$(tun0_up)
    NATIVE=$(native_pids)
    SOCKS=$(socks5_listen)
    if [ -n "$TUN0" ] && [ -n "$NATIVE" ] && [ -n "$SOCKS" ]; then
        echo "core gates ready after $((i * 2))s"
        break
    fi
    sleep 2
done
echo "--- tun0 ---";    echo "${TUN0:-<none>}"
echo "--- native ---";  echo "${NATIVE:-<none>}"
echo "--- socks5 ---";  echo "${SOCKS:-<none>}"
[ -n "$TUN0" ]   || fail "tun0 未接管（ip -o addr show tun0 为空）"
[ -n "$NATIVE" ] || fail "ccspeed native 进程未存活（未见 libccspeed.so / ccspeed-linux）"
[ -n "$SOCKS" ]  || fail "SOCKS5 127.0.0.1:1081 未监听（/proc/net/tcp{,6} 无 :0439）"

EGRESS_SKIPPED=0
DIRECT_IP=""
say "硬门：经 SOCKS5 从外部服务取真实出口 IP（$EGRESS_URL）"
if ! command -v curl >/dev/null 2>&1; then
    fail "runner 无 curl，无法验证真实出口"
fi
for i in 1 2 3; do
    DIRECT_IP="$(curl -s -m 20 "$EGRESS_URL" 2>/dev/null | tr -d '\r\n ')"
    is_ip "$DIRECT_IP" && break
    echo "direct egress attempt $i failed (${DIRECT_IP:-empty})"
    sleep 3
done
echo "直连出口基线 (runner/AVD NAT): ${DIRECT_IP:-<empty>}"

if ! is_ip "$DIRECT_IP"; then
    echo "SKIP: 直连出口不可达 —— 模拟器环境无外网，出口门本次跳过（不得记为 PASS）"
    EGRESS_SKIPPED=1
elif [ -z "$SOCKS" ]; then
    fail "SOCKS5 未监听，无法验证真实出口"
else
    TUNNEL_IP=""
    # 优先设备内 curl（问题单指定姿势）；AVD 无 curl 时经 adb forward 用 runner curl。
    USE_DEVICE_CURL=0
    if adb shell "command -v curl >/dev/null 2>&1" >/dev/null 2>&1; then
        USE_DEVICE_CURL=1
        echo "device curl available"
    else
        echo "device curl absent -> adb forward tcp:1081 + runner curl --socks5-hostname"
        adb forward tcp:1081 tcp:1081 >/dev/null 2>&1
    fi
    # 节点选路 + 首连可能要几十秒，给 75s 窗口；期间任一时刻取到有效 IP 即过。
    for i in {1..15}; do
        if [ "$USE_DEVICE_CURL" = "1" ]; then
            TUNNEL_IP=$(adb shell "curl -s -m 20 -x socks5h://127.0.0.1:1081 '$EGRESS_URL'" 2>/dev/null | tr -d '\r\n ')
        else
            TUNNEL_IP=$(curl -s -m 20 --socks5-hostname 127.0.0.1:1081 "$EGRESS_URL" 2>/dev/null | tr -d '\r\n ')
        fi
        is_ip "$TUNNEL_IP" && break
        echo "socks5 egress attempt $i/15 failed (${TUNNEL_IP:-empty})"
        sleep 5
    done
    [ "$USE_DEVICE_CURL" = "1" ] || adb forward --remove tcp:1081 >/dev/null 2>&1
    echo "经 SOCKS5 出口 IP: ${TUNNEL_IP:-<empty>}"
    if ! is_ip "$TUNNEL_IP"; then
        # 区分「环境到节点不通」与「App 假通」：runner 与 AVD 同 NAT，
        # runner TCP 都连不上节点 ⇒ 环境限制，显式 SKIP；能连上但取不到数据 ⇒ 硬失败。
        NODE_ADDR=$(adb logcat -d 2>/dev/null | grep -oE 'node +: [0-9.]+:[0-9]+' | tail -1 | grep -oE '[0-9.]+:[0-9]+')
        if [ -n "$NODE_ADDR" ] && ! timeout 6 bash -c "exec 3<>/dev/tcp/${NODE_ADDR%:*}/${NODE_ADDR#*:}" 2>/dev/null; then
            echo "SKIP: 节点 $NODE_ADDR 从 runner 不可达 —— 环境限制，出口门 SKIP（不计 PASS）"
            EGRESS_SKIPPED=1
        else
            echo "runner 到节点 ${NODE_ADDR:-unknown} 可达（或未解析到节点）"
            fail "经 SOCKS5 取出口 IP 失败（TCP 假通/数据零返回）"
        fi
    elif [ "$TUNNEL_IP" = "$DIRECT_IP" ]; then
        fail "经 SOCKS5 的出口 IP 与直连相同（$TUNNEL_IP），未真正经隧道出网"
    else
        echo "PASS: 隧道出口 $TUNNEL_IP ≠ 直连 $DIRECT_IP"
    fi
fi

say "Web 控制台（127.0.0.1:28981）——仅 WARN"
adb forward tcp:28981 tcp:28981 >/dev/null 2>&1
WEB_CODE=$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:28981/ 2>/dev/null)
adb forward --remove tcp:28981 >/dev/null 2>&1
if [ "$WEB_CODE" = "200" ]; then
    echo "web console OK (HTTP 200)"
else
    echo "WARN: web console (28981) 未就绪（HTTP ${WEB_CODE:-none}）——仅 WARN"
fi

say "停止释放（信息项，不作硬门）"
adb shell am stopservice -n "$PKG/.CcspeedVpnService" || true
sleep 4
echo "--- after stop: native/tun0 ---"
adb shell "ps -A | grep -E 'libccspeed\.so|ccspeed-linux' || echo 'OK: no ccspeed native process'"
adb shell "ip -o addr show tun0 || echo 'OK: tun0 gone'"

say "APK assets 零凭据复核"
APK_INSTALLED="$(adb shell pm path "$PKG" 2>/dev/null | head -1 | sed 's/^package://' | tr -d '\r')"
echo "installed apk=$APK_INSTALLED"
if [ -n "$APK_INSTALLED" ]; then
    adb pull "$APK_INSTALLED" /tmp/installed.apk >/dev/null 2>&1 || true
    if [ -f /tmp/installed.apk ]; then
        HIT="$(python3 - <<'PY'
import re
try:
    data = open('/tmp/installed.apk', 'rb').read()
    hits = re.findall(rb'CC_AUTH=[A-Za-z0-9_-]{16,}|CC_COMM=[A-Za-z0-9_-]{16,}', data)
    print(len(hits))
except Exception:
    print(0)
PY
)"
    echo "credential-shape hits in APK = $HIT"
    if [ "$HIT" != "0" ]; then
        echo "WARN: APK 命中凭据形状（仅 WARN）"
    fi
else
    echo "WARN: 无法拉取已安装 APK 进行凭据复核"
fi
else
    echo "WARN: 无法获取已安装 APK 路径"
fi

say "CMP-446 控制台打开 / 返回"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 3
CONSOLE_OK=0
for i in 1 2 3; do
    tap_via_uiautomator "ui_console_${i}.xml" "控制台" || true
    sleep 2
    RESUMED="$(adb shell dumpsys activity activities | grep -E "ResumedActivity" | head -2)"
    echo "resumed[$i]: $RESUMED"
    if echo "$RESUMED" | grep -q "ConsoleActivity"; then CONSOLE_OK=1; break; fi
done
if [ "$CONSOLE_OK" = "1" ]; then
    adb exec-out screencap -p > "$SHOTS/console.png"
    echo "screenshot: $SHOTS/console.png ($(stat -c%s "$SHOTS/console.png" 2>/dev/null || echo 0) bytes)"
    adb shell input keyevent KEYCODE_BACK
    sleep 1
    BACK_STATE="$(adb shell dumpsys activity activities | grep -E "ResumedActivity" | head -1)"
    echo "after back: $BACK_STATE"
    echo "$BACK_STATE" | grep -q "MainActivity" \
        && echo "console back -> MainActivity OK" \
        || echo "WARN: 返回后未见 MainActivity"
else
    echo "WARN: 控制台未打开（点击未生效），返回判定跳过"
fi

say "结果"
if [ "${#FAILURES[@]}" -gt 0 ]; then
    echo "RESULT: FAIL（${#FAILURES[@]} 项硬门未过）"
    for f in "${FAILURES[@]}"; do echo "  - $f"; done
    exit 1
fi
if [ "$EGRESS_SKIPPED" = "1" ]; then
    echo "RESULT: SKIP（核心 3 门通过；出口门 SKIP：环境无外网，未验证真实出口，不计 PASS）"
    exit 0
fi
echo "RESULT: PASS（核心 3 门 + SOCKS5 真实出口均通过）"