package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"time"
)

// UDP 网关（节点:端口）。
// 2026-11-28（CMP-444）：CC API 的节点数据只有 host/port，无 UDP 端口字段，
// 这里的地址是推测值、从未验证可用。DNS 已改走 TCP 隧道（见 dnsOverTCP），
// 非 53 端口的 UDP 仍走此尽力路径。
var udpNodes = []string{
	"106.75.13.238:46405",  // JP
	"106.75.225.124:46405", // KR
	"125.94.47.254:46405",  // US
}

func xorBytes(b []byte) {
	for i := range b {
		b[i] ^= xorKey
	}
}

// dnsOverTCP：DNS 查询经节点的 TCP 隧道转发（RFC 1035 TCP 报文，2 字节长度前缀）。
// 节点的 UDP 转发不可用（CMP-444），但 TCP 隧道实测可用且有节点允许到 53 端口
// 的 TCP 出站，故 DNS 单独走这条路，不出隧道、不泄漏。
func dnsOverTCP(ip net.IP, query []byte) ([]byte, error) {
	last := error(nil)
	for _, dst := range dnsTargets(ip) {
		resp, err := dnsExchange(dst, query)
		if err == nil {
			return resp, nil
		}
		last = err
	}
	return nil, last
}

// dnsTargets：首选请求里的 DNS 服务器；部分节点拒绝到 8.8.8.8:53 的 TCP，
// 实测 114.114.114.114 在全部节点可达，作统一回退。
func dnsTargets(ip net.IP) []net.IP {
	out := []net.IP{ip}
	if !ip.Equal(net.IPv4(114, 114, 114, 114)) {
		out = append(out, net.IPv4(114, 114, 114, 114))
	}
	return out
}

func dnsExchange(ip net.IP, query []byte) ([]byte, error) {
	up, node, err := dialNode(st.activeSet())
	if err != nil {
		return nil, err
	}
	defer up.Close()
	up.SetDeadline(time.Now().Add(2500 * time.Millisecond))
	if err := sendConnect(up, st.tunnelID(), ip, 53); err != nil {
		return nil, err
	}
	buf := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(buf, uint16(len(query)))
	copy(buf[2:], query)
	xorBytes(buf)
	if _, err := up.Write(buf); err != nil {
		return nil, err
	}
	var lb [2]byte
	if _, err := io.ReadFull(up, lb[:]); err != nil {
		return nil, err
	}
	xorBytes(lb[:])
	n := int(binary.BigEndian.Uint16(lb[:]))
	if n == 0 {
		return nil, fmt.Errorf("empty dns response")
	}
	resp := make([]byte, n)
	if _, err := io.ReadFull(up, resp); err != nil {
		return nil, err
	}
	xorBytes(resp)
	log.Printf("dns-over-tcp %s via %s (%s) %dB", ip, node, nodeLabel(node), n)
	return resp, nil
}

func handleUDP(c net.Conn, host string, port int, udpNode string) {
	_ = host
	_ = port
	// 每个关联一个本地 UDP 端口和一个到节点的 UDP 套接字。
	cudp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		socksReply(c, 0x01)
		return
	}
	defer cudp.Close()
	naddr, err := net.ResolveUDPAddr("udp", udpNode)
	if err != nil {
		socksReply(c, 0x01)
		return
	}
	nudp, err := net.DialUDP("udp", nil, naddr)
	if err != nil {
		socksReply(c, 0x01)
		return
	}
	defer nudp.Close()

	assoc := uint16(cudp.LocalAddr().(*net.UDPAddr).Port)
	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1, byte(assoc >> 8), byte(assoc)}
	if _, err := c.Write(reply); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	log.Printf("udp associate via %s local=%s", udpNode, cudp.LocalAddr())

	go func() {
		io.Copy(io.Discard, c)
		cudp.Close()
		nudp.Close()
	}()

	var client net.Addr
	// node -> client
	go func() {
		buf := make([]byte, 65535+14)
		for {
			nudp.SetReadDeadline(time.Now().Add(120 * time.Second))
			n, err := nudp.Read(buf)
			if err != nil {
				return
			}
			if n < 14 {
				continue
			}
			// XOR decrypt the entire frame from remote node
			xorBytes(buf[:n])
			dst := net.IP(append([]byte(nil), buf[4:8]...))
			dport := binary.BigEndian.Uint16(buf[8:10])
			payload := append([]byte(nil), buf[14:n]...)
			if client == nil {
				continue
			}
			out := make([]byte, 0, 10+len(payload))
			out = append(out, 0, 0, 0, 1)
			out = append(out, dst...)
			out = append(out, byte(dport>>8), byte(dport))
			out = append(out, payload...)
			cudp.WriteTo(out, client)
		}
	}()

	buf := make([]byte, 65535)
	for {
		n, addr, err := cudp.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if client == nil {
			client = addr
		}
		ip, dport, payload, ok := parseSocksUDP(buf[:n])
		if !ok {
			continue
		}
		// DNS 走 TCP 隧道：节点 UDP 转发不可用。payload/ip 是 buf 的切片，
		// 交给 goroutine 前必须复制，否则会被下一轮 ReadFromUDP 覆盖。
		if dport == 53 && client != nil {
			go func(dst net.IP, q []byte, who net.Addr) {
				resp, err := dnsOverTCP(dst, q)
				if err != nil {
					log.Printf("dns-over-tcp %s: %v", dst, err)
					return
				}
				out := make([]byte, 0, 10+len(resp))
				out = append(out, 0, 0, 0, 1)
				out = append(out, dst.To4()...)
				out = append(out, 0, 53)
				out = append(out, resp...)
				cudp.WriteTo(out, who)
			}(
				append(net.IP(nil), ip.To4()...),
				append([]byte(nil), payload...),
				client,
			)
			continue
		}
		frame := make([]byte, 14)
		binary.BigEndian.PutUint16(frame[1:3], assoc)
		copy(frame[4:8], ip.To4())
		binary.BigEndian.PutUint16(frame[8:10], uint16(dport))
		binary.BigEndian.PutUint16(frame[10:12], uint16(dport))
		frame = append(frame, payload...)
		// XOR encrypt the entire frame before sending to remote node
		xorBytes(frame)
		if _, err := nudp.Write(frame); err != nil {
			return
		}
	}
}

// SOCKS5 UDP 请求: RSV(2) FRAG(1) ATYP(1) DST.ADDR DST.PORT DATA
func parseSocksUDP(b []byte) (net.IP, int, []byte, bool) {
	if len(b) < 4 || b[0] != 0 || b[1] != 0 || b[2] != 0 {
		return nil, 0, nil, false
	}
	var host string
	i := 4
	switch b[3] {
	case 1:
		if len(b) < i+4+2 {
			return nil, 0, nil, false
		}
		host = net.IP(b[i : i+4]).String()
		i += 4
	case 3:
		if len(b) < i+1 {
			return nil, 0, nil, false
		}
		l := int(b[i])
		i++
		if len(b) < i+l+2 {
			return nil, 0, nil, false
		}
		host = string(b[i : i+l])
		i += l
	case 4:
		return nil, 0, nil, false
	default:
		return nil, 0, nil, false
	}
	port := int(binary.BigEndian.Uint16(b[i : i+2]))
	i += 2
	ip, err := resolveIPv4(host)
	if err != nil {
		log.Printf("udp resolve %s: %v", host, err)
		return nil, 0, nil, false
	}
	return ip, port, b[i:], true
}
