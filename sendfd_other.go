//go:build !linux || !cgo

package main

// sendFdViaUnix stub for non-Linux platforms (Windows, etc.)
func sendFdViaUnix(socketFd uintptr, fdToSend int) bool {
	return false
}