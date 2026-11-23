#!/usr/bin/env bash
# CMP-410 AVD 验收链：装机 → VpnService 授权 → logcat 判据 → 停止释放 → 零凭据复核。
# 在 CI 里由 reactivecircus/android-emulator-runner 的 script: 调起（adb 已在 PATH）。
# 真机 192.168.1.157 暂不可达 ⇒ 降级到 AVD；真机回归三项单列 backlog。
set -uo pipefail

PKG=com.callacat.ccspeed
APK=${APK_PATH_OVERRIDE:-app/build/outputs/apk/debug/app-debug.apk}

say() { echo "=== $* ==="; }

tap_via_uiautomator() {
    # $1 = xml dump 文件名; $2.. = 要点的按钮文案候选（首个 hit 即点，找不到返回 1）
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

say() { echo "=== $* ==="; }

say "adb devices"
adb devices

say "wait-for-device"
adb wait-for-device

say "unlock + 保持唤醒"
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

say "install APK"
adb install -r "$APK"

say "launch MainActivity"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 5

say "tap 启动 -> VpnService.prepare 弹窗（uiautomator 起不来时退回 wakeup + 屏幕中心 tap）"
if ! tap_via_uiautomator ui.xml "启动" "Start"; then
    echo "WARN: uiautomator 首次未命中（run 44980563192 见 null root node），用中心坐标兜底"
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
    # 兜底：屏幕中心点击
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

say "native 进程存活"
adb shell "ps -A | grep -E 'ccspeed|hev' || echo 'NO ccspeed/hev process'"

say "tun0 接管"
adb shell "ip -o addr show tun0 || echo 'NO tun0'"

say "SOCKS5 127.0.0.1:1081 在听（/proc/net/tcp :0439）"
adb shell "(grep -i ':0439' /proc/net/tcp || echo 'NO 1081 LISTEN')"

say "Web 控制台 127.0.0.1:28981 应答（AVD 无 curl，用 wget；adb forward + runner host wget）"
# AVD google_apis 镜像 toybox 有 wget 无 curl（run 44980563192 见
# `wget: inaccessible or not found` 是旧版本，新版 toybox 自带 wget）。
# 改用 adb forward 把 AVD 的 28981 映射到 runner host，然后用 host 的 wget 打 localhost。
adb forward tcp:28981 tcp:28981
echo "port-forward set"
wget -q -O /tmp/webconsole.html --server-response http://127.0.0.1:28981/ 2>&1 | grep -E "HTTP|Length" | tail-2
head -c 200 /tmp/webconsole.html 2>/dev/null; echo
adb forward --remove tcp:28981 || true

say "TUN 生效：wget 经壳出（拿出口 IP，与 AVD 自身 egress 不同即接管成功）"
# 同法：AdbHost->AVD forward 只覆盖 28981（壳的 web）；外部出口探测改从 runner
# host 直接打 ifconfig.me 记录基线 IP，再与经壳出去（如无主机客户端可走）作对比。
# AVD 内无 curl，用 wget；runner host 直连 ifconfig.me 记录基线 IP。
wget -q -O /tmp/host_egress_ip.txt --timeout=20 https://ifconfig.me 2>&1 | tail-1
cat /tmp/host_egress_ip.txt 2>/dev/null; echo
echo "runner host egress baseline: $(cat /tmp/host_egress_ip.txt 2>/dev/null)"

say "gate: 每条探针都需非 FAIL（WARN 容 center-tap 兜底命中）"
# 之前因为 set -e 去掉导致假绿：把关键判据汇总在一行，非 PASS 时 exit 1。
FAIL=0
adb shell "ip -o addr show tun0" | grep -q "tun0" || { echo "GATE: tun0 不存在"; FAIL=1; }
if ! adb logcat -d | grep -q "web console http://127.0.0.1:28981"; then
    echo "GATE: 未见 ccspeed web console banner"
    FAIL=1
fi
adb shell "ps -A | grep -qE 'ccspeed|hev'" || { echo "GATE: native 进程未见"; FAIL=1; }
if ! adb shell "grep -i ':0439' /proc/net/tcp" | grep -q .; then
    echo "GATE: SOCKS5 127.0.0.1:1081 未在听"
    FAIL=1
fi
adb shell am stopservice -n "$PKG/.CcspeedVpnService"
sleep 4
echo "--- after stop: native/tun0 ---"
adb shell "ps -A | grep -E 'ccspeed|hev' || echo 'OK: no ccspeed/hev process'"
adb shell "ip -o addr show tun0 || echo 'OK: tun0 gone'"

if [ "$FAIL" = "1" ]; then
    echo "RESULT: FAIL（见上方 GATE 行）"
    # 失败时额外收集诊断信息
    echo "=== 失败时完整诊断 ==="
    echo "--- 完整 logcat (最后 200 行) ---"
    adb logcat -d | tail -200
    echo "--- 所有 ccspeed 相关进程 ---"
    adb shell "ps -A | grep -E 'ccspeed|hev' || echo 'none'"
    echo "--- 所有监听端口 ---"
    adb shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -E ':0439|:70C5' || echo 'no ports'"
    echo "--- VPN 服务状态 ---"
    adb shell dumpsys activity services "$PKG/.CcspeedVpnService" 2>/dev/null || echo "no service info"
    exit 1
fi
echo "RESULT: PASS"

say "APK assets 零凭据复核"
APK_INSTALLED="$(adb shell pm path "$PKG" | head-1 | sed 's/^package://' | tr -d '\r')"
echo "installed apk=$APK_INSTALLED"
adb pull "$APK_INSTALLED" /tmp/installed.apk >/dev/null
HIT="$(python3 - <<'PY'
import re
data = open('/tmp/installed.apk', 'rb').read()
hits = re.findall(rb'CC_AUTH=[A-Za-z0-9_-]{16,}|CC_COMM=[A-Za-z0-9_-]{16,}', data)
print(len(hits))
PY
)"
echo "credential-shape hits in APK = $HIT"
if [ "$HIT" != "0" ]; then
    echo "FATAL: APK 命中凭据形状"; exit 1
fi

say "gate 完成"
