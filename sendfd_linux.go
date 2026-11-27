//go:build linux && cgo

package main

import (
	"log"

	"golang.org/x/sys/unix"
)

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