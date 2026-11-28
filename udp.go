package main

import (
	"encoding/binary"
	"io"
	"log"
	"net"
	"time"
)

// UDP 网关（节点:端口）。
// 2026-11-28：原 120.232.239.210/125.94.47.254 的 :46405 与同 IP 的 TCP 节点均已失效，
// 改为与 fallbackNodes 同 IP 的 46405（同一台 CC 服务器另开的 UDP 口）。
// ⚠️ 尚未真机实测 UDP 数据面（TCP 已实测通过），以 CMP-444 验证为准。
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