package main

import (
	"errors"
	"net/http"
	"strings"
)

func (a *App) registerUsers(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/users", a.api(admin, a.handleUsers))
	mux.HandleFunc("POST /api/users", a.api(admin, a.handleCreateUser))
	mux.HandleFunc("POST /api/users/{name}/{action}", a.api(admin, a.handleUserAction))
	mux.HandleFunc("GET /api/audit", a.api(admin, a.handleAudit))
}

func userView(user User, sessions []Session) map[string]any {
	return map[string]any{
		"name":               user.Name,
		"admin":              user.Admin,
		"disabled":           user.Disabled,
		"twoFactor":          user.TOTPSecret != "",
		"mustChangePassword": user.MustChangePassword,
		"created":            user.Created,
		"createdBy":          user.CreatedBy,
		"lastLogin":          user.LastLogin,
		"sessions":           len(sessions),
	}
}

func (a *App) handleUsers(q *Request) (any, error) {
	list := []map[string]any{}
	for _, user := range a.users.list() {
		list = append(list, userView(user, a.sessions.forUser(user.Name)))
	}
	return map[string]any{"users": list, "me": q.user.Name}, nil
}

// handleCreateUser maakt een gebruiker met een tijdelijk wachtwoord. Bij de eerste keer inloggen
// stelt hij 2FA in en kiest hij een eigen wachtwoord.
func (a *App) handleCreateUser(q *Request) (any, error) {
	var body struct {
		Name  string `json:"name"`
		Admin bool   `json:"admin"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	name := strings.TrimSpace(body.Name)
	password := randomPassword(16)
	hash, err := hashPassword(password)
	if err != nil {
		return nil, err
	}
	if err := a.users.create(User{Name: name, PasswordHash: hash, Admin: body.Admin, MustChangePassword: true, CreatedBy: q.user.Name}); err != nil {
		if err == errUserExists {
			return nil, badRequest("Er is al een gebruiker met die naam.")
		}
		return nil, badRequest("%s", capitalize(err.Error())+".")
	}
	role := "developer"
	if body.Admin {
		role = "beheerder"
	}
	q.log("gebruiker aangemaakt", name, role)
	result, err := a.handleUsers(q)
	if err != nil {
		return nil, err
	}
	response := result.(map[string]any)
	response["password"] = password
	return response, nil
}

// userError: een gewone fout van de gebruikers (zoals "laatste beheerder") netjes teruggeven.
func userError(err error) error {
	if errors.Is(err, errLastAdmin) || errors.Is(err, errUserNotFound) {
		return badRequest("%s", capitalize(err.Error())+".")
	}
	return err
}

func (a *App) handleUserAction(q *Request) (any, error) {
	name := q.r.PathValue("name")
	action := q.r.PathValue("action")
	target, ok := a.users.get(name)
	if !ok {
		return nil, notFound("Die gebruiker bestaat niet.")
	}
	self := strings.EqualFold(target.Name, q.user.Name)
	lastAdmin := target.Admin && !target.Disabled && a.users.admins() <= 1
	var body struct {
		Value bool `json:"value"`
	}
	if err := q.body(&body); err != nil {
		return nil, err
	}
	response := map[string]any{}
	switch action {
	case "reset-2fa":
		password, err := a.users.resetTwoFactor(target.Name)
		if err != nil {
			return nil, userError(err)
		}
		a.sessions.removeUser(target.Name, "")
		response["password"] = password
		q.log("2fa gereset", target.Name, "met een tijdelijk wachtwoord")
	case "reset-password":
		password, err := a.users.resetPassword(target.Name)
		if err != nil {
			return nil, userError(err)
		}
		a.sessions.removeUser(target.Name, "")
		response["password"] = password
		q.log("wachtwoord gereset", target.Name, "")
	case "admin":
		if self || (lastAdmin && !body.Value) {
			return nil, badRequest("Je kunt de laatste beheerder (of jezelf) geen gewone developer maken.")
		}
		if _, err := a.users.update(target.Name, func(user *User) error {
			user.Admin = body.Value
			return nil
		}); err != nil {
			return nil, userError(err)
		}
		q.log("rol gewijzigd", target.Name, map[bool]string{true: "beheerder", false: "developer"}[body.Value])
	case "disable":
		if self || (lastAdmin && body.Value) {
			return nil, badRequest("Je kunt jezelf of de laatste beheerder niet uitzetten.")
		}
		if _, err := a.users.update(target.Name, func(user *User) error {
			user.Disabled = body.Value
			return nil
		}); err != nil {
			return nil, userError(err)
		}
		if body.Value {
			a.sessions.removeUser(target.Name, "")
		}
		q.log(map[bool]string{true: "gebruiker uitgezet", false: "gebruiker aangezet"}[body.Value], target.Name, "")
	case "delete":
		if self || lastAdmin {
			return nil, badRequest("Je kunt jezelf of de laatste beheerder niet verwijderen.")
		}
		if err := a.users.remove(target.Name); err != nil {
			return nil, userError(err)
		}
		a.sessions.removeUser(target.Name, "")
		q.log("gebruiker verwijderd", target.Name, "")
	case "logout":
		removed := a.sessions.removeUser(target.Name, q.session.Token)
		q.log("overal uitgelogd", target.Name, "")
		response["removed"] = removed
	default:
		return nil, notFound("Onbekende actie.")
	}
	result, err := a.handleUsers(q)
	if err != nil {
		return nil, err
	}
	for key, value := range result.(map[string]any) {
		response[key] = value
	}
	return response, nil
}

func (a *App) handleAudit(q *Request) (any, error) {
	return map[string]any{"entries": a.audit.list(300)}, nil
}
