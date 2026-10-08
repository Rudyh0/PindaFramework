// PindaHost: het dev-paneel van een PindaCraft-server.
//
// Eén programma dat als dienst op de VPS draait (achter Caddy voor HTTPS). Hiermee beheren
// developers de server: gebruikers met verplichte 2FA, de eerste setup, databases (MariaDB)
// en later ook de server zelf, bestanden, backups en de website.
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
)

// Version wordt bij het bouwen ingevuld (zie de GitHub-workflow).
var Version = "dev"

const defaultConfig = "/opt/pinda/panel/config.json"

func main() {
	log.SetFlags(log.LstdFlags)
	log.SetPrefix("[pinda-host] ")

	args := os.Args[1:]
	command := "serve"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		command, args = args[0], args[1:]
	}

	flags := flag.NewFlagSet(command, flag.ExitOnError)
	configPath := flags.String("config", envOr("PINDA_HOST_CONFIG", defaultConfig), "pad naar config.json")
	_ = flags.Parse(args)
	rest := flags.Args()

	if command == "version" {
		fmt.Println("pinda-host", Version)
		return
	}

	app, err := openApp(*configPath)
	if err != nil {
		log.Fatalf("starten mislukt: %v", err)
	}

	switch command {
	case "serve":
		if err := app.serve(); err != nil {
			log.Fatal(err)
		}
	case "setup-code":
		code, err := app.setupCode()
		if err != nil {
			log.Fatal(err)
		}
		if code == "" {
			fmt.Println("De setup is al gedaan; er is geen setupcode meer.")
			return
		}
		fmt.Println(code)
	case "fingerprint":
		// Voor gebruik zonder domein: het eigen certificaat (maken als het er nog niet is).
		if _, _, err := app.ensureCertificate(); err != nil {
			log.Fatal(err)
		}
		fmt.Println(app.certificateFingerprint())
	case "reset-2fa":
		requireArg(rest, "reset-2fa <gebruiker>")
		if err := app.users.resetTwoFactor(rest[0]); err != nil {
			log.Fatal(err)
		}
		app.audit.add("cli", "", "2fa-reset", rest[0], "via de opdrachtregel")
		fmt.Printf("De 2FA van %s is gereset. Bij de volgende keer inloggen stelt hij/zij een nieuwe in.\n", rest[0])
	case "reset-password":
		requireArg(rest, "reset-password <gebruiker>")
		password, err := app.users.resetPassword(rest[0])
		if err != nil {
			log.Fatal(err)
		}
		app.audit.add("cli", "", "wachtwoord-reset", rest[0], "via de opdrachtregel")
		fmt.Printf("Tijdelijk wachtwoord voor %s: %s\n(bij het inloggen moet een nieuw wachtwoord worden gekozen)\n", rest[0], password)
	default:
		fmt.Fprintf(os.Stderr, "Onbekende opdracht %q. Gebruik: serve, setup-code, fingerprint, reset-2fa, reset-password, version\n", command)
		os.Exit(2)
	}
}

func requireArg(args []string, usage string) {
	if len(args) < 1 || strings.TrimSpace(args[0]) == "" {
		fmt.Fprintln(os.Stderr, "Gebruik: pinda-host "+usage)
		os.Exit(2)
	}
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// App houdt alles bij elkaar: instellingen, gebruikers, sessies, logboek en de diensten.
type App struct {
	configPath string
	dataDir    string
	config     *ConfigStore
	users      *UserStore
	sessions   *Sessions
	audit      *AuditLog
	limiter    *RateLimiter
	mariadb    *MariaDB
	jobs       *Jobs
}

func openApp(configPath string) (*App, error) {
	dataDir := filepath.Dir(configPath)
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, err
	}
	config, err := openConfig(configPath)
	if err != nil {
		return nil, err
	}
	users, err := openUsers(filepath.Join(dataDir, "users.json"))
	if err != nil {
		return nil, err
	}
	app := &App{
		configPath: configPath,
		dataDir:    dataDir,
		config:     config,
		users:      users,
		sessions:   newSessions(),
		audit:      openAudit(filepath.Join(dataDir, "audit.log")),
		limiter:    newRateLimiter(),
		mariadb:    newMariaDB(),
		jobs:       newJobs(),
	}
	return app, nil
}
