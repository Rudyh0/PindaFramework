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
	"log"
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
	// Generation gaat omhoog bij elke reset of wachtwoordwijziging. Een inlogpoging die daarvoor
	// begon, kan daarna niet meer worden afgemaakt.
	Generation int64 `json:"generation,omitempty"`
	// Devices: apparaten waarop deze gebruiker eerder helemaal (met 2FA) inlogde, als SHA-256
	// van het apparaat-token, met wanneer ze voor het laatst gebruikt zijn. Zo'n apparaat telt
	// niet mee in de grens per gebruiker, zodat een aanvaller je niet buiten kan sluiten.
	Devices map[string]int64 `json:"devices,omitempty"`
}

const maxDevices = 10

// rememberDevice zet een apparaat in de lijst (of werkt "laatst gebruikt" bij) en ruimt de
// oudste op als het er te veel worden.
func (u *User) rememberDevice(hash string, now int64) {
	if u.Devices == nil {
		u.Devices = map[string]int64{}
	}
	u.Devices[hash] = now
	for len(u.Devices) > maxDevices {
		oldest, when := "", int64(0)
		for key, used := range u.Devices {
			if oldest == "" || used < when {
				oldest, when = key, used
			}
		}
		delete(u.Devices, oldest)
	}
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

// UserStore bewaart de gebruikers in users.json. Verandert dat bestand van buitenaf (bijv. door
// "pinda-host reset-2fa" op de opdrachtregel terwijl de dienst draait), dan wordt het opnieuw gelezen.
type UserStore struct {
	mu      sync.Mutex
	path    string
	users   map[string]*User // op naam in kleine letters
	modTime time.Time
	size    int64
}

var errLastAdmin = errors.New("er moet altijd minstens één beheerder overblijven die aan staat")

func openUsers(path string) (*UserStore, error) {
	store := &UserStore{path: path, users: map[string]*User{}}
	if err := store.loadLocked(); err != nil {
		return nil, err
	}
	return store, nil
}

// loadLocked leest users.json als het sinds de vorige keer veranderd is.
func (s *UserStore) loadLocked() error {
	info, err := os.Stat(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if info.ModTime().Equal(s.modTime) && info.Size() == s.size {
		return nil
	}
	data, err := os.ReadFile(s.path)
	if err != nil {
		return err
	}
	var list []*User
	if err := json.Unmarshal(data, &list); err != nil {
		return fmt.Errorf("%s is geen geldige JSON: %w", s.path, err)
	}
	users := map[string]*User{}
	for _, user := range list {
		users[strings.ToLower(user.Name)] = user
	}
	s.users, s.modTime, s.size = users, info.ModTime(), info.Size()
	return nil
}

// lock pakt het slot en leest het bestand opnieuw als dat nodig is.
func (s *UserStore) lock() {
	s.mu.Lock()
	if err := s.loadLocked(); err != nil {
		log.Printf("users.json kon niet opnieuw gelezen worden (de vorige versie blijft gelden): %v", err)
	}
}

func (s *UserStore) saveLocked() error {
	list := make([]*User, 0, len(s.users))
	for _, user := range s.users {
		list = append(list, user)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Created < list[j].Created })
	if err := saveJSON(s.path, list); err != nil {
		return err
	}
	if info, err := os.Stat(s.path); err == nil {
		s.modTime, s.size = info.ModTime(), info.Size()
	}
	return nil
}

func (s *UserStore) count() int {
	s.lock()
	defer s.mu.Unlock()
	return len(s.users)
}

// get geeft een kopie, zodat niemand per ongeluk iets aanpast zonder op te slaan.
func (s *UserStore) get(name string) (User, bool) {
	s.lock()
	defer s.mu.Unlock()
	user, ok := s.users[strings.ToLower(name)]
	if !ok {
		return User{}, false
	}
	return *user, true
}

func (s *UserStore) list() []User {
	s.lock()
	defer s.mu.Unlock()
	list := make([]User, 0, len(s.users))
	for _, user := range s.users {
		list = append(list, *user)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Created < list[j].Created })
	return list
}

func (s *UserStore) adminsLocked() int {
	count := 0
	for _, user := range s.users {
		if user.Admin && !user.Disabled {
			count++
		}
	}
	return count
}

func (s *UserStore) admins() int {
	s.lock()
	defer s.mu.Unlock()
	return s.adminsLocked()
}

func (s *UserStore) create(user User) error {
	return s.add(user, false)
}

// createFirst maakt de allereerste beheerder, alleen als er nog niemand is.
func (s *UserStore) createFirst(user User) error {
	return s.add(user, true)
}

func (s *UserStore) add(user User, onlyFirst bool) error {
	if !usernamePattern.MatchString(user.Name) {
		return errors.New("een gebruikersnaam is 3 tot 32 tekens: letters, cijfers, punt, streepje of underscore")
	}
	s.lock()
	defer s.mu.Unlock()
	if onlyFirst && len(s.users) > 0 {
		return errors.New("er is al een beheerder")
	}
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

// update past een gebruiker aan en slaat op. Mislukt het opslaan, of zou er geen beheerder meer
// overblijven, dan blijft alles zoals het was.
func (s *UserStore) update(name string, change func(*User) error) (User, error) {
	s.lock()
	defer s.mu.Unlock()
	user, ok := s.users[strings.ToLower(name)]
	if !ok {
		return User{}, errUserNotFound
	}
	adminsBefore := s.adminsLocked()
	edited := *user
	if err := change(&edited); err != nil {
		return User{}, err
	}
	previous := *user
	*user = edited
	if adminsBefore > 0 && s.adminsLocked() == 0 {
		*user = previous
		return User{}, errLastAdmin
	}
	if err := s.saveLocked(); err != nil {
		*user = previous
		return User{}, err
	}
	return edited, nil
}

func (s *UserStore) remove(name string) error {
	s.lock()
	defer s.mu.Unlock()
	key := strings.ToLower(name)
	user, ok := s.users[key]
	if !ok {
		return errUserNotFound
	}
	adminsBefore := s.adminsLocked()
	delete(s.users, key)
	if adminsBefore > 0 && s.adminsLocked() == 0 {
		s.users[key] = user
		return errLastAdmin
	}
	if err := s.saveLocked(); err != nil {
		s.users[key] = user
		return err
	}
	return nil
}

// resetTwoFactor haalt de 2FA weg en geeft ook een tijdelijk wachtwoord: zo kan alleen wie dat
// nieuwe wachtwoord krijgt een nieuwe authenticator-app koppelen (en niet iemand die het oude
// wachtwoord toevallig kent).
func (s *UserStore) resetTwoFactor(name string) (string, error) {
	password := randomPassword(16)
	hash, err := hashPassword(password)
	if err != nil {
		return "", err
	}
	_, err = s.update(name, func(user *User) error {
		user.TOTPSecret = ""
		user.TOTPLastStep = 0
		user.PasswordHash = hash
		user.MustChangePassword = true
		user.Generation++
		user.Devices = nil
		return nil
	})
	return password, err
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
		user.Generation++
		user.Devices = nil
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
