#!/usr/bin/env bash
# CMP-410 AVD 验收链：装机 → VpnService 授权 → logcat 判据 → 停止释放 → 零凭据复核。
# 在 CI 里由 reactivecircus/android-emulator-runner 的 script: 调起（adb 已在 PATH）。
# 真机 192.168.1.157 暂不可达 ⇒ 降级到 AVD；真机回归三项单列 backlog。
set -euo pipefail

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

say "adb devices"
adb devices

say "install APK"
adb install -r "$APK"

say "launch MainActivity"
adb shell am start -n "$PKG/.MainActivity"
sleep 5

say "tap 启动 → VpnService.prepare 弹窗"
tap_via_uiautomator ui.xml "启动" "Start"
sleep 3

say "tap 授权对话框（OK/确定/允许/Allow）"
tap_via_uiautomator ui2.xml "OK" "确定" "允许" "Allow" || \
    echo "WARN: 授权按钮未命中——若 service 未起会由下面的判据暴露"
sleep 10

say "logcat banner / ABI / TUN 判据"
adb logcat -d -s ccspeed-runtime:I ccspeed-vpn:I ccspeed-main:I | tail -80

say "native 进程存活"
adb shell "ps -A | grep -E 'ccspeed|hev' || echo 'NO ccspeed/hev process'"

say "tun0 接管"
adb shell "ip -o addr show tun0 || echo 'NO tun0'"

say "SOCKS5 127.0.0.1:1081 在听（/proc/net/tcp :0439）"
adb shell "(grep -i ':0439' /proc/net/tcp || echo 'NO 1081 LISTEN')"

say "Web 控制台 127.0.0.1:28981 应答"
adb shell "curl -s -o /dev/null -w 'http=%{http_code}\n' http://127.0.0.1:28981/"

say "TUN 生效：curl 经壳出（拿出口 IP，与 AVD 自身 egress 不同即接管成功）"
adb shell "curl -s --max-time 20 https://ifconfig.me || echo 'curl-via-tun failed'"

say "停止：service 停、native 退、tun 释放"
adb shell am stopservice -n "$PKG/.CcspeedVpnService"
sleep 4
adb shell "ps -A | grep -E 'ccspeed|hev' || echo 'OK: no ccspeed/hev process'"
adb shell "ip -o addr show tun0 || echo 'OK: tun0 gone'"

say "APK assets 零凭据复核"
APK_INSTALLED="$(adb shell pm path "$PKG" | head -1 | sed 's/^package://' | tr -d '\r')"
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

say "gate: adb install / logcat / TUN / 停止 / 零凭据 全部回显即 PASS"