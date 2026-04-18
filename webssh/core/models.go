package core

import (
	"io"
	"log"
	"sync"
	"unicode/utf8"

	"github.com/gorilla/websocket"
	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

var (
	wcMu   sync.RWMutex
	WcList []*WriteCounter
)

type WriteCounter struct {
	Total int
	Id    string
}

func (wc *WriteCounter) Write(p []byte) (int, error) {
	n := len(p)
	wcMu.Lock()
	wc.Total += n
	wcMu.Unlock()
	return n, nil
}

func AddCounter(wc *WriteCounter) {
	wcMu.Lock()
	WcList = append(WcList, wc)
	wcMu.Unlock()
}

func RemoveCounter(id string) {
	wcMu.Lock()
	defer wcMu.Unlock()
	for i := 0; i < len(WcList); i++ {
		if WcList[i].Id == id {
			WcList = append(WcList[:i], WcList[i+1:]...)
			return
		}
	}
}

func FindCounter(id string) (int, bool) {
	wcMu.RLock()
	defer wcMu.RUnlock()
	for _, v := range WcList {
		if v.Id == id {
			return v.Total, true
		}
	}
	return 0, false
}

type wsOutput struct {
	ws *websocket.Conn
	mu sync.Mutex
}

func (w *wsOutput) Write(p []byte) (int, error) {
	if !utf8.Valid(p) {
		bufStr := string(p)
		buf := make([]rune, 0, len(bufStr))
		for _, r := range bufStr {
			if r == utf8.RuneError {
				buf = append(buf, []rune("@")...)
			} else {
				buf = append(buf, r)
			}
		}
		p = []byte(string(buf))
	}
	w.mu.Lock()
	err := w.ws.WriteMessage(websocket.TextMessage, p)
	w.mu.Unlock()
	return len(p), err
}

type SSHClient struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	Hostname   string `json:"hostname"`
	Port       int    `json:"port"`
	LoginType  int    `json:"logintype"`
	PrivateKey string `json:"privateKey"`
	Passphrase string `json:"passphrase"`
	ProxyHost  string `json:"proxyHost"`
	ProxyPort  int    `json:"proxyPort"`
	ProxyUser  string `json:"proxyUser"`
	ProxyPass  string `json:"proxyPass"`
	Client     *ssh.Client
	Sftp       *sftp.Client
	StdinPipe  io.WriteCloser
	Session    *ssh.Session
}

func NewSSHClient() SSHClient {
	client := SSHClient{}
	client.Port = 22
	return client
}

func (sclient *SSHClient) Close() {
	defer func() {
		if err := recover(); err != nil {
			log.Println("SSHClient Close recover from panic: ", err)
		}
	}()
	if sclient.StdinPipe != nil {
		sclient.StdinPipe.Close()
		sclient.StdinPipe = nil
	}
	if sclient.Session != nil {
		sclient.Session.Close()
		sclient.Session = nil
	}
	if sclient.Sftp != nil {
		sclient.Sftp.Close()
		sclient.Sftp = nil
	}
	if sclient.Client != nil {
		sclient.Client.Close()
		sclient.Client = nil
	}
}
