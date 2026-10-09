// anytls-plugin: KNcloud 安卓端的 AnyTLS 插件，打包为 libanytls.so。
//
// Xray-core 没有 AnyTLS 出站（上游明确不做），安卓的 Xray 又是预编译的
// libv2ray.aar，所以和 Hysteria2 一样以独立进程运行：本进程只监听 127.0.0.1
// 的 SOCKS5，背后用 sing-anytls 客户端连 AnyTLS 服务器；Xray 把它当 socks 出站。
//
//	应用 → Xray(入站 + 路由分流 + 统计) → 本机 AnyTLS 桥 → AnyTLS 服务器
//
// 桥的实现与 KNcloud-WIN 的 anytls.go 保持一致（uTLS 指纹、SNI 规则、
// TCP 走 AnyTLS 流、UDP 走 UDP-over-TCP v2）。
//
// 用法: libanytls.so -c config.json
package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	anytls "github.com/anytls/sing-anytls"
	utls "github.com/refraction-networking/utls"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
)

// Config 由 KNcloud（PluginServiceManager）生成。
type Config struct {
	Listen      string `json:"listen"` // 127.0.0.1:port
	Server      string `json:"server"` // 服务器地址（域名或 IP）
	ServerPort  int    `json:"server_port"`
	Password    string `json:"password"`
	SNI         string `json:"sni,omitempty"`
	Insecure    bool   `json:"insecure,omitempty"`
	Fingerprint string `json:"fingerprint,omitempty"`
}

func main() {
	configPath := flag.String("c", "", "config file")
	flag.Parse()
	log.SetFlags(log.LstdFlags)
	if *configPath == "" {
		log.Fatal("missing -c config")
	}
	data, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatal("read config: ", err)
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		log.Fatal("decode config: ", err)
	}
	b, err := startAnyTLSBridge(cfg)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("anytls socks5 %s => %s", b.addr, net.JoinHostPort(cfg.Server, strconv.Itoa(cfg.ServerPort)))

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM, syscall.SIGHUP)
	<-sig
	b.Stop()
}

// anyTLSServerName TLS SNI：显式 sni 优先，否则用域名形式的服务器地址；IP 地址不发 SNI。
func anyTLSServerName(cfg Config) string {
	sni := strings.TrimSpace(cfg.SNI)
	if sni == "" {
		sni = cfg.Server
	}
	if net.ParseIP(strings.Trim(sni, "[]")) != nil {
		return ""
	}
	return sni
}

// startAnyTLSBridge 按配置起一个本地 AnyTLS 桥。
func startAnyTLSBridge(cfg Config) (*anyTLSBridge, error) {
	if cfg.Password == "" {
		return nil, fmt.Errorf("AnyTLS node missing password")
	}
	if cfg.ServerPort <= 0 || cfg.ServerPort > 65535 {
		return nil, fmt.Errorf("AnyTLS node has invalid port: %d", cfg.ServerPort)
	}
	if cfg.Server == "" {
		return nil, fmt.Errorf("AnyTLS node missing server")
	}
	listen := cfg.Listen
	if listen == "" {
		listen = "127.0.0.1:0"
	}
	server := net.JoinHostPort(strings.Trim(cfg.Server, "[]"), strconv.Itoa(cfg.ServerPort))
	serverName := anyTLSServerName(cfg)
	tlsCfg := &utls.Config{
		ServerName:         serverName,
		InsecureSkipVerify: cfg.Insecure || serverName == "",
		MinVersion:         utls.VersionTLS12,
	}
	helloID := anyTLSHelloID(cfg.Fingerprint)

	dialOut := func(ctx context.Context) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(ctx, anyTLSDialTimeout)
		defer cancel()
		var d net.Dialer
		raw, err := d.DialContext(ctx, "tcp", server)
		if err != nil {
			return nil, err
		}
		tc := utls.UClient(raw, tlsCfg.Clone(), helloID)
		if err := tc.HandshakeContext(ctx); err != nil {
			raw.Close()
			return nil, fmt.Errorf("AnyTLS TLS handshake: %w", err)
		}
		return tc, nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	client, err := anytls.NewClient(ctx, anytls.ClientConfig{
		Password:                 cfg.Password,
		IdleSessionCheckInterval: 30 * time.Second,
		IdleSessionTimeout:       30 * time.Second,
		DialOut:                  dialOut,
		Logger:                   logger.NOP(), // 服务端推送 padding / alert 时会写日志，nil 会崩
	})
	if err != nil {
		cancel()
		return nil, err
	}
	ln, err := net.Listen("tcp", listen)
	if err != nil {
		client.Close()
		cancel()
		return nil, err
	}
	b := &anyTLSBridge{
		addr:   ln.Addr().String(),
		ln:     ln,
		client: client,
		ctx:    ctx,
		cancel: cancel,
		conns:  map[io.Closer]struct{}{},
	}
	b.wg.Add(1)
	go b.serve()
	return b, nil
}

// anyTLSBridge 是一个运行中的 AnyTLS 桥。
type anyTLSBridge struct {
	addr   string // 127.0.0.1:port，供 Xray socks 出站连接
	ln     net.Listener
	client *anytls.Client
	ctx    context.Context
	cancel context.CancelFunc

	mu    sync.Mutex
	conns map[io.Closer]struct{}
	once  sync.Once
	wg    sync.WaitGroup
}

// anyTLSDialTimeout 连服务器（TCP + TLS 握手）的上限。
const anyTLSDialTimeout = 10 * time.Second

// anyTLSHelloID 把分享链接里的 fp 映射成 uTLS 指纹；AnyTLS 常架在 CDN 后，默认用 Chrome。
func anyTLSHelloID(fp string) utls.ClientHelloID {
	switch strings.ToLower(strings.TrimSpace(fp)) {
	case "firefox":
		return utls.HelloFirefox_Auto
	case "safari":
		return utls.HelloSafari_Auto
	case "ios":
		return utls.HelloIOS_Auto
	case "edge":
		return utls.HelloEdge_Auto
	case "random", "randomized":
		return utls.HelloRandomized
	default:
		return utls.HelloChrome_Auto
	}
}

// Stop 关闭桥：停止监听，断开全部连接与 AnyTLS 会话。可重复调用。
func (b *anyTLSBridge) Stop() {
	if b == nil {
		return
	}
	b.once.Do(func() {
		b.cancel()
		b.ln.Close()
		b.mu.Lock()
		for c := range b.conns {
			c.Close()
		}
		b.conns = map[io.Closer]struct{}{}
		b.mu.Unlock()
		b.client.Close()
	})
}

func (b *anyTLSBridge) track(c io.Closer) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.ctx.Err() != nil {
		c.Close()
		return false
	}
	b.conns[c] = struct{}{}
	return true
}

func (b *anyTLSBridge) untrack(c io.Closer) {
	b.mu.Lock()
	delete(b.conns, c)
	b.mu.Unlock()
	c.Close()
}

func (b *anyTLSBridge) serve() {
	defer b.wg.Done()
	for {
		c, err := b.ln.Accept()
		if err != nil {
			return
		}
		if !b.track(c) {
			continue
		}
		go func() {
			defer b.untrack(c)
			b.handleSocks(c)
		}()
	}
}

// ------------------------- 最小 SOCKS5 服务端（仅本机 Xray 使用） -------------------------

const (
	socksVer         = 5
	socksCmdConnect  = 1
	socksCmdUDPAssoc = 3
)

var errSocksBadRequest = errors.New("bad socks request")

func (b *anyTLSBridge) handleSocks(c net.Conn) {
	c.SetDeadline(time.Now().Add(15 * time.Second))
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil || hdr[0] != socksVer {
		return
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}
	if _, err := c.Write([]byte{socksVer, 0}); err != nil { // NO AUTH
		return
	}
	req := make([]byte, 3)
	if _, err := io.ReadFull(c, req); err != nil || req[0] != socksVer {
		return
	}
	dest, err := readSocksAddr(c)
	if err != nil {
		return
	}
	switch req[1] {
	case socksCmdConnect:
		remote, err := b.client.CreateProxy(b.ctx, dest)
		if err != nil {
			c.Write([]byte{socksVer, 5, 0, 1, 0, 0, 0, 0, 0, 0}) // connection refused
			return
		}
		if !b.track(remote) {
			return
		}
		defer b.untrack(remote)
		if _, err := c.Write([]byte{socksVer, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
			return
		}
		c.SetDeadline(time.Time{})
		relayTCP(c, remote)
	case socksCmdUDPAssoc:
		b.handleUDPAssociate(c)
	default:
		c.Write([]byte{socksVer, 7, 0, 1, 0, 0, 0, 0, 0, 0}) // command not supported
	}
}

// readSocksAddr 读 ATYP + ADDR + PORT。
func readSocksAddr(r io.Reader) (M.Socksaddr, error) {
	t := make([]byte, 1)
	if _, err := io.ReadFull(r, t); err != nil {
		return M.Socksaddr{}, err
	}
	var host string
	switch t[0] {
	case 1:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(r, ip); err != nil {
			return M.Socksaddr{}, err
		}
		host = net.IP(ip).String()
	case 4:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(r, ip); err != nil {
			return M.Socksaddr{}, err
		}
		host = net.IP(ip).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(r, l); err != nil {
			return M.Socksaddr{}, err
		}
		name := make([]byte, int(l[0]))
		if _, err := io.ReadFull(r, name); err != nil {
			return M.Socksaddr{}, err
		}
		host = string(name)
	default:
		return M.Socksaddr{}, errSocksBadRequest
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(r, p); err != nil {
		return M.Socksaddr{}, err
	}
	return M.ParseSocksaddrHostPort(host, binary.BigEndian.Uint16(p)), nil
}

// appendSocksAddr 写 ATYP + ADDR + PORT。
func appendSocksAddr(dst []byte, a M.Socksaddr) []byte {
	if a.IsFqdn() {
		dst = append(dst, 3, byte(len(a.Fqdn)))
		dst = append(dst, a.Fqdn...)
	} else if ip := a.Addr.Unmap(); ip.Is4() {
		b := ip.As4()
		dst = append(dst, 1)
		dst = append(dst, b[:]...)
	} else {
		b := a.Addr.As16()
		dst = append(dst, 4)
		dst = append(dst, b[:]...)
	}
	return binary.BigEndian.AppendUint16(dst, a.Port)
}

func relayTCP(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	// 一侧结束后给另一侧一点时间收尾（半关闭），再整体关闭
	timer := time.NewTimer(5 * time.Second)
	select {
	case <-done:
	case <-timer.C:
	}
	timer.Stop()
	a.Close()
	b.Close()
}

// anyTLSStreamDialer 让 uot.Client 经 AnyTLS 流拨号（UDP-over-TCP 用）。
type anyTLSStreamDialer struct{ b *anyTLSBridge }

func (d anyTLSStreamDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkTCP {
		return nil, fmt.Errorf("unsupported network %s", network)
	}
	return d.b.client.CreateProxy(ctx, destination)
}

func (d anyTLSStreamDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, fmt.Errorf("ListenPacket not supported")
}

// handleUDPAssociate SOCKS5 UDP ASSOCIATE：本地 UDP 口收发 SOCKS UDP 报文，
// 经 UDP-over-TCP v2 走一条 AnyTLS 流。控制连接断开即结束。
func (b *anyTLSBridge) handleUDPAssociate(ctrl net.Conn) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		ctrl.Write([]byte{socksVer, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	if !b.track(pc) {
		return
	}
	defer b.untrack(pc)
	reply := []byte{socksVer, 0, 0}
	reply = appendSocksAddr(reply, M.SocksaddrFromNet(pc.LocalAddr()))
	if _, err := ctrl.Write(reply); err != nil {
		return
	}
	ctrl.SetDeadline(time.Time{})

	// UoT 请求头里必须带一个合法目标（非 connect 模式下服务端只当参考），
	// 所以等第一个报文到了、拿它的目标去建 UoT 流。
	var remote net.PacketConn
	defer func() {
		if remote != nil {
			b.untrack(remote)
		}
	}()

	// 控制连接关闭 → 结束整个关联
	go func() {
		io.Copy(io.Discard, ctrl)
		pc.Close()
	}()

	var (
		clientMu   sync.Mutex
		clientAddr net.Addr
	)
	buf := make([]byte, 65535)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		if n < 4 || buf[2] != 0 { // 不支持分片
			continue
		}
		r := &byteReader{b: buf[3:n]}
		dest, err := readSocksAddr(r)
		if err != nil {
			continue
		}
		clientMu.Lock()
		clientAddr = from
		clientMu.Unlock()
		if remote == nil {
			uc := &uot.Client{Dialer: anyTLSStreamDialer{b}, Version: uot.Version}
			rc, err := uc.ListenPacket(b.ctx, dest)
			if err != nil {
				return
			}
			if !b.track(rc) {
				return
			}
			remote = rc
			// 远端 → 本地客户端
			go func() {
				rb := make([]byte, 65535)
				for {
					n, src, err := rc.ReadFrom(rb)
					if err != nil {
						pc.Close()
						return
					}
					clientMu.Lock()
					to := clientAddr
					clientMu.Unlock()
					pkt := appendSocksAddr([]byte{0, 0, 0}, M.SocksaddrFromNet(src))
					pkt = append(pkt, rb[:n]...)
					pc.WriteTo(pkt, to)
				}
			}()
		}
		if _, err := remote.WriteTo(r.rest(), dest); err != nil {
			return
		}
	}
}

type byteReader struct {
	b []byte
	i int
}

func (r *byteReader) Read(p []byte) (int, error) {
	if r.i >= len(r.b) {
		return 0, io.EOF
	}
	n := copy(p, r.b[r.i:])
	r.i += n
	return n, nil
}

func (r *byteReader) rest() []byte { return r.b[r.i:] }
