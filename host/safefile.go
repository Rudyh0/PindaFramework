package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"syscall"
)

// Het paneel draait als root, maar de map van de Minecraft-server is van de gebruiker
// "minecraft". Een plugin (of wie die gebruiker overneemt) kan daar een symlink neerzetten,
// bijvoorbeeld database.yml -> /etc/shadow. Daarom lopen we vanaf de basismap map voor map
// en volgen we nergens een symlink: zo lezen en schrijven we alleen echt in die map.

var errNotRegular = errors.New("geen gewoon bestand")

// safeName: alleen een bestandsnaam, geen pad.
func safeName(name string) bool {
	return name != "" && name != "." && name != ".." && !strings.ContainsAny(name, "/\\\x00")
}

// openDirAt opent base/rel zonder symlinks te volgen (base zelf is van ons en vertrouwd).
// Met create worden ontbrekende mappen gemaakt, van uid/gid (als die >= 0 zijn).
func openDirAt(base, rel string, create bool, uid, gid int) (int, error) {
	if create {
		if err := os.MkdirAll(base, 0o755); err != nil {
			return -1, err
		}
	}
	fd, err := syscall.Open(base, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_CLOEXEC, 0)
	if err != nil {
		return -1, &os.PathError{Op: "open", Path: base, Err: err}
	}
	where := base
	for _, part := range strings.Split(rel, "/") {
		if part == "" || part == "." {
			continue
		}
		where += "/" + part
		if !safeName(part) {
			syscall.Close(fd)
			return -1, &os.PathError{Op: "open", Path: where, Err: syscall.EINVAL}
		}
		next, err := openSubdir(fd, part)
		if err == syscall.ENOENT && create {
			made := syscall.Mkdirat(fd, part, 0o755)
			if made != nil && made != syscall.EEXIST {
				syscall.Close(fd)
				return -1, &os.PathError{Op: "mkdir", Path: where, Err: made}
			}
			next, err = openSubdir(fd, part)
			// Alleen een map die we net zelf maakten geven we aan de Minecraft-gebruiker.
			if err == nil && made == nil && uid >= 0 {
				_ = syscall.Fchown(next, uid, gid)
			}
		}
		syscall.Close(fd)
		if err != nil {
			return -1, &os.PathError{Op: "open", Path: where, Err: err}
		}
		fd = next
	}
	return fd, nil
}

func openSubdir(dir int, name string) (int, error) {
	for {
		fd, err := syscall.Openat(dir, name, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
		if err != syscall.EINTR {
			return fd, err
		}
	}
}

// openFileAt opent een gewoon bestand (geen symlink, map, fifo, ...) om te lezen.
func openFileAt(base, rel, name string) (*os.File, error) {
	if !safeName(name) {
		return nil, &os.PathError{Op: "open", Path: name, Err: syscall.EINVAL}
	}
	dir, err := openDirAt(base, rel, false, -1, -1)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(dir)
	var fd int
	for {
		// O_NONBLOCK: een fifo laat ons anders eeuwig wachten.
		fd, err = syscall.Openat(dir, name, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK|syscall.O_CLOEXEC, 0)
		if err != syscall.EINTR {
			break
		}
	}
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: name, Err: err}
	}
	file := os.NewFile(uintptr(fd), name)
	info, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, err
	}
	if !info.Mode().IsRegular() {
		file.Close()
		return nil, &os.PathError{Op: "open", Path: name, Err: errNotRegular}
	}
	return file, nil
}

// readFileAt leest base/rel/name, maar nooit meer dan max bytes (anders een fout).
func readFileAt(base, rel, name string, max int64) ([]byte, error) {
	file, err := openFileAt(base, rel, name)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > max {
		return nil, fmt.Errorf("%s is te groot (meer dan %d bytes)", name, max)
	}
	return data, nil
}

// fileExistsAt: staat er een gewoon bestand met deze naam in base/rel?
func fileExistsAt(base, rel, name string) bool {
	file, err := openFileAt(base, rel, name)
	if err != nil {
		return false
	}
	file.Close()
	return true
}

// writeFileAt schrijft base/rel/name in één keer (eerst een tijdelijk bestand, dan hernoemen),
// alleen leesbaar voor de eigenaar. Met uid/gid >= 0 wordt het bestand van die gebruiker.
func writeFileAt(base, rel, name string, data []byte, uid, gid int) error {
	if !safeName(name) {
		return &os.PathError{Op: "write", Path: name, Err: syscall.EINVAL}
	}
	dir, err := openDirAt(base, rel, true, uid, gid)
	if err != nil {
		return err
	}
	defer syscall.Close(dir)
	temp := "." + name + "." + strings.ToLower(randomCode(10))
	fd, err := syscall.Openat(dir, temp, syscall.O_WRONLY|syscall.O_CREAT|syscall.O_EXCL|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0o600)
	if err != nil {
		return &os.PathError{Op: "create", Path: temp, Err: err}
	}
	file := os.NewFile(uintptr(fd), temp)
	fail := func(err error) error {
		file.Close()
		_ = syscall.Unlinkat(dir, temp)
		return err
	}
	if _, err := file.Write(data); err != nil {
		return fail(err)
	}
	if uid >= 0 {
		if err := file.Chown(uid, gid); err != nil {
			return fail(err)
		}
	}
	if err := file.Sync(); err != nil {
		return fail(err)
	}
	if err := file.Close(); err != nil {
		_ = syscall.Unlinkat(dir, temp)
		return err
	}
	// rename vervangt een eventuele symlink zelf; hij volgt hem niet.
	if err := syscall.Renameat(dir, temp, dir, name); err != nil {
		_ = syscall.Unlinkat(dir, temp)
		return &os.PathError{Op: "rename", Path: name, Err: err}
	}
	_ = syscall.Fsync(dir)
	return nil
}
