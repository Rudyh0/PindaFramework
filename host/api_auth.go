package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	sessionCookie = "pinda_host"
	loginCookie   = "pinda_host_login"
	deviceCookie  = "pinda_host_device"
	deviceMaxAge  = 180 * 24 * 3600
)

// Grenzen per kwartier: per IP-adres, per naam vanaf één IP, per naam in totaal, per bekend
// apparaat en 2FA-codes per naam. Een bekend apparaat telt alleen voor zichzelf: zo kan een
// aanvaller met veel IP-adressen jou niet buitensluiten.
const (
	limitWindow    = 15 * time.Minute
	limitPerIP     = 10
	limitUserPerIP = 8
	limitUser      = 50
	limitDevice    = 10
	limitCodes     = 10
)

var errTooMany = &apiError{http.StatusTooManyRequests, "Te veel pogingen. Probeer het over een kwartier opnieuw."}

// hashSlots: wachtwoorden controleren kost bewust veel rekenwerk; hooguit twee tegelijk, zodat
// een stortvloed aan inlogpogingen de server niet platlegt. Bekende apparaten en ingelogde
// gebruikers hebben een eigen plek, zodat zo'n stortvloed hen niet tegenhoudt.
var (
	hashSlots    = make(chan struct{}, 2)
	trustedSlots = make(chan struct{}, 1)
)

func withHashSlot(trusted bool, work func()) error {
	slots := hashSlots
	if trusted {
		slots = trustedSlots
	}
	select {
	case slots <- struct{}{}:
		defer func() { <-slots }()
		work()
		return nil
	case <-time.After(10 * time.Second):
		return &apiError{http.StatusTooManyRequests, "Het is even te druk. Probeer het zo opnieuw."}
	}
}

func deviceHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// knownDevice: de hash van het apparaat-token als deze browser eerder volledig als deze
// gebruiker inlogde, anders "".
func knownDevice(r *http.Request, user User) string {
	cookie, err := r.Cookie(deviceCookie)
	if err != nil || len(cookie.Value) != 64 {
		return ""
	}
	hash := deviceHash(cookie.Value)
	if _, ok := user.Devices[hash]; ok {
		return hash
	}
	return ""
}

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

var errBadLogin = &apiError{http.StatusUnauthorized, "Gebruikersnaam of wachtwoord klopt niet."}

func (a *App) handleLogin(q *Request) (any, error) {
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	name := strings.ToLower(strings.TrimSpace(body.Username))
	if !usernamePattern.MatchString(name) || len(body.Password) > 256 {
		if !a.limiter.take("ip:"+ipGroup(q.ip), limitPerIP, limitWindow) {
			return nil, errTooMany
		}
		return nil, errBadLogin
	}
	user, exists := a.users.get(name)
	device := ""
	if exists {
		device = knownDevice(q.r, user)
	}
	// Een bekend apparaat telt alleen voor zichzelf. Anders: per IP, per naam vanaf dit IP en
	// per naam in totaal.
	var keys []string
	if device != "" {
		keys = []string{"device:" + device}
		if !a.limiter.take(keys[0], limitDevice, limitWindow) {
			return nil, errTooMany
		}
	} else {
		keys = []string{"ip:" + ipGroup(q.ip), "user:" + name + "|" + ipGroup(q.ip), "user:" + name}
		if !a.limiter.take(keys[0], limitPerIP, limitWindow) || !a.limiter.take(keys[1], limitUserPerIP, limitWindow) ||
			!a.limiter.take(keys[2], limitUser, limitWindow) {
			return nil, errTooMany
		}
	}
	hash := dummyHash
	if exists {
		hash = user.PasswordHash
	}
	valid := false
	if err := withHashSlot(device != "", func() { valid = verifyPassword(hash, body.Password) }); err != nil {
		return nil, err
	}
	if !valid || !exists {
		a.audit.add(name, q.ip, "inloggen mislukt", "", "verkeerde naam of wachtwoord")
		return nil, errBadLogin
	}
	for _, key := range keys {
		a.limiter.forgive(key)
	}
	if user.Disabled {
		return nil, &apiError{http.StatusForbidden, "Dit account staat uit. Vraag een beheerder om het weer aan te zetten."}
	}
	challenge := &Challenge{User: user.Name, MustChange: user.MustChangePassword, IP: q.ip, Generation: user.Generation, Device: device}
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

// checkCode controleert de 2FA-code van een inlogpoging. Pogingen tellen per IP, per naam en
// per inlogpoging (na 5 keer fout moet je opnieuw beginnen).
func (a *App) checkCode(q *Request, challenge *Challenge, code string) (int64, error) {
	ipKey, codeKey := "ip:"+ipGroup(q.ip), "2fa:"+strings.ToLower(challenge.User)
	if !a.limiter.take(ipKey, limitPerIP, limitWindow) || !a.limiter.take(codeKey, limitCodes, limitWindow) {
		return 0, errTooMany
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
		a.limiter.forgive(ipKey)
		a.limiter.forgive(codeKey)
		return step, nil
	}
	attempts := challenge.Attempts + 1
	a.sessions.updateChallenge(challenge.Token, func(c *Challenge) { c.Attempts = attempts })
	if attempts >= challengeAttempts {
		a.sessions.removeChallenge(challenge.Token)
		a.audit.add(challenge.User, q.ip, "inloggen mislukt", "", "te vaak een verkeerde 2FA-code")
		return 0, &apiError{http.StatusUnauthorized, "Te vaak een verkeerde code. Begin opnieuw met inloggen."}
	}
	return 0, badRequest("Deze code klopt niet. Je kunt het nog %d keer proberen.", challengeAttempts-attempts)
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
	// Alles opnieuw controleren onder het slot van de gebruikers: zo telt dezelfde code maar
	// één keer, ook als hij twee keer tegelijk binnenkomt.
	_, err = a.users.update(challenge.User, func(user *User) error {
		if user.Disabled {
			return &apiError{http.StatusForbidden, "Dit account staat uit."}
		}
		if user.Generation != challenge.Generation {
			return errChanged
		}
		if challenge.Secret != "" {
			if user.TOTPSecret != "" {
				return &apiError{http.StatusConflict, "Voor dit account is net al 2FA ingesteld. Log opnieuw in."}
			}
			user.TOTPSecret = challenge.Secret
		} else if user.TOTPSecret == "" {
			return &apiError{http.StatusUnauthorized, "De 2FA van dit account is gereset. Log opnieuw in."}
		}
		if step <= user.TOTPLastStep {
			return badRequest("Deze code is al gebruikt. Wacht op de volgende code.")
		}
		user.TOTPLastStep = step
		return nil
	})
	if err != nil {
		return nil, a.loginUpdateError(challenge, err)
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
	return a.finishLogin(q, challenge)
}

// errChanged: het account is tijdens het inloggen gereset (of het wachtwoord veranderd).
var errChanged = &apiError{http.StatusUnauthorized, "Je account is net gewijzigd (bijvoorbeeld een reset). Log opnieuw in."}

// loginUpdateError: bij een fout die betekent dat deze inlogpoging niet meer kan, wordt hij
// weggegooid.
func (a *App) loginUpdateError(challenge *Challenge, err error) error {
	var api *apiError
	if errors.Is(err, errUserNotFound) || (errors.As(err, &api) && (api.status == http.StatusUnauthorized || api.status == http.StatusForbidden)) {
		a.sessions.removeChallenge(challenge.Token)
	}
	if errors.Is(err, errUserNotFound) {
		return &apiError{http.StatusUnauthorized, "Je inlogpoging is verlopen. Begin opnieuw."}
	}
	return err
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
	var hash string
	var hashErr error
	if err := withHashSlot(true, func() { hash, hashErr = hashPassword(body.Password) }); err != nil {
		return nil, err
	}
	if hashErr != nil {
		return nil, hashErr
	}
	if _, err := a.users.update(challenge.User, func(user *User) error {
		if user.Disabled {
			return &apiError{http.StatusForbidden, "Dit account staat uit."}
		}
		if user.Generation != challenge.Generation || user.TOTPSecret == "" {
			return errChanged
		}
		if verifyPassword(user.PasswordHash, body.Password) {
			return badRequest("Kies een ander wachtwoord dan het tijdelijke.")
		}
		user.PasswordHash = hash
		user.MustChangePassword = false
		return nil
	}); err != nil {
		return nil, a.loginUpdateError(challenge, err)
	}
	a.audit.add(challenge.User, q.ip, "wachtwoord gewijzigd", "", "na een tijdelijk wachtwoord")
	return a.finishLogin(q, challenge)
}

// finishLogin maakt de sessie. De browser wordt (of blijft) een bekend apparaat.
func (a *App) finishLogin(q *Request, challenge *Challenge) (any, error) {
	a.sessions.removeChallenge(challenge.Token)
	newDevice := ""
	user, err := a.users.update(challenge.User, func(user *User) error {
		if user.Disabled {
			return &apiError{http.StatusForbidden, "Dit account staat uit."}
		}
		if user.Generation != challenge.Generation {
			return errChanged
		}
		now := time.Now().UnixMilli()
		user.LastLogin = now
		device := challenge.Device
		if _, known := user.Devices[device]; device == "" || !known {
			newDevice = randomToken()
			device = deviceHash(newDevice)
		}
		user.rememberDevice(device, now)
		return nil
	})
	if err != nil {
		return nil, a.loginUpdateError(challenge, err)
	}
	session := a.sessions.create(user.Name, q.ip, q.r.UserAgent())
	a.setCookie(q.w, q.r, loginCookie, "", -1)
	a.setCookie(q.w, q.r, sessionCookie, session.Token, a.config.get().SessionHours*3600)
	if newDevice != "" {
		a.setCookie(q.w, q.r, deviceCookie, newDevice, deviceMaxAge)
	}
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
	ipKey := "ip:" + ipGroup(q.ip)
	if !a.limiter.take(ipKey, limitPerIP, limitWindow) {
		return nil, errTooMany
	}
	if err := checkPasswordStrength(body.Password, q.user.Name); err != nil {
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	var valid bool
	var hash string
	var hashErr error
	if err := withHashSlot(true, func() {
		valid = verifyPassword(q.user.PasswordHash, body.Current)
		if valid {
			hash, hashErr = hashPassword(body.Password)
		}
	}); err != nil {
		return nil, err
	}
	if !valid {
		return nil, badRequest("Je huidige wachtwoord klopt niet.")
	}
	a.limiter.forgive(ipKey)
	if hashErr != nil {
		return nil, hashErr
	}
	if _, err := a.users.update(q.user.Name, func(user *User) error {
		user.PasswordHash = hash
		// Inlogpogingen die nog met het oude wachtwoord begonnen zijn, tellen niet meer.
		user.Generation++
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
	ipKey := "ip:" + ipGroup(q.ip)
	if !a.limiter.take(ipKey, limitPerIP, limitWindow) || !a.limiter.take("setup", 30, limitWindow) {
		return nil, errTooMany
	}
	expected, err := a.setupCode()
	if err != nil {
		return nil, err
	}
	if expected == "" || subtle.ConstantTimeCompare([]byte(normalizeCode(body.Code)), []byte(normalizeCode(expected))) != 1 {
		a.audit.add("", q.ip, "setup mislukt", "", "verkeerde setupcode")
		return nil, badRequest("Deze setupcode klopt niet. Je vindt hem in de installer, of met: sudo pinda-host setup-code")
	}
	a.limiter.forgive(ipKey)
	name := strings.TrimSpace(body.Username)
	if !usernamePattern.MatchString(name) {
		return nil, badRequest("Een gebruikersnaam is 3 tot 32 tekens: letters, cijfers, punt, streepje of underscore.")
	}
	if err := checkPasswordStrength(body.Password, name); err != nil {
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	var hash string
	var hashErr error
	if err := withHashSlot(false, func() { hash, hashErr = hashPassword(body.Password) }); err != nil {
		return nil, err
	}
	if hashErr != nil {
		return nil, hashErr
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
	err = a.users.createFirst(User{
		Name: challenge.User, PasswordHash: challenge.PasswordHash, TOTPSecret: challenge.Secret,
		TOTPLastStep: step, Admin: true, CreatedBy: "setup",
	})
	if err != nil {
		a.sessions.removeChallenge(challenge.Token)
		return nil, forbidden("Er is al een beheerder. Log gewoon in.")
	}
	_ = os.Remove(filepath.Join(a.dataDir, "setup-code"))
	a.audit.add(challenge.User, q.ip, "setup", challenge.User, "eerste beheerder aangemaakt")
	return a.finishLogin(q, challenge)
}
