#!/usr/bin/env bash
# avd-verify.sh 门禁逻辑自测：用假 adb/curl 验证「硬门失败必须 exit 1 / 出口 SKIP 不得 PASS」。
# 不接真机、不联网，秒级跑完。CI 的 android-emulator job 在起模拟器前先跑它。
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
: > "$tmp/fake.apk"

cat > "$tmp/bin/adb" <<'STUB'
#!/usr/bin/env bash
FIXTURE='<?xml version="1.0"?><hierarchy><node text="启动" bounds="[0,0][100,100]"/><node text="OK" bounds="[0,0][100,100]"/></hierarchy>'
case "$*" in
  *"uiautomator dump"*) printf '%s' "$FIXTURE" > /tmp/__avd_fixture.xml; exit 0 ;;
  *"pull "*) cp /tmp/__avd_fixture.xml "${@: -1}" 2>/dev/null; exit 0 ;;
  *"curl -s -m 20"*) echo "${FAKE_TUNNEL_IP:-}"; exit 0 ;;
  *"command -v curl"*) exit 0 ;;
  *"ip -o addr show tun0"*) [ -n "${FAKE_TUN0:-}" ] && echo "$FAKE_TUN0"; exit 0 ;;
  *"ps -A"*) [ -n "${FAKE_NATIVE:-}" ] && echo "$FAKE_NATIVE"; exit 0 ;;
  *":0439 "*) [ -n "${FAKE_SOCKS:-}" ] && echo "$FAKE_SOCKS"; exit 0 ;;
  *) exit 0 ;;
esac
STUB

cat > "$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  *"-o /dev/null"*) printf '%s' "${FAKE_WEB_CODE:-000}"; exit 0 ;;
  *ifconfig.me*) echo "${FAKE_DIRECT_IP:-}"; exit 0 ;;
  *) exit 0 ;;
esac
STUB

chmod +x "$tmp/bin/adb" "$tmp/bin/curl"

fails=0
run_case() {
    local name=$1 want_code=$2 want_result=$3
    shift 3
    local out code
    out=$(cd "$here" && env PATH="$tmp/bin:$PATH" AVD_VERIFY_FAST=1 \
        APK_PATH_OVERRIDE="$tmp/fake.apk" "$@" \
        bash "$here/avd-verify.sh" 2>&1)
    code=$?
    if [ "$code" != "$want_code" ]; then
        echo "FAIL [$name] exit=$code want=$want_code"
        echo "$out" | grep -E "RESULT|FAIL:|SKIP:" | tail -8
        fails=$((fails + 1))
        return
    fi
    if ! grep -q "RESULT: $want_result" <<<"$out"; then
        echo "FAIL [$name] RESULT 不符，期望含 'RESULT: $want_result'"
        echo "$out" | grep -E "RESULT|FAIL:|SKIP:" | tail -8
        fails=$((fails + 1))
        return
    fi
    echo "ok   [$name] exit=$code | $(grep -o 'RESULT: [^（]*' <<<"$out" | tail -1)"
}

CORE=(FAKE_TUN0='18: tun0    inet 10.200.0.2/32' FAKE_NATIVE='1234 /data/app/x/lib/x86_64/libccspeed.so' FAKE_SOCKS='0100007F:0439 00000000:0000 0A')
NET=(FAKE_DIRECT_IP='1.1.1.1' FAKE_WEB_CODE='200')

run_case "全部正常"            0 PASS  "${CORE[@]}" "${NET[@]}" FAKE_TUNNEL_IP='2.2.2.2'
run_case "tun0 缺失"           1 FAIL  "${NET[@]}" FAKE_TUN0= FAKE_NATIVE='1 libccspeed.so' FAKE_SOCKS='x:0439' FAKE_TUNNEL_IP='2.2.2.2'
run_case "native 缺失(只剩壳)"  1 FAIL  "${NET[@]}" FAKE_TUN0='tun0' FAKE_NATIVE= FAKE_SOCKS='x:0439' FAKE_TUNNEL_IP='2.2.2.2'
run_case "SOCKS5 未监听"       1 FAIL  "${NET[@]}" FAKE_TUN0='tun0' FAKE_NATIVE='1 libccspeed.so' FAKE_SOCKS= FAKE_TUNNEL_IP='2.2.2.2'
run_case "假通: 隧道取不到 IP"   1 FAIL  "${CORE[@]}" "${NET[@]}" FAKE_TUNNEL_IP=
run_case "出口与直连相同"       1 FAIL  "${CORE[@]}" "${NET[@]}" FAKE_TUNNEL_IP='1.1.1.1'
run_case "环境无外网 -> SKIP"   0 SKIP  "${CORE[@]}" FAKE_DIRECT_IP= FAKE_TUNNEL_IP=

if [ "$fails" -gt 0 ]; then
    echo "SELFTEST FAIL: $fails 项"
    exit 1
fi
echo "SELFTEST PASS: 7/7"