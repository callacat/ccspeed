// ccsocks: 纯 Go 的 CC 加速器 TCP relay 客户端。
// 不需要模拟器/官方 App/Frida；直接连节点 9003 端口。
//
// 隧道协议:
//
//	CONNECT: [0a 01 00 01][id_len][id][80 5a 5d 00 01 00 00 00 0b 0a 00 04][ip4][port2]
//	数据:    明文 XOR 0x41（双向）
package main

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	mrand "math/rand/v2"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

const xorKey = 0x41

// version 由构建时通过 -ldflags "-X main.version=..." 注入（CI release job 自动注入 tag）
var version = "dev"

// state 保存运行时可变状态；切节点/改凭据只换指针，老连接继续用旧值。
type state struct {
	mu         sync.RWMutex
	nodes      []string // 候选节点
	active     []string // 当前生效的连接目标（rotate=false 时是 [chosen]）
	chosen     string   // 当前固定节点（rotate 模式为空）
	chosenName string   // 当前节点 label
	chosenTime time.Time
	latency    time.Duration
	rotate     bool
	id         string
	udpNode    string
	socksUser  string
	socksPass  string
}

func (s *state) activeSet() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.active
}

func (s *state) tunnelID() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.id
}

func (s *state) udp() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.udpNode
}

var st = &state{}

var (
	protectSocketName string // abstract namespace socket name for protect socket IPC
	protectConn       net.Conn
)

func main() {
	listen := flag.String("listen", "127.0.0.1:1081", "SOCKS5 listen address")
	node := flag.String("node", "", "fixed CC node host:port (empty = random per connection)")
	nodesFile := flag.String("nodes-file", "", "optional live_nodes.json path (default: embedded tested list)")
	id := flag.String("id", "", "tunnel client id (default random 9 chars)")
	rotate := flag.Bool("rotate", false, "pick a random node for every connection (default false: one random node at startup)")
	udpNode := flag.String("udp-node", "", "UDP gateway host:port (empty = random built-in)")
	webAddr := flag.String("web", "127.0.0.1:28981", "Web console address (empty = disabled)")
	socksUser := flag.String("socks-user", envOr(os.Getenv("CC_SOCKS_USER"), ""), "SOCKS5 username (empty = no-auth)")
	socksPass := flag.String("socks-pass", envOr(os.Getenv("CC_SOCKS_PASS"), ""), "SOCKS5 password (empty = no-auth)")
	apiBase := flag.String("api-base", "https://desktop.ccspeed.cn/api", "CC desktop API base (default: https://desktop.ccspeed.cn/api; empty = local list)")
	apiGame := flag.Int("api-game-id", 0, "gameId for API node pull (0 = random group)")
	apiGroup := flag.Int("api-group-id", 0, "fixed groupId (0 = random group)")
	apiGroupMin := flag.Int("api-group-min", 1, "random groupId range min")
	apiGroupMax := flag.Int("api-group-max", 10100, "random groupId range max")
	apiScanGroups := flag.Int("api-scan-groups", 30, "number of random groups to sample for fastest node")
	quiet := flag.Bool("quiet", true, "hide logs (set -quiet=false to show)")
	apiAuth := flag.String("api-auth", envOr(os.Getenv("CC_AUTH"), defaultAPIAuth), "Authorization header (or CC_AUTH)")
	apiComm := flag.String("api-comm", envOr(os.Getenv("CC_COMM"), defaultAPIComm), "comm header (or CC_COMM)")
	protectSocketFlag := flag.String("protect-socket", "", "abstract namespace socket name for protect socket IPC with Kotlin side (Android only)")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	protectSocketName = *protectSocketFlag
	if protectSocketName != "" {
		// Connect to Kotlin's LocalServerSocket in abstract namespace
		conn, err := net.Dial("unix", "@"+protectSocketName)
		if err != nil {
			log.Printf("failed to connect to protect socket %s: %v", protectSocketName, err)
		} else {
			protectConn = conn
			log.Printf("connected to protect socket %s", protectSocketName)
		}
	}

	if *showVersion {
		fmt.Println(version)
		return
	}
	log.SetOutput(newTee(io.Discard, ringBuf))
	// 控制台可见：日志同时进 ring buffer（Web /api/logs 用），quiet 只影响 stdout。
	if !*quiet {
		log.SetOutput(newTee(os.Stderr, ringBuf))
	}
	if *id == "" {
		*id = randomID(9)
	}
	st.rotate = *rotate
	st.id = *id
	st.socksUser = *socksUser
	st.socksPass = *socksPass
	cfg := loadConfig()
	if cfg.WebAddr != "" && *webAddr == "127.0.0.1:28981" {
		// 未显式给 -web 时允许配置文件覆盖默认端口。
		*webAddr = cfg.WebAddr
	}
	var nodes []string
	var err error
	if *apiBase != "" && *node == "" && *nodesFile == "" {
		// 无凭据时直接用内置节点，避免无谓的 API 重试
		if *apiAuth == "" || *apiComm == "" {
			log.Printf("no api credentials provided, using %d builtin fallback nodes", len(fallbackNodes))
			nodes = fallbackNodes
		} else {
			group := *apiGroup
			groupsUsed := 0
			var lastErr error
			if group == 0 && *apiGame == 0 {
				// 采样多个随机 groupId，汇总节点后再测延迟选最快。
				seen := map[string]bool{}
				scan := *apiScanGroups
				if scan < 1 {
					scan = 1
				}
				for try := 0; try < scan*3 && groupsUsed < scan; try++ {
					g := *apiGroupMin
					if *apiGroupMax > *apiGroupMin {
						g += mrand.IntN(*apiGroupMax - *apiGroupMin + 1)
					}
					ns, e := fetchAPINodes(*apiBase, *apiAuth, *apiComm, 0, g)
					if e != nil {
						// 拉取失败（含缺凭据 / 上游 401）：=> 记 lastErr 并继续尝试下一组，末尾把 lastErr 冒泡
						lastErr = e
						log.Printf("group %d fetch failed: %v", g, e)
						continue
					}
					if len(ns) == 0 {
						// 拉到 0 条：静默 continue
						continue
					}
					groupsUsed++
					for _, n := range ns {
						if !seen[n] {
							seen[n] = true
							nodes = append(nodes, n)
						}
					}
				}
			} else {
				nodes, err = fetchAPINodes(*apiBase, *apiAuth, *apiComm, *apiGame, group)
				if err != nil {
					log.Fatal(err)
				}
				groupsUsed = 1
			}
			if len(nodes) == 0 {
				// 用户提供了凭据但仍拉不到节点，报错退出
				msg := "api returned no nodes"
				if lastErr != nil {
					msg = lastErr.Error()
				}
				fmt.Fprintln(os.Stderr, "ccspeed: "+msg)
				os.Exit(1)
			}
			log.Printf("nodes loaded from API=%d groups=%d", len(nodes), groupsUsed)
		}
	} else {
		nodes, err = loadNodes(*node, *nodesFile)
		if err != nil {
			log.Fatal(err)
		}
		log.Printf("nodes loaded=%d", len(nodes))
	}
	use := nodes
	chosenNode := ""
	var chosenDelay time.Duration
	if !*rotate && *node == "" {
		chosenNode, chosenDelay = pickFastest(nodes, *id)
		use = []string{chosenNode}
		log.Printf("selected fastest node %s (%s) latency=%s", chosenNode, nodeLabel(chosenNode), chosenDelay.Round(time.Millisecond))
	}
	if *node != "" {
		chosenNode = *node
	}
	uNode := *udpNode
	if uNode == "" {
		uNode = udpNodes[mrand.IntN(len(udpNodes))]
	}
	st.mu.Lock()
	st.nodes = nodes
	st.active = use
	st.chosen = chosenNode
	st.chosenName = nodeLabel(chosenNode)
	st.chosenTime = time.Now()
	st.latency = chosenDelay
	st.udpNode = uNode
	st.mu.Unlock()
	log.Printf("listen=%s id=%s rotate=%v udp-node=%s", *listen, *id, *rotate, uNode)
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	apiAuthVal, apiCommVal = *apiAuth, *apiComm
	apiBaseVal = *apiBase
	webAddrVal = *webAddr
	listenAddrVal = ln.Addr().String()
	printBanner(chosenNode, chosenDelay, ln.Addr().String(), *rotate)
	if *webAddr != "" {
		go startWeb(*webAddr)
	}
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		go handle(c)
	}
}

func envOr(v, def string) string {
	if v != "" {
		return v
	}
	return def
}

var nodeLabels = map[string]string{}

func nodeLabel(addr string) string {
	if v := nodeLabels[addr]; v != "" {
		return v
	}
	return "unknown"
}

func fetchAPINodes(base, auth, comm string, gameID, groupID int) ([]string, error) {
	if auth == "" || comm == "" {
		return nil, fmt.Errorf("api-auth/api-comm required (CC_AUTH/CC_COMM)")
	}
	if gameID == 0 && groupID == 0 {
		return nil, fmt.Errorf("api-game-id or api-group-id required")
	}
	base = strings.TrimRight(base, "/")
	h := map[string]string{
		"Authorization": auth,
		"comm":          comm,
		"Accept":        "*/*",
		"User-Agent":    "CCSPEED_PC_EXE_20230414",
	}
	get := func(url string) (map[string]any, error) {
		req, err := http.NewRequest("GET", url, nil)
		if err != nil {
			return nil, err
		}
		for k, v := range h {
			req.Header.Set(k, v)
		}
		client := &http.Client{Timeout: 15 * time.Second}
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		var m map[string]any
		if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
			return nil, err
		}
		// 上游未登入/凭证失效时返 {"code":401,"msg":"您还没有登入,请先登入"}（HTTP 也常是 200），
		// 不在这里冒泡就会被 walk 当成「结构里没有节点」，最后以 "api returned no nodes" 误导用户。
		if c, ok := m["code"]; ok {
			switch fmt.Sprint(c) {
			case "401", "403":
				msg, _ := m["msg"].(string)
				if msg == "" {
					msg = "上游拒绝：凭证无效或未登入"
				}
				return nil, fmt.Errorf("api %s: %s（检查 CC_AUTH/CC_COMM）", url, msg)
			}
		}
		return m, nil
	}
	groups := []int{}
	if groupID != 0 {
		groups = append(groups, groupID)
	} else {
		m, err := get(fmt.Sprintf("%s/content/GameSpeedLine/getGameSpeedLineGroup?gameId=%d", base, gameID))
		if err != nil {
			return nil, err
		}
		var walk func(any)
		walk = func(x any) {
			switch v := x.(type) {
			case map[string]any:
				if g, ok := v["groupId"]; ok {
					switch n := g.(type) {
					case float64:
						groups = append(groups, int(n))
					case string:
						if i, e := strconv.Atoi(n); e == nil {
							groups = append(groups, i)
						}
					}
				}
				for _, y := range v {
					walk(y)
				}
			case []any:
				for _, y := range v {
					walk(y)
				}
			}
		}
		walk(m)
	}
	groups = uniqueInts(groups)
	seen := map[string]bool{}
	var out []string
	for _, g := range groups {
		m, err := get(fmt.Sprintf("%s/content/GameSpeedLine/getGameSpeedLine?groupId=%d", base, g))
		if err != nil {
			return nil, err
		}
		var walk func(any)
		walk = func(x any) {
			switch v := x.(type) {
			case map[string]any:
				host, _ := v["host"].(string)
				port, _ := v["port"].(float64)
				if host != "" && port != 0 {
					s := net.JoinHostPort(host, fmt.Sprint(int(port)))
					if name, ok := v["name"].(string); ok && name != "" {
						nodeLabels[s] = name
					}
					if !seen[s] {
						seen[s] = true
						out = append(out, s)
					}
				}
				for _, y := range v {
					walk(y)
				}
			case []any:
				for _, y := range v {
					walk(y)
				}
			}
		}
		walk(m)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("api returned no nodes")
	}
	return out, nil
}

func uniqueInts(in []int) []int {
	seen := map[int]bool{}
	var out []int
	for _, x := range in {
		if !seen[x] {
			seen[x] = true
			out = append(out, x)
		}
	}
	return out
}

func loadNodes(fixed, file string) ([]string, error) {
	if fixed != "" {
		return []string{fixed}, nil
	}
	var data []byte
	var err error
	if file == "" {
		return nil, fmt.Errorf("no local node source; use -api-base (default) or -nodes-file")
	}
	data, err = os.ReadFile(file)
	if err != nil {
		return nil, err
	}
	// live_nodes.json: {"raw": {mapping: {"serverCfg":[{"ip":...,"port":...}]}}}
	var j struct {
		Raw map[string]struct {
			ServerCfg []struct {
				IP   string `json:"ip"`
				Port int    `json:"port"`
			} `json:"serverCfg"`
		} `json:"raw"`
	}
	var out []string
	seen := map[string]bool{}
	if json.Unmarshal(data, &j) == nil && len(j.Raw) > 0 {
		for _, rec := range j.Raw {
			for _, n := range rec.ServerCfg {
				if n.IP == "" || n.Port == 0 {
					continue
				}
				s := net.JoinHostPort(n.IP, fmt.Sprint(n.Port))
				if !seen[s] {
					seen[s] = true
					out = append(out, s)
				}
			}
		}
	} else {
		for _, line := range strings.Split(string(data), "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			if !seen[line] {
				seen[line] = true
				out = append(out, line)
			}
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no nodes")
	}
	return out, nil
}

func dialNode(nodes []string) (net.Conn, string, error) {
	start := mrand.IntN(len(nodes))
	var last error
	for i := 0; i < len(nodes); i++ {
		n := nodes[(start+i)%len(nodes)]
		c, err := dialAndProtect(n, 8*time.Second)
		if err == nil {
			return c, n, nil
		}
		last = err
	}
	return nil, "", last
}

// dialAndProtect creates a TCP connection and protects the socket fd via Kotlin IPC if available.
func dialAndProtect(addr string, timeout time.Duration) (net.Conn, error) {
	// Parse host:port
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}

	// Resolve IP
	ips, err := net.DefaultResolver.LookupIPAddr(context.Background(), host)
	if err != nil || len(ips) == 0 {
		// Fallback to direct dial
		return net.DialTimeout("tcp", addr, timeout)
	}
	ip := ips[0].IP

	// Use net.Dialer with Control function to protect socket before connect
	dialer := &net.Dialer{
		Timeout: timeout,
		Control: func(network, address string, c syscall.RawConn) error {
			if protectConn != nil {
				return c.Control(func(fd uintptr) {
					if ok := protectSocketFd(int(fd)); !ok {
						log.Printf("protectSocket failed for fd=%d, continuing anyway", fd)
					}
				})
			}
			return nil
		},
	}

	return dialer.DialContext(context.Background(), "tcp", net.JoinHostPort(ip.String(), portStr))
}

// protectSocketFd sends the fd to Kotlin side for VpnService.protect() call via SCM_RIGHTS.
func protectSocketFd(fd int) bool {
	if protectConn == nil {
		return false
	}

	// Get the underlying UnixConn for SCM_RIGHTS
	unixConn, ok := protectConn.(*net.UnixConn)
	if !ok {
		// Fallback: try to get raw conn via File()
		f, err := protectConn.(*net.UnixConn).File()
		if err != nil {
			return false
		}
		defer f.Close()
		return sendFdViaUnix(f.Fd(), fd)
	}

	f, err := unixConn.File()
	if err != nil {
		return false
	}
	defer f.Close()

	return sendFdViaUnix(f.Fd(), fd)
}

//go:build linux

// sendFdViaUnix sends a file descriptor over a Unix domain socket using SCM_RIGHTS.
// Only available on Linux (not Windows).
func sendFdViaUnix(socketFd uintptr, fdToSend int) bool {
	// Use unix.Sendmsg to send the fd via SCM_RIGHTS
	// We need a dummy byte for the message
	dummy := []byte{1} // PROTECT_FD command
	oob := unix.UnixRights(fdToSend)
	err := unix.Sendmsg(int(socketFd), dummy, oob, nil, 0)
	if err != nil {
		log.Printf("Sendmsg failed: %v", err)
		return false
	}

	// Read response (1 byte: 1=OK, 0=FAIL)
	resp := make([]byte, 1)
	_, _, _, _, err = unix.Recvmsg(int(socketFd), resp, nil, 0)
	if err != nil || len(resp) == 0 {
		return false
	}
	return resp[0] == 1
}

//go:build !linux

// sendFdViaUnix stub for non-Linux platforms (Windows, etc.)
func sendFdViaUnix(socketFd uintptr, fdToSend int) bool {
	return false
}

type xorConn struct{ net.Conn }

func (c xorConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	for i := 0; i < n; i++ {
		p[i] ^= xorKey
	}
	return n, err
}

func (c xorConn) Write(p []byte) (int, error) {
	q := make([]byte, len(p))
	copy(q, p)
	for i := range q {
		q[i] ^= xorKey
	}
	return c.Conn.Write(q)
}

// probeLatency 用 example.com:443 的 TLS+HTTP 做健康检查。
func probeLatency(node, id string, ip net.IP) (time.Duration, error) {
	if ip == nil {
		var err error
		if ip, err = resolveIPv4("example.com"); err != nil {
			return 0, err
		}
	}
	start := time.Now()
	c, err := net.DialTimeout("tcp", node, 4*time.Second)
	if err != nil {
		return 0, err
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(6 * time.Second))
	if err := sendConnect(c, id, ip, 443); err != nil {
		return 0, err
	}
	t := tls.Client(xorConn{c}, &tls.Config{ServerName: "example.com", InsecureSkipVerify: true})
	if err := t.Handshake(); err != nil {
		return 0, err
	}
	if _, err := t.Write([]byte("GET / HTTP/1.0\r\nHost: example.com\r\n\r\n")); err != nil {
		return 0, err
	}
	b := make([]byte, 16)
	n, _ := t.Read(b)
	if n == 0 {
		return 0, fmt.Errorf("no response")
	}
	return time.Since(start), nil
}

func pickFastest(nodes []string, id string) (string, time.Duration) {
	ip, err := resolveIPv4("example.com")
	if err != nil || len(nodes) == 0 {
		if len(nodes) == 0 {
			return "", 0
		}
		return nodes[0], 0
	}
	type result struct {
		node string
		d    time.Duration
	}
	ch := make(chan result, len(nodes))
	sem := make(chan struct{}, 16)
	var wg sync.WaitGroup
	for _, n := range nodes {
		wg.Add(1)
		go func(n string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			d, err := probeLatency(n, id, ip)
			if err == nil {
				ch <- result{n, d}
			}
		}(n)
	}
	wg.Wait()
	close(ch)
	best := ""
	var bestD time.Duration
	for r := range ch {
		if best == "" || r.d < bestD {
			best = r.node
			bestD = r.d
		}
	}
	if best == "" {
		log.Printf("no healthy nodes after probe, fallback %s", nodes[0])
		return nodes[0], 0
	}
	return best, bestD
}

func printBanner(node string, delay time.Duration, listen string, rotate bool) {
	if rotate || node == "" {
		fmt.Printf("region  : random-per-connection\n")
	} else {
		fmt.Printf("region  : %s\n", nodeLabel(node))
		fmt.Printf("node    : %s\n", node)
		fmt.Printf("node-ip : %s\n", resolveNodeIP(node))
		if delay > 0 {
			fmt.Printf("latency : %s\n", delay.Round(time.Millisecond))
		}
	}
	fmt.Printf("dns     : 1.1.1.1:53, 223.5.5.5:53, 8.8.8.8:53\n")
	if ip, err := resolveIPv4("example.com"); err == nil {
		fmt.Printf("probe   : example.com -> %s\n", ip)
	}
	fmt.Printf("listen  : %s\n", listen)
}

func resolveNodeIP(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		host = addr
	}
	if ip := net.ParseIP(host); ip != nil {
		return host
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err == nil {
		for _, x := range ips {
			if v4 := x.IP.To4(); v4 != nil {
				return v4.String()
			}
		}
		if len(ips) > 0 {
			return ips[0].IP.String()
		}
	}
	return "unknown"
}

func handle(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))
	if err := socksHandshake(c); err != nil {
		log.Printf("socks: %v", err)
		return
	}
	cmd, host, port, err := socksRequest(c)
	if err != nil {
		log.Printf("request: %v", err)
		return
	}
	if cmd == 3 {
		handleUDP(c, host, port, st.udp())
		return
	}
	ip, err := resolveIPv4(host)
	if err != nil {
		socksReply(c, 0x04)
		log.Printf("resolve %s: %v", host, err)
		return
	}
	up, node, err := dialNode(st.activeSet())
	if err != nil {
		socksReply(c, 0x01)
		log.Printf("node: %v", err)
		return
	}
	defer up.Close()
	if err := sendConnect(up, st.tunnelID(), ip, port); err != nil {
		socksReply(c, 0x01)
		log.Printf("connect frame: %v", err)
		return
	}
	// 先回 SOCKS 成功；节点一般不会先发数据。
	if err := socksReply(c, 0x00); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	up.SetDeadline(time.Time{})
	log.Printf("relay %s:%d via %s (%s)", ip, port, node, nodeLabel(node))
	done := make(chan struct{}, 2)
	go func() { xorCopy(up, c); done <- struct{}{} }()
	go func() { xorCopy(c, up); done <- struct{}{} }()
	<-done
}

// socksHandshake：RFC 1928 方法协商。默认 no-auth；配置了 -socks-user 后
// 只在客户端选择 user/pass(0x02) 时继续，否则直接拒绝。
func socksHandshake(c net.Conn) error {
	h := make([]byte, 2)
	if _, err := io.ReadFull(c, h); err != nil {
		return err
	}
	if h[0] != 5 {
		return fmt.Errorf("bad version %d", h[0])
	}
	m := make([]byte, int(h[1]))
	if _, err := io.ReadFull(c, m); err != nil {
		return err
	}
	st.mu.RLock()
	need := st.socksUser != ""
	st.mu.RUnlock()
	if !need {
		if !hasMethod(m, 0x00) {
			c.Write([]byte{5, 0xff})
			return fmt.Errorf("no acceptable auth method")
		}
		_, err := c.Write([]byte{5, 0})
		return err
	}
	if !hasMethod(m, 0x02) {
		c.Write([]byte{5, 0xff})
		return fmt.Errorf("user/pass auth required, not offered")
	}
	if _, err := c.Write([]byte{5, 2}); err != nil {
		return err
	}
	return socksUserPass(c)
}

func hasMethod(m []byte, want byte) bool {
	for _, x := range m {
		if x == want {
			return true
		}
	}
	return false
}

// socksUserPass：RFC 1929 用户名/口令校验。
func socksUserPass(c net.Conn) error {
	h := make([]byte, 2)
	if _, err := io.ReadFull(c, h); err != nil {
		return err
	}
	if h[0] != 0x01 {
		return fmt.Errorf("bad user/pass version %d", h[0])
	}
	ub := make([]byte, int(h[1]))
	if _, err := io.ReadFull(c, ub); err != nil {
		return err
	}
	pl := make([]byte, 1)
	if _, err := io.ReadFull(c, pl); err != nil {
		return err
	}
	pb := make([]byte, int(pl[0]))
	if _, err := io.ReadFull(c, pb); err != nil {
		return err
	}
	st.mu.RLock()
	wantU, wantP := st.socksUser, st.socksPass
	st.mu.RUnlock()
	if subtleEqual(string(ub), wantU) && subtleEqual(string(pb), wantP) {
		_, err := c.Write([]byte{0x01, 0x00})
		return err
	}
	c.Write([]byte{0x01, 0x01})
	return fmt.Errorf("bad socks credentials")
}

func subtleEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var d byte
	for i := 0; i < len(a); i++ {
		d |= a[i] ^ b[i]
	}
	return d == 0
}

func socksRequest(c net.Conn) (byte, string, int, error) {
	h := make([]byte, 4)
	if _, err := io.ReadFull(c, h); err != nil {
		return 0, "", 0, err
	}
	if h[0] != 5 || (h[1] != 1 && h[1] != 3) {
		socksReply(c, 0x07)
		return 0, "", 0, fmt.Errorf("command %d unsupported", h[1])
	}
	var host string
	switch h[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return 0, "", 0, err
		}
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return 0, "", 0, err
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(c, b); err != nil {
			return 0, "", 0, err
		}
		host = string(b)
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return 0, "", 0, err
		}
		host = net.IP(b).String()
	default:
		socksReply(c, 0x08)
		return 0, "", 0, fmt.Errorf("atyp %d", h[3])
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(c, p); err != nil {
		return 0, "", 0, err
	}
	return h[1], host, int(binary.BigEndian.Uint16(p)), nil
}

func socksReply(c net.Conn, code byte) error {
	_, err := c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}

// 系统 DNS 可能被 CC 的 198.18.0.2 污染，且 utun 路由会瞬时不可达。
// 依次尝试公共 DNS（TCP/UDP），拒绝 198.18/15 假地址。
func makeResolver(network, addr string) *net.Resolver {
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			return d.DialContext(ctx, network, addr)
		},
	}
}

var publicResolvers = []*net.Resolver{
	makeResolver("tcp", "1.1.1.1:53"),
	makeResolver("tcp", "223.5.5.5:53"),
	makeResolver("tcp", "8.8.8.8:53"),
	makeResolver("udp", "1.1.1.1:53"),
	makeResolver("udp", "223.5.5.5:53"),
}

func usableIPv4(ip net.IP) net.IP {
	v4 := ip.To4()
	if v4 == nil {
		return nil
	}
	// CC 的假 DNS 返回 198.18.0.0/15，不能直接发给节点。
	if v4[0] == 198 && (v4[1] == 18 || v4[1] == 19) {
		return nil
	}
	return v4
}

func resolveIPv4(host string) (net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		if v4 := usableIPv4(ip); v4 != nil {
			return v4, nil
		}
		return nil, fmt.Errorf("IPv6/fake IP unsupported")
	}
	for _, r := range publicResolvers {
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		ips, err := r.LookupIPAddr(ctx, host)
		cancel()
		if err != nil {
			continue
		}
		for _, ip := range ips {
			if v4 := usableIPv4(ip.IP); v4 != nil {
				return v4, nil
			}
		}
	}
	// 最后尝试系统 DNS；只接受非 198.18/15。
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	for _, ip := range ips {
		if v4 := usableIPv4(ip.IP); v4 != nil {
			return v4, nil
		}
	}
	return nil, fmt.Errorf("no usable IPv4")
}

func sendConnect(w io.Writer, id string, ip net.IP, port int) error {
	i4 := ip.To4()
	var rb [1]byte
	rand.Read(rb[:])
	n := rb[0] & 0x0f
	x := (byte(port>>8) + i4[0]) & 0xff
	w9 := uint16(n)*0x1110 + uint16(n^x)
	frame := []byte{0x0a | n<<4, 0x01, 0x00, 0x01, byte(len(id))}
	frame = append(frame, []byte(id)...)
	frame = append(frame, 0x80, 0x5a, 0x5d, 0x00)
	frame = append(frame, byte(w9), byte(w9>>8), 0x00, 0x00, 0x0b, 0x00, 0x00, 0x04)
	frame = append(frame, i4...)
	frame = append(frame, byte(port>>8), byte(port))
	_, err := w.Write(frame)
	return err
}

func xorCopy(dst io.Writer, src io.Reader) error {
	buf := make([]byte, 32*1024)
	for {
		n, err := src.Read(buf)
		if n > 0 {
			for i := 0; i < n; i++ {
				buf[i] ^= xorKey
			}
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return werr
			}
		}
		if err != nil {
			return err
		}
	}
}

func randomID(n int) string {
	const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		for i := range b {
			b[i] = chars[i%len(chars)]
		}
	}
	for i := range b {
		b[i] = chars[int(b[i])%len(chars)]
	}
	return string(b)
}
