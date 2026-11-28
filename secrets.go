package main

// 本地凭据：绝不要提交/外发。
// 为空时必须由 Web 控制台或 CC_AUTH/CC_COMM 环境变量提供。
const defaultAPIAuth = ``
const defaultAPIComm = ``

// 内置回退节点列表：无凭据/无 -node/-nodes-file 时使用，避免 "api returned no nodes" 直接 Fatal。
// 这些是实测可用的 CC 节点，定期轮换；与 udpNodes 同源维护。
// 2026-11-28 实测：原 120.232.239.210:9003 / 125.94.47.254:9003 已失效
//（ccspeed 自检 "no healthy nodes after probe"），替换为 CT103 现役实例在用的活节点。
var fallbackNodes = []string{
	"106.75.13.238:8012",   // JP
	"106.75.225.124:9007",  // KR
	"125.94.47.254:8005",   // US
	"106.75.13.238:9007",   // DE
	"106.75.13.238:12125",  // RU
}
