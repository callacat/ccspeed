package main

// 本地凭据：绝不要提交/外发。
// 为空时必须由 Web 控制台或 CC_AUTH/CC_COMM 环境变量提供。
const defaultAPIAuth = ``
const defaultAPIComm = ``

// 内置回退节点列表：无凭据/无 -node/-nodes-file 时使用，避免 "api returned no nodes" 直接 Fatal。
// 这些是实测可用的 CC 节点，定期轮换；与 udpNodes 同源维护。
var fallbackNodes = []string{
	"120.232.239.210:9003",
	"125.94.47.254:9003",
}
