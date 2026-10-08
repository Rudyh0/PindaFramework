package main

import (
	"crypto/subtle"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	sessionCookie = "pinda_host"
	loginCookie   = "pinda_host_login"
)

func (a *App) registerAuth(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/state", a.api(public, a.handleState))
	mux.HandleFunc("POST /api/login", a.api(public, a.handleLogin))
	mux.HandleFunc("POST /api/login/verify", a.api(public, a.handleLoginVerify))
	mux.HandleFunc("POST /api/login/password", a.api(public, a.handleLoginPassword))
	mux.HandleFunc("POST /api/logout", a.api(member, a.handleLogout))
	mux.HandleFunc("POST /api/account/password", a.api(member, a.handleChangePassword))
	mux.HandleFunc("POST /api/account/logout-everywhere", a.api(member, a.handleLogoutEverywhere))
}

func (a *App) setCookie(w http.ResponseWriter, r *http.Request, name, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name: name, Value: value, Path: "/", MaxAge: maxAge,
		HttpOnly: true, Secure: secureRequest(r), SameSite: http.SameSiteStrictMode,
	})
}

func (a *App) currentSession(r *http.Request) (*Session, User, bool) {
	cookie, err := r.Cookie(sessionCookie)
	if err != nil {
		return nil, User{}, false
	}
	config := a.config.get()
	session := a.sessions.get(cookie.Value, time.Duration(config.SessionHours)*time.Hour, time.Duration(config.IdleMinutes)*time.Minute)
	if session == nil {
		return nil, User{}, false
	}
	user, ok := a.users.get(session.User)
	if !ok || user.Disabled || user.TOTPSecret == "" || user.MustChangePassword {
		a.sessions.remove(session.Token)
		return nil, User{}, false
	}
	return session, user, true
}

// me: wat de pagina over de ingelogde gebruiker moet weten.
func me(user User) map[string]any {
	return map[string]any{"name": user.Name, "admin": user.Admin, "lastLogin": user.LastLogin}
}

func (a *App) handleState(q *Request) (any, error) {
	config := a.config.get()
	state := map[string]any{
		"version":    Version,
		"mode":       config.Mode,
		"domain":     config.Domain,
		"serverName": config.ServerName,
		"needsSetup": a.users.count() == 0,
		"setupDone":  config.SetupDone,
		"user":       nil,
	}
	if _, user, ok := a.currentSession(q.r); ok {
		state["user"] = me(user)
	}
	return state, nil
}

// tooMany: te veel mislukte pogingen vanaf dit IP (of op deze naam)?
func (a *App) tooMany(keys ...string) error {
	for _, key := range keys {
		limit := 10
		if strings.HasPrefix(key, "user:") {
			limit = 8
		}
		if a.limiter.blocked(key, limit, 15*time.Minute) {
			return &apiError{http.StatusTooManyRequests, "Te veel mislukte pogingen. Probeer het over een kwartier opnieuw."}
		}
	}
	return nil
}

func (a *App) issuer() string {
	config := a.config.get()
	if config.ServerName != "" {
		return config.ServerName + " dev"
	}
	return "PindaHost"
}

// challengeResponse: wat de browser nodig heeft voor de 2FA-stap.
func (a *App) challengeResponse(challenge *Challenge, account string) (map[string]any, error) {
	response := map[string]any{"step": "code", "account": account, "attemptsLeft": challengeAttempts - challenge.Attempts}
	if challenge.Secret != "" {
		qr, err := totpQR(totpURI(a.issuer(), account, challenge.Secret))
		if err != nil {
			return nil, err
		}
		response["step"] = "setup"
		response["qr"] = qr
		response["secret"] = groupSecret(challenge.Secret)
		response["issuer"] = a.issuer()
	}
	return response, nil
}

func (a *App) handleLogin(q *Request) (any, error) {
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(body.Username)
	ipKey, userKey := "ip:"+q.ip, "user:"+strings.ToLower(name)
	if err := a.tooMany(ipKey, userKey); err != nil {
		return nil, err
	}
	user, exists := a.users.get(name)
	hash := dummyHash
	if exists {
		hash = user.PasswordHash
	}
	if !verifyPassword(hash, body.Password) || !exists {
		a.limiter.fail(ipKey)
		a.limiter.fail(userKey)
		a.audit.add(name, q.ip, "inloggen mislukt", "", "verkeerde naam of wachtwoord")
		return nil, &apiError{http.StatusUnauthorized, "Gebruikersnaam of wachtwoord klopt niet."}
	}
	if user.Disabled {
		return nil, &apiError{http.StatusForbidden, "Dit account staat uit. Vraag een beheerder om het weer aan te zetten."}
	}
	challenge := &Challenge{User: user.Name, MustChange: user.MustChangePassword, IP: q.ip}
	if user.TOTPSecret == "" {
		challenge.Secret = newTOTPSecret()
	}
	challenge = a.sessions.newChallenge(challenge)
	a.setCookie(q.w, q.r, loginCookie, challenge.Token, int(challengeLifetime.Seconds()))
	return a.challengeResponse(challenge, user.Name)
}

func (a *App) loginChallenge(q *Request) (*Challenge, error) {
	cookie, err := q.r.Cookie(loginCookie)
	if err != nil {
		return nil, &apiError{http.StatusUnauthorized, "Je inlogpoging is verlopen. Begin opnieuw."}
	}
	challenge := a.sessions.challenge(cookie.Value)
	if challenge == nil {
		return nil, &apiError{http.StatusUnauthorized, "Je inlogpoging is verlopen. Begin opnieuw."}
	}
	return challenge, nil
}

// checkCode controleert de 2FA-code van een inlogpoging en telt mislukte pogingen.
func (a *App) checkCode(q *Request, challenge *Challenge, code string) (int64, error) {
	if err := a.tooMany("ip:" + q.ip); err != nil {
		return 0, err
	}
	secret := challenge.Secret
	var lastStep int64
	if secret == "" {
		user, ok := a.users.get(challenge.User)
		if !ok {
			return 0, &apiError{http.StatusUnauthorized, "Je inlogpoging is verlopen. Begin opnieuw."}
		}
		secret, lastStep = user.TOTPSecret, user.TOTPLastStep
	}
	step, ok := verifyTOTP(secret, code, lastStep, time.Now())
	if ok {
		return step, nil
	}
	a.limiter.fail("ip:" + q.ip)
	attempts := challenge.Attempts + 1
	a.sessions.updateChallenge(challenge.Token, func(c *Challenge) { c.Attempts = attempts })
	if attempts >= challengeAttempts {
		a.sessions.removeChallenge(challenge.Token)
		a.audit.add(challenge.User, q.ip, "inloggen mislukt", "", "te vaak een verkeerde 2FA-code")
		return 0, &apiError{http.StatusUnauthorized, "Te vaak een verkeerde code. Begin opnieuw met inloggen."}
	}
	left := challengeAttempts - attempts
	return 0, badRequest("Deze code klopt niet. Je kunt het nog %d keer proberen.", left)
}

func (a *App) handleLoginVerify(q *Request) (any, error) {
	var body struct {
		Code string `json:"code"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	challenge, err := a.loginChallenge(q)
	if err != nil {
		return nil, err
	}
	if challenge.Setup {
		return nil, badRequest("Gebruik de setup om de eerste beheerder te maken.")
	}
	if challenge.Verified {
		return map[string]any{"step": "password"}, nil
	}
	step, err := a.checkCode(q, challenge, body.Code)
	if err != nil {
		return nil, err
	}
	_, err = a.users.update(challenge.User, func(user *User) error {
		if challenge.Secret != "" {
			user.TOTPSecret = challenge.Secret
		}
		user.TOTPLastStep = step
		return nil
	})
	if err != nil {
		return nil, err
	}
	if challenge.Secret != "" {
		a.audit.add(challenge.User, q.ip, "2fa ingesteld", "", "")
	}
	if challenge.MustChange {
		a.sessions.updateChallenge(challenge.Token, func(c *Challenge) {
			c.Verified = true
			c.Secret = ""
		})
		return map[string]any{"step": "password"}, nil
	}
	return a.finishLogin(q, challenge.User, challenge.Token)
}

func (a *App) handleLoginPassword(q *Request) (any, error) {
	var body struct {
		Password string `json:"password"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	challenge, err := a.loginChallenge(q)
	if err != nil {
		return nil, err
	}
	if !challenge.Verified || !challenge.MustChange {
		return nil, badRequest("Eerst de 2FA-code invullen.")
	}
	if err := checkPasswordStrength(body.Password, challenge.User); err != nil {
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	hash, err := hashPassword(body.Password)
	if err != nil {
		return nil, err
	}
	if _, err := a.users.update(challenge.User, func(user *User) error {
		if verifyPassword(user.PasswordHash, body.Password) {
			return badRequest("Kies een ander wachtwoord dan het tijdelijke.")
		}
		user.PasswordHash = hash
		user.MustChangePassword = false
		return nil
	}); err != nil {
		return nil, err
	}
	a.audit.add(challenge.User, q.ip, "wachtwoord gewijzigd", "", "na een tijdelijk wachtwoord")
	return a.finishLogin(q, challenge.User, challenge.Token)
}

func (a *App) finishLogin(q *Request, name, challengeToken string) (any, error) {
	a.sessions.removeChallenge(challengeToken)
	a.limiter.reset("user:" + strings.ToLower(name))
	user, err := a.users.update(name, func(user *User) error {
		user.LastLogin = time.Now().UnixMilli()
		return nil
	})
	if err != nil {
		return nil, err
	}
	session := a.sessions.create(user.Name, q.ip, q.r.UserAgent())
	a.setCookie(q.w, q.r, loginCookie, "", -1)
	a.setCookie(q.w, q.r, sessionCookie, session.Token, a.config.get().SessionHours*3600)
	a.audit.add(user.Name, q.ip, "inloggen", "", "")
	return map[string]any{"step": "done", "user": me(user)}, nil
}

func (a *App) handleLogout(q *Request) (any, error) {
	a.sessions.remove(q.session.Token)
	a.setCookie(q.w, q.r, sessionCookie, "", -1)
	q.log("uitloggen", "", "")
	return map[string]bool{"ok": true}, nil
}

func (a *App) handleLogoutEverywhere(q *Request) (any, error) {
	removed := a.sessions.removeUser(q.user.Name, q.session.Token)
	q.log("overal uitloggen", "", "")
	return map[string]int{"removed": removed}, nil
}

func (a *App) handleChangePassword(q *Request) (any, error) {
	var body struct {
		Current  string `json:"current"`
		Password string `json:"password"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if err := a.tooMany("ip:" + q.ip); err != nil {
		return nil, err
	}
	if !verifyPassword(q.user.PasswordHash, body.Current) {
		a.limiter.fail("ip:" + q.ip)
		return nil, badRequest("Je huidige wachtwoord klopt niet.")
	}
	if err := checkPasswordStrength(body.Password, q.user.Name); err != nil {
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	hash, err := hashPassword(body.Password)
	if err != nil {
		return nil, err
	}
	if _, err := a.users.update(q.user.Name, func(user *User) error {
		user.PasswordHash = hash
		return nil
	}); err != nil {
		return nil, err
	}
	removed := a.sessions.removeUser(q.user.Name, q.session.Token)
	q.log("wachtwoord gewijzigd", "", "")
	return map[string]int{"loggedOut": removed}, nil
}

func capitalize(text string) string {
	if text == "" {
		return text
	}
	return strings.ToUpper(text[:1]) + text[1:]
}

// ============================================================ de allereerste beheerder

func normalizeCode(code string) string {
	replacer := strings.NewReplacer("-", "", " ", "")
	return strings.ToUpper(replacer.Replace(strings.TrimSpace(code)))
}

func (a *App) handleSetupAccount(q *Request) (any, error) {
	var body struct {
		Code     string `json:"code"`
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	if a.users.count() > 0 {
		return nil, forbidden("Er is al een beheerder. Log gewoon in.")
	}
	if err := a.tooMany("ip:" + q.ip); err != nil {
		return nil, err
	}
	expected, err := a.setupCode()
	if err != nil {
		return nil, err
	}
	if subtle.ConstantTimeCompare([]byte(normalizeCode(body.Code)), []byte(normalizeCode(expected))) != 1 {
		a.limiter.fail("ip:" + q.ip)
		a.audit.add("", q.ip, "setup mislukt", "", "verkeerde setupcode")
		return nil, badRequest("Deze setupcode klopt niet. Je vindt hem in de installer, of met: sudo pinda-host setup-code")
	}
	name := strings.TrimSpace(body.Username)
	if !usernamePattern.MatchString(name) {
		return nil, badRequest("Een gebruikersnaam is 3 tot 32 tekens: letters, cijfers, punt, streepje of underscore.")
	}
	if err := checkPasswordStrength(body.Password, name); err != nil {
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	hash, err := hashPassword(body.Password)
	if err != nil {
		return nil, err
	}
	challenge := a.sessions.newChallenge(&Challenge{User: name, PasswordHash: hash, Secret: newTOTPSecret(), Setup: true, IP: q.ip})
	a.setCookie(q.w, q.r, loginCookie, challenge.Token, int(challengeLifetime.Seconds()))
	return a.challengeResponse(challenge, name)
}

func (a *App) handleSetupAccountVerify(q *Request) (any, error) {
	var body struct {
		Code string `json:"code"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	challenge, err := a.loginChallenge(q)
	if err != nil {
		return nil, err
	}
	if !challenge.Setup {
		return nil, badRequest("Ongeldig verzoek.")
	}
	if a.users.count() > 0 {
		a.sessions.removeChallenge(challenge.Token)
		return nil, forbidden("Er is al een beheerder. Log gewoon in.")
	}
	step, err := a.checkCode(q, challenge, body.Code)
	if err != nil {
		return nil, err
	}
	err = a.users.create(User{
		Name: challenge.User, PasswordHash: challenge.PasswordHash, TOTPSecret: challenge.Secret,
		TOTPLastStep: step, Admin: true, CreatedBy: "setup",
	})
	if err != nil {
		return nil, err
	}
	_ = os.Remove(filepath.Join(a.dataDir, "setup-code"))
	a.audit.add(challenge.User, q.ip, "setup", challenge.User, "eerste beheerder aangemaakt")
	return a.finishLogin(q, challenge.User, challenge.Token)
}
