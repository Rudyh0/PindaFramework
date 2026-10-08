package main

import (
	"crypto/rand"
	"encoding/hex"
	"net"
	"strings"
	"sync"
	"time"
)

// Session is een ingelogde browser.
type Session struct {
	Token    string
	User     string
	Created  time.Time
	LastSeen time.Time
	IP       string
	Agent    string
}

// Challenge is een half afgemaakte login: wachtwoord goed, nu nog de 2FA-code
// (en eventueel een nieuw wachtwoord).
type Challenge struct {
	Token string
	// De gebruiker; bij de allereerste setup bestaat die nog niet.
	User string
	// Bij het instellen van 2FA: het nieuwe geheim (pas bewaard als de code klopt).
	Secret string
	// Alleen bij de eerste setup: het gekozen wachtwoord (al gehasht).
	PasswordHash string
	Setup        bool
	Verified     bool
	MustChange   bool
	Attempts     int
	Expires      time.Time
	IP           string
}

const (
	challengeLifetime = 5 * time.Minute
	challengeAttempts = 5
)

type Sessions struct {
	mu         sync.Mutex
	sessions   map[string]*Session
	challenges map[string]*Challenge
}

func newSessions() *Sessions {
	s := &Sessions{sessions: map[string]*Session{}, challenges: map[string]*Challenge{}}
	go func() {
		for range time.Tick(time.Minute) {
			s.cleanup()
		}
	}()
	return s
}

func randomToken() string {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	return hex.EncodeToString(buf)
}

func (s *Sessions) create(user, ip, agent string) *Session {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	session := &Session{Token: randomToken(), User: user, Created: now, LastSeen: now, IP: ip, Agent: agent}
	s.sessions[session.Token] = session
	return session
}

// get geeft de sessie als hij nog geldig is, en houdt bij dat hij net gebruikt is.
func (s *Sessions) get(token string, maxAge, idle time.Duration) *Session {
	if token == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	session, ok := s.sessions[token]
	if !ok {
		return nil
	}
	now := time.Now()
	if now.Sub(session.Created) > maxAge || now.Sub(session.LastSeen) > idle {
		delete(s.sessions, token)
		return nil
	}
	session.LastSeen = now
	result := *session
	return &result
}

func (s *Sessions) remove(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.sessions, token)
}

// removeUser logt een gebruiker overal uit (behalve eventueel de huidige sessie).
func (s *Sessions) removeUser(user, except string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	removed := 0
	for token, session := range s.sessions {
		if strings.EqualFold(session.User, user) && token != except {
			delete(s.sessions, token)
			removed++
		}
	}
	return removed
}

func (s *Sessions) forUser(user string) []Session {
	s.mu.Lock()
	defer s.mu.Unlock()
	var list []Session
	for _, session := range s.sessions {
		if strings.EqualFold(session.User, user) {
			list = append(list, *session)
		}
	}
	return list
}

func (s *Sessions) newChallenge(challenge *Challenge) *Challenge {
	s.mu.Lock()
	defer s.mu.Unlock()
	challenge.Token = randomToken()
	challenge.Expires = time.Now().Add(challengeLifetime)
	s.challenges[challenge.Token] = challenge
	return challenge
}

// challenge geeft een kopie; met updateChallenge pas je hem aan.
func (s *Sessions) challenge(token string) *Challenge {
	if token == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	challenge, ok := s.challenges[token]
	if !ok || time.Now().After(challenge.Expires) {
		delete(s.challenges, token)
		return nil
	}
	result := *challenge
	return &result
}

func (s *Sessions) updateChallenge(token string, change func(*Challenge)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if challenge, ok := s.challenges[token]; ok {
		change(challenge)
	}
}

func (s *Sessions) removeChallenge(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.challenges, token)
}

func (s *Sessions) cleanup() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for token, challenge := range s.challenges {
		if now.After(challenge.Expires) {
			delete(s.challenges, token)
		}
	}
	// Sessies zonder activiteit van meer dan een dag zijn hoe dan ook verlopen.
	for token, session := range s.sessions {
		if now.Sub(session.LastSeen) > 24*time.Hour {
			delete(s.sessions, token)
		}
	}
}

// ============================================================ te veel pogingen

// RateLimiter telt pogingen per sleutel (IP-adres, gebruikersnaam, ...). Een poging telt al
// mee vóór de controle, zodat veel tegelijk proberen niets oplevert.
type RateLimiter struct {
	mu       sync.Mutex
	attempts map[string][]time.Time
}

func newRateLimiter() *RateLimiter {
	limiter := &RateLimiter{attempts: map[string][]time.Time{}}
	go func() {
		for range time.Tick(5 * time.Minute) {
			limiter.cleanup()
		}
	}()
	return limiter
}

// take telt een poging mee, behalve als er al te veel waren (dan false).
func (r *RateLimiter) take(key string, max int, window time.Duration) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	recent := r.recent(key, window)
	if len(recent) >= max {
		return false
	}
	r.attempts[key] = append(recent, time.Now())
	return true
}

// forgive haalt de laatste poging weg (hij lukte).
func (r *RateLimiter) forgive(key string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if list := r.attempts[key]; len(list) > 0 {
		r.attempts[key] = list[:len(list)-1]
	}
}

func (r *RateLimiter) reset(key string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.attempts, key)
}

func (r *RateLimiter) recent(key string, window time.Duration) []time.Time {
	cutoff := time.Now().Add(-window)
	list := r.attempts[key]
	kept := list[:0]
	for _, at := range list {
		if at.After(cutoff) {
			kept = append(kept, at)
		}
	}
	if len(kept) == 0 {
		delete(r.attempts, key)
		return nil
	}
	r.attempts[key] = kept
	return kept
}

func (r *RateLimiter) cleanup() {
	r.mu.Lock()
	defer r.mu.Unlock()
	for key := range r.attempts {
		r.recent(key, time.Hour)
	}
}

// ipGroup: een IPv6-adres telt per /64 (zo groot is een normale aansluiting), IPv4 per adres.
func ipGroup(address string) string {
	ip := net.ParseIP(address)
	if ip == nil || ip.To4() != nil {
		return address
	}
	return ip.Mask(net.CIDRMask(64, 128)).String() + "/64"
}
