package main

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// User is iemand die op het dev-paneel mag. 2FA is altijd verplicht.
type User struct {
	Name               string `json:"name"`
	PasswordHash       string `json:"passwordHash"`
	TOTPSecret         string `json:"totpSecret,omitempty"`
	TOTPLastStep       int64  `json:"totpLastStep,omitempty"`
	Admin              bool   `json:"admin"`
	MustChangePassword bool   `json:"mustChangePassword,omitempty"`
	Disabled           bool   `json:"disabled,omitempty"`
	Created            int64  `json:"created"`
	CreatedBy          string `json:"createdBy,omitempty"`
	LastLogin          int64  `json:"lastLogin,omitempty"`
}

var usernamePattern = regexp.MustCompile(`^[A-Za-z0-9_.-]{3,32}$`)

const (
	passwordIterations = 600_000
	minPasswordLength  = 10
)

var (
	errUserExists   = errors.New("deze gebruikersnaam bestaat al")
	errUserNotFound = errors.New("gebruiker niet gevonden")
)

// UserStore bewaart de gebruikers in users.json.
type UserStore struct {
	mu    sync.RWMutex
	path  string
	users map[string]*User // op naam in kleine letters
}

func openUsers(path string) (*UserStore, error) {
	store := &UserStore{path: path, users: map[string]*User{}}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store, nil
	}
	if err != nil {
		return nil, err
	}
	var list []*User
	if err := json.Unmarshal(data, &list); err != nil {
		return nil, fmt.Errorf("%s is geen geldige JSON: %w", path, err)
	}
	for _, user := range list {
		store.users[strings.ToLower(user.Name)] = user
	}
	return store, nil
}

func (s *UserStore) saveLocked() error {
	list := make([]*User, 0, len(s.users))
	for _, user := range s.users {
		list = append(list, user)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Created < list[j].Created })
	return saveJSON(s.path, list)
}

func (s *UserStore) count() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.users)
}

// get geeft een kopie, zodat niemand per ongeluk iets aanpast zonder op te slaan.
func (s *UserStore) get(name string) (User, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	user, ok := s.users[strings.ToLower(name)]
	if !ok {
		return User{}, false
	}
	return *user, true
}

func (s *UserStore) list() []User {
	s.mu.RLock()
	defer s.mu.RUnlock()
	list := make([]User, 0, len(s.users))
	for _, user := range s.users {
		list = append(list, *user)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Created < list[j].Created })
	return list
}

func (s *UserStore) admins() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	count := 0
	for _, user := range s.users {
		if user.Admin && !user.Disabled {
			count++
		}
	}
	return count
}

func (s *UserStore) create(user User) error {
	if !usernamePattern.MatchString(user.Name) {
		return errors.New("een gebruikersnaam is 3 tot 32 tekens: letters, cijfers, punt, streepje of underscore")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	key := strings.ToLower(user.Name)
	if _, exists := s.users[key]; exists {
		return errUserExists
	}
	user.Created = time.Now().UnixMilli()
	s.users[key] = &user
	if err := s.saveLocked(); err != nil {
		delete(s.users, key)
		return err
	}
	return nil
}

// update past een gebruiker aan en slaat op. Mislukt het opslaan, dan blijft alles zoals het was.
func (s *UserStore) update(name string, change func(*User) error) (User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	user, ok := s.users[strings.ToLower(name)]
	if !ok {
		return User{}, errUserNotFound
	}
	edited := *user
	if err := change(&edited); err != nil {
		return User{}, err
	}
	previous := *user
	*user = edited
	if err := s.saveLocked(); err != nil {
		*user = previous
		return User{}, err
	}
	return edited, nil
}

func (s *UserStore) remove(name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := strings.ToLower(name)
	user, ok := s.users[key]
	if !ok {
		return errUserNotFound
	}
	delete(s.users, key)
	if err := s.saveLocked(); err != nil {
		s.users[key] = user
		return err
	}
	return nil
}

func (s *UserStore) resetTwoFactor(name string) error {
	_, err := s.update(name, func(user *User) error {
		user.TOTPSecret = ""
		user.TOTPLastStep = 0
		return nil
	})
	return err
}

// resetPassword geeft een tijdelijk wachtwoord dat bij het inloggen veranderd moet worden.
func (s *UserStore) resetPassword(name string) (string, error) {
	password := randomPassword(16)
	hash, err := hashPassword(password)
	if err != nil {
		return "", err
	}
	_, err = s.update(name, func(user *User) error {
		user.PasswordHash = hash
		user.MustChangePassword = true
		return nil
	})
	return password, err
}

// ============================================================ wachtwoorden

func checkPasswordStrength(password, username string) error {
	if len([]rune(password)) < minPasswordLength {
		return fmt.Errorf("een wachtwoord is minimaal %d tekens", minPasswordLength)
	}
	if len(password) > 256 {
		return errors.New("dat wachtwoord is te lang")
	}
	if username != "" && strings.Contains(strings.ToLower(password), strings.ToLower(username)) {
		return errors.New("je wachtwoord mag je gebruikersnaam niet bevatten")
	}
	return nil
}

// hashPassword: PBKDF2-SHA256, opgeslagen als "pbkdf2-sha256$iteraties$zout$hash".
func hashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, password, salt, passwordIterations, 32)
	if err != nil {
		return "", err
	}
	return "pbkdf2-sha256$" + strconv.Itoa(passwordIterations) + "$" +
		base64.RawStdEncoding.EncodeToString(salt) + "$" + base64.RawStdEncoding.EncodeToString(key), nil
}

func verifyPassword(hash, password string) bool {
	parts := strings.Split(hash, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	iterations, err := strconv.Atoi(parts[1])
	if err != nil || iterations < 1 {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[2])
	if err != nil {
		return false
	}
	expected, err := base64.RawStdEncoding.DecodeString(parts[3])
	if err != nil {
		return false
	}
	key, err := pbkdf2.Key(sha256.New, password, salt, iterations, len(expected))
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(key, expected) == 1
}

// dummyHash maakt inloggen met een onbekende naam even traag als met een bekende.
var dummyHash, _ = hashPassword("geen-echt-wachtwoord")

// randomPassword: zonder tekens die op elkaar lijken, en zonder voorkeur voor bepaalde tekens.
func randomPassword(length int) string {
	const alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	limit := 256 - 256%len(alphabet)
	out := make([]byte, 0, length)
	buf := make([]byte, 1)
	for len(out) < length {
		if _, err := rand.Read(buf); err != nil {
			panic(err)
		}
		if int(buf[0]) < limit {
			out = append(out, alphabet[int(buf[0])%len(alphabet)])
		}
	}
	return string(out)
}
