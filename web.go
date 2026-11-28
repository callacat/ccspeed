package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---------- 日志 ring buffer ----------

type ringLog struct {
	mu   sync.Mutex
	line []string
	max  int
}

var ringBuf = &ringLog{max: 500}

func (r *ringLog) Write(p []byte) (int, error) {
	s := strings.TrimRight(string(p), "\n")
	r.mu.Lock()
	r.line = append(r.line, s)
	if len(r.line) > r.max {
		r.line = r.line[len(r.line)-r.max:]
	}
	r.mu.Unlock()
	return len(p), nil
}

// tail 返回最后 n 行。
func (r *ringLog) tail(n int) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if n > len(r.line) {
		n = len(r.line)
	}
	out := make([]string, n)
	copy(out, r.line[len(r.line)-n:])
	return out
}

// tee 同时写给多个 writer。
type teeWriter struct {
	ws []interface{ Write([]byte) (int, error) }
}

func newTee(ws ...interface{ Write([]byte) (int, error) }) teeWriter {
	return teeWriter{ws: ws}
}

func (t teeWriter) Write(p []byte) (int, error) {
	for _, w := range t.ws {
		if _, err := w.Write(p); err != nil {
			return 0, err
		}
	}
	return len(p), nil
}

// ---------- 配置文件 ----------

type configFile struct {
	APIAuth   string `json:"api_auth"`
	APIComm   string `json:"api_comm"`
	APIBase   string `json:"api_base,omitempty"`
	SocksUser string `json:"socks_user,omitempty"`
	SocksPass string `json:"socks_pass,omitempty"`
	WebAddr   string `json:"web_addr,omitempty"`
}

// configPath 返回配置文件路径。
// Android 上通过 CcspeedRuntime.start 设置 HOME=filesDir，优先使用 $HOME/ccspeed.json。
// 其他平台使用 可执行文件同目录下的 ccspeed.json。
func configPath() string {
	if home := os.Getenv("HOME"); home != "" {
		return filepath.Join(home, "ccspeed.json")
	}
	exe, err := os.Executable()
	if err != nil {
		return "ccspeed.json"
	}
	return exe + ".json"
}

func loadConfig() configFile {
	var cfg configFile
	b, err := os.ReadFile(configPath())
	if err != nil {
		return cfg
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		log.Printf("config parse: %v", err)
	}
	return cfg
}

func saveConfig(c configFile) error {
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	p := configPath()
	if err := os.WriteFile(p, b, 0600); err != nil {
		return err
	}
	return os.Chmod(p, 0600)
}

// ---------- 国家分组（本地关键字映射，节点无 country 字段） ----------

// regionOf 按 name 关键字分组；命中不了归 "其他"。
func regionOf(name string) string {
	kw := [][2]string{
		{"香港", "中国香港"}, {"港", "中国香港"}, {"HK", "中国香港"}, {"HongKong", "中国香港"},
		{"台湾", "中国台湾"}, {"台北", "中国台湾"}, {"Taiwan", "中国台湾"},
		{"澳门", "中国澳门"},
		{"日本", "日本"}, {"东京", "日本"}, {"大阪", "日本"}, {"Japan", "日本"}, {"JP", "日本"},
		{"韩国", "韩国"}, {"首尔", "韩国"}, {"Korea", "韩国"}, {"KR", "韩国"},
		{"新加坡", "新加坡"}, {"狮城", "新加坡"}, {"Singapore", "新加坡"}, {"SG", "新加坡"},
		{"马来西亚", "马来西亚"}, {"吉隆坡", "马来西亚"}, {"Malaysia", "马来西亚"}, {"马来", "马来西亚"},
		{"泰国", "泰国"}, {"曼谷", "泰国"}, {"Thailand", "泰国"},
		{"越南", "越南"}, {"Vietnam", "越南"},
		{"菲律宾", "菲律宾"}, {"马尼拉", "菲律宾"}, {"Philippines", "菲律宾"},
		{"印尼", "印度尼西亚"}, {"印度尼西亚", "印度尼西亚"}, {"雅加达", "印度尼西亚"},
		{"印度", "印度"}, {"India", "印度"},
		{"美国", "美国"}, {"洛杉矶", "美国"}, {"硅谷", "美国"}, {"圣何塞", "美国"}, {"西雅图", "美国"}, {"USA", "美国"}, {"US", "美国"},
		{"加拿大", "加拿大"}, {"多伦多", "加拿大"}, {"温哥华", "加拿大"}, {"Canada", "加拿大"},
		{"英国", "英国"}, {"伦敦", "英国"}, {"UK", "英国"}, {"Britain", "英国"},
		{"德国", "德国"}, {"法兰克福", "德国"}, {"Germany", "德国"}, {"DE", "德国"},
		{"法国", "法国"}, {"巴黎", "法国"}, {"France", "法国"},
		{"荷兰", "荷兰"}, {"阿姆斯特丹", "荷兰"}, {"Netherlands", "荷兰"},
		{"俄罗斯", "俄罗斯"}, {"莫斯科", "俄罗斯"}, {"Russia", "俄罗斯"}, {"RU", "俄罗斯"},
		{"澳洲", "澳大利亚"}, {"澳大利亚", "澳大利亚"}, {"悉尼", "澳大利亚"}, {"Australia", "澳大利亚"}, {"AU", "澳大利亚"},
		{"土耳其", "土耳其"}, {"Turkey", "土耳其"},
		{"巴西", "巴西"}, {"Brazil", "巴西"},
		{"阿根廷", "阿根廷"},
	}
	for _, p := range kw {
		if strings.Contains(strings.ToLower(name), strings.ToLower(p[0])) {
			return p[1]
		}
	}
	return "其他"
}

// ---------- HTTP handlers ----------

type nodeInfo struct {
	Addr  string `json:"addr"`
	Name  string `json:"name"`
	Group string `json:"group"`
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	enc.Encode(v)
}

func apiStatus(w http.ResponseWriter, r *http.Request) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	writeJSON(w, map[string]any{
		"listen":        listenAddr(),
		"node":          st.chosen,
		"node_name":     st.chosenName,
		"node_group":    regionOf(st.chosenName),
		"latency_ms":    st.latency.Milliseconds(),
		"rotating":      st.rotate,
		"nodes":         len(st.nodes),
		"since":         st.chosenTime.UTC().Format(time.RFC3339),
		"socks_auth":    st.socksUser != "",
		"udp_node":      st.udpNode,
		"core_version":  version,
	})
}

// listenAddr 由 -listen flag 记录，/api/status 展示。
var listenAddrVal string

func listenAddr() string { return listenAddrVal }

func apiNodes(w http.ResponseWriter, r *http.Request) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	out := make([]nodeInfo, 0, len(st.nodes))
	for _, n := range st.nodes {
		name := nodeLabel(n)
		out = append(out, nodeInfo{Addr: n, Name: name, Group: regionOf(name)})
	}
	writeJSON(w, out)
}

type selectReq struct {
	Addr string `json:"addr"`
}

func apiSelect(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var req selectReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.Addr == "" {
		http.Error(w, "addr required", http.StatusBadRequest)
		return
	}
	st.mu.Lock()
	found := false
	for _, n := range st.nodes {
		if n == req.Addr {
			found = true
			break
		}
	}
	if !found {
		st.mu.Unlock()
		http.Error(w, "node not in list", http.StatusNotFound)
		return
	}
	st.mu.Unlock()
	d, _ := probeLatency(req.Addr, st.tunnelID(), nil)
	st.mu.Lock()
	st.chosen = req.Addr
	st.chosenName = nodeLabel(req.Addr)
	st.chosenTime = time.Now()
	st.latency = d
	if !st.rotate {
		st.active = []string{req.Addr}
	}
	st.mu.Unlock()
	log.Printf("selected node %s (%s) latency=%s", req.Addr, nodeLabel(req.Addr), d.Round(time.Millisecond))
	writeJSON(w, map[string]any{"ok": true, "node": req.Addr, "latency_ms": d.Milliseconds()})
}

func apiLogs(w http.ResponseWriter, r *http.Request) {
	n := 200
	writeJSON(w, map[string]any{"lines": ringBuf.tail(n)})
}

// configResp 回给前端：凭据做脱敏（是否存在），不回真实值。
func apiConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		cfg := loadConfig()
		// -api-auth/-api-comm flag 或 env 优先于配置文件
		apiAuth, apiComm := currentCreds()
		writeJSON(w, map[string]any{
			"api_auth_set":   apiAuth != "",
			"api_comm_set":   apiComm != "",
			"api_base":       apiBaseVal,
			"web_addr":       webAddrVal,
			"socks_user_set": st.socksUser != "",
			"from_file":      cfg.APIAuth != "",
			"config_path":    configPath(),
		})
	case http.MethodPost:
		var in struct {
			APIAuth   string `json:"api_auth"`
			APIComm   string `json:"api_comm"`
			APIBase   string `json:"api_base"`
			SocksUser string `json:"socks_user"`
			SocksPass string `json:"socks_pass"`
			WebAddr   string `json:"web_addr"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		cur := loadConfig()
		if in.APIAuth != "" {
			cur.APIAuth = in.APIAuth
		}
		if in.APIComm != "" {
			cur.APIComm = in.APIComm
		}
		if in.APIBase != "" {
			cur.APIBase = in.APIBase
		}
		cur.SocksUser = in.SocksUser
		cur.SocksPass = in.SocksPass
		if in.WebAddr != "" {
			cur.WebAddr = in.WebAddr
		}
		if err := saveConfig(cur); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		st.mu.Lock()
		st.socksUser = cur.SocksUser
		st.socksPass = cur.SocksPass
		st.mu.Unlock()
		writeJSON(w, map[string]any{"ok": true, "saved": configPath(), "note": "api 凭据与 Web 端口重启后生效；SOCKS5 鉴权即时生效"})
	default:
		http.Error(w, "GET/POST only", http.StatusMethodNotAllowed)
	}
}

var apiAuthVal, apiCommVal, apiBaseVal, webAddrVal string

func currentCreds() (string, string) { return apiAuthVal, apiCommVal }

// ---------- 启动 ----------

func startWeb(addr string) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		log.Printf("web console disabled: -web %q 需要 host:port 或 :port 形态: %v", addr, err)
		return
	}
	if n, e := strconv.Atoi(port); e != nil || n < 0 || n > 65535 {
		log.Printf("web console disabled: -web %q 端口 %s 超出合法范围 0-65535", addr, port)
		return
	}
	if host == "127.0.0.1" {
		host = "0.0.0.0"
	}
	listenAddr := net.JoinHostPort(host, port)
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(uiHTML))
	})
	mux.HandleFunc("/api/status", apiStatus)
	mux.HandleFunc("/api/nodes", apiNodes)
	mux.HandleFunc("/api/select", apiSelect)
	mux.HandleFunc("/api/logs", apiLogs)
	mux.HandleFunc("/api/config", apiConfig)
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		log.Printf("web console disabled: %v", err)
		return
	}
	log.Printf("web console http://%s", ln.Addr())
	openBrowser("http://" + ln.Addr().String())
	if err := (&http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}).Serve(ln); err != nil {
		log.Printf("web serve: %v", err)
	}
}

func openBrowser(url string) {
	var name string
	var args []string
	switch runtime.GOOS {
	case "windows":
		name, args = "cmd", []string{"/c", "start", "", url}
	case "darwin":
		name, args = "open", []string{url}
	default:
		name, args = "xdg-open", []string{url}
	}
	if p, err := exec.LookPath(name); err == nil {
		go func() { exec.Command(p, args...).Start() }()
	} else {
		fmt.Fprintf(os.Stderr, "open browser manually: %s\n", url)
	}
}