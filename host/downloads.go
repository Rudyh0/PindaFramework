package main

import (
	"context"
	"crypto/md5"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Waar het paneel software vandaan haalt. Variabelen, zodat de tests een nepserver kunnen gebruiken.
var (
	purpurAPI   = "https://api.purpurmc.org/v2/purpur"
	modrinthAPI = "https://api.modrinth.com/v2"
	githubAPI   = "https://api.github.com"
)

var apiClient = &http.Client{Timeout: 30 * time.Second}

var (
	minecraftVersionPattern = regexp.MustCompile(`^[0-9]{1,3}(\.[0-9]{1,3}){1,3}$`)
	buildPattern            = regexp.MustCompile(`^[0-9]{1,8}$`)
	sha256Pattern           = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

// userAgent: Modrinth vraagt om een herkenbare User-Agent.
func userAgent() string {
	return "Rudyh0/PindaFramework pinda-host/" + Version + " (https://github.com/Rudyh0/PindaFramework)"
}

func getJSON(ctx context.Context, address string, target any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", userAgent())
	request.Header.Set("Accept", "application/json")
	response, err := apiClient.Do(request)
	if err != nil {
		return fmt.Errorf("%s is niet bereikbaar: %w", hostOf(address), err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound {
		return errNotFoundRemote
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("%s gaf een fout (%s)", hostOf(address), response.Status)
	}
	return json.NewDecoder(io.LimitReader(response.Body, 16<<20)).Decode(target)
}

var errNotFoundRemote = errors.New("niet gevonden")

func hostOf(address string) string {
	if parsed, err := url.Parse(address); err == nil {
		return parsed.Host
	}
	return address
}

// Hashes: controlegetallen van een download.
type Hashes struct {
	MD5, SHA1, SHA256, SHA512 string
}

// Expected: wat een download moet zijn. Lege velden worden niet gecontroleerd; minstens één
// controlegetal is verplicht.
type Expected struct {
	MD5, SHA1, SHA256, SHA512 string
}

func (e Expected) check(h Hashes) error {
	pairs := [][2]string{{e.SHA512, h.SHA512}, {e.SHA256, h.SHA256}, {e.SHA1, h.SHA1}, {e.MD5, h.MD5}}
	checked := false
	for _, pair := range pairs {
		if pair[0] == "" {
			continue
		}
		checked = true
		if !strings.EqualFold(pair[0], pair[1]) {
			return errors.New("de download klopt niet met het controlegetal; probeer het opnieuw")
		}
	}
	if !checked {
		return errors.New("geen controlegetal bekend voor deze download")
	}
	return nil
}

// allowedURL: alleen https (of http naar 127.0.0.1, voor de nepserver in tests).
func allowedURL(address *url.URL) bool {
	return address.Scheme == "https" && address.Host != "" || (address.Scheme == "http" && address.Hostname() == "127.0.0.1" && address.User == nil)
}

var downloadClient = &http.Client{
	Timeout: 15 * time.Minute,
	CheckRedirect: func(request *http.Request, via []*http.Request) error {
		if len(via) >= 5 || !allowedURL(request.URL) {
			return errors.New("doorverwezen naar een onveilig adres")
		}
		return nil
	},
}

// download haalt address op naar out (hooguit max bytes) en controleert het controlegetal.
func download(ctx context.Context, address string, out io.Writer, max int64, expected Expected) (int64, error) {
	if parsed, err := url.Parse(address); err != nil || !allowedURL(parsed) {
		return 0, fmt.Errorf("ongeldig downloadadres: %s", address)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return 0, err
	}
	request.Header.Set("User-Agent", userAgent())
	response, err := downloadClient.Do(request)
	if err != nil {
		return 0, fmt.Errorf("downloaden van %s mislukt: %w", hostOf(address), err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("downloaden van %s mislukt (%s)", hostOf(address), response.Status)
	}
	sums := []hash.Hash{md5.New(), sha1.New(), sha256.New(), sha512.New()}
	writers := []io.Writer{out}
	for _, sum := range sums {
		writers = append(writers, sum)
	}
	written, err := io.Copy(io.MultiWriter(writers...), io.LimitReader(response.Body, max+1))
	if err != nil {
		return written, fmt.Errorf("downloaden onderbroken: %w", err)
	}
	if written > max {
		return written, fmt.Errorf("de download is groter dan %s", humanBytes(max))
	}
	got := Hashes{hex.EncodeToString(sums[0].Sum(nil)), hex.EncodeToString(sums[1].Sum(nil)),
		hex.EncodeToString(sums[2].Sum(nil)), hex.EncodeToString(sums[3].Sum(nil))}
	return written, expected.check(got)
}

// ============================================================ Minecraft-versies

// compareVersions vergelijkt "1.21.11", "26.2", "26.1.2" (-1, 0 of 1).
func compareVersions(a, b string) int {
	as, bs := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(as) || i < len(bs); i++ {
		var x, y int
		if i < len(as) {
			x, _ = strconv.Atoi(as[i])
		}
		if i < len(bs) {
			y, _ = strconv.Atoi(bs[i])
		}
		if x != y {
			if x < y {
				return -1
			}
			return 1
		}
	}
	return 0
}

// requiredJava: welke Java een Minecraft-versie minstens nodig heeft.
func requiredJava(version string) int {
	switch {
	case compareVersions(version, "26.1") >= 0:
		return 25
	case compareVersions(version, "1.20.5") >= 0:
		return 21
	case compareVersions(version, "1.18") >= 0:
		return 17
	case compareVersions(version, "1.17") >= 0:
		return 16
	}
	return 8
}

// PurpurVersion: een Minecraft-versie zoals het paneel hem toont.
type PurpurVersion struct {
	Version      string `json:"version"`
	Recommended  bool   `json:"recommended"`  // de huidige stabiele versie
	Experimental bool   `json:"experimental"` // nieuwer dan de stabiele: nog niet aan te raden
	Java         int    `json:"java"`
	Framework    bool   `json:"framework"` // PindaFramework werkt hierop
}

type PurpurVersions struct {
	Current  string          `json:"current"`
	Versions []PurpurVersion `json:"versions"` // nieuwste eerst
}

var purpurCache struct {
	sync.Mutex
	at       time.Time
	versions PurpurVersions
}

// minFrameworkVersion: PindaFramework heeft api-version 26.1.
const minFrameworkVersion = "26.1"

func purpurVersions(ctx context.Context) (PurpurVersions, error) {
	purpurCache.Lock()
	defer purpurCache.Unlock()
	if time.Since(purpurCache.at) < 10*time.Minute && purpurCache.versions.Current != "" {
		return purpurCache.versions, nil
	}
	var raw struct {
		Versions []string `json:"versions"`
		Metadata struct {
			Current string `json:"current"`
		} `json:"metadata"`
	}
	if err := getJSON(ctx, purpurAPI, &raw); err != nil {
		return PurpurVersions{}, err
	}
	if len(raw.Versions) == 0 {
		return PurpurVersions{}, errors.New("Purpur gaf geen versies terug")
	}
	current := raw.Metadata.Current
	if current == "" {
		current = raw.Versions[len(raw.Versions)-1]
	}
	list := make([]PurpurVersion, 0, len(raw.Versions))
	for _, version := range raw.Versions {
		if !minecraftVersionPattern.MatchString(version) {
			continue
		}
		list = append(list, PurpurVersion{
			Version: version, Recommended: version == current, Experimental: compareVersions(version, current) > 0,
			Java: requiredJava(version), Framework: compareVersions(version, minFrameworkVersion) >= 0,
		})
	}
	sort.SliceStable(list, func(i, j int) bool { return compareVersions(list[i].Version, list[j].Version) > 0 })
	purpurCache.versions = PurpurVersions{Current: current, Versions: list}
	purpurCache.at = time.Now()
	return purpurCache.versions, nil
}

// PurpurBuild: één build van Purpur.
type PurpurBuild struct {
	Version   string `json:"version"`
	Build     string `json:"build"`
	Result    string `json:"result"`
	MD5       string `json:"md5"`
	Timestamp int64  `json:"timestamp"`
}

// purpurBuilds: de nieuwste builds van een versie (nieuwste eerst).
func purpurBuilds(ctx context.Context, version string) ([]string, error) {
	if !minecraftVersionPattern.MatchString(version) {
		return nil, errors.New("ongeldige versie")
	}
	var raw struct {
		Builds struct {
			Latest string   `json:"latest"`
			All    []string `json:"all"`
		} `json:"builds"`
	}
	if err := getJSON(ctx, purpurAPI+"/"+version, &raw); err != nil {
		return nil, err
	}
	builds := []string{}
	for i := len(raw.Builds.All) - 1; i >= 0 && len(builds) < 25; i-- {
		if buildPattern.MatchString(raw.Builds.All[i]) {
			builds = append(builds, raw.Builds.All[i])
		}
	}
	if len(builds) == 0 && buildPattern.MatchString(raw.Builds.Latest) {
		builds = append(builds, raw.Builds.Latest)
	}
	return builds, nil
}

func purpurBuild(ctx context.Context, version, build string) (PurpurBuild, error) {
	if !minecraftVersionPattern.MatchString(version) || (build != "latest" && !buildPattern.MatchString(build)) {
		return PurpurBuild{}, errors.New("ongeldige versie of build")
	}
	var info PurpurBuild
	if err := getJSON(ctx, purpurAPI+"/"+version+"/"+build, &info); err != nil {
		return info, err
	}
	if !buildPattern.MatchString(info.Build) {
		return info, errors.New("Purpur gaf een ongeldige build terug")
	}
	return info, nil
}

func purpurDownloadURL(version, build string) string {
	return purpurAPI + "/" + version + "/" + build + "/download"
}

// ============================================================ plugins

// RemotePlugin: een plugin die gedownload kan worden.
type RemotePlugin struct {
	Version  string
	Filename string
	URL      string
	Expected Expected
	Beta     bool
}

// modrinthLatest zoekt de nieuwste versie van een Modrinth-project voor deze Minecraft-versie:
// eerst een gewone release, anders een bèta.
func modrinthLatest(ctx context.Context, project, minecraft string) (RemotePlugin, error) {
	query := url.Values{}
	query.Set("loaders", `["paper"]`)
	query.Set("game_versions", `["`+minecraft+`"]`)
	var versions []struct {
		VersionNumber string `json:"version_number"`
		VersionType   string `json:"version_type"`
		Files         []struct {
			URL      string            `json:"url"`
			Filename string            `json:"filename"`
			Primary  bool              `json:"primary"`
			Hashes   map[string]string `json:"hashes"`
		} `json:"files"`
	}
	if err := getJSON(ctx, modrinthAPI+"/project/"+url.PathEscape(project)+"/version?"+query.Encode(), &versions); err != nil {
		return RemotePlugin{}, err
	}
	for _, wanted := range []string{"release", "beta"} {
		for _, version := range versions {
			if version.VersionType != wanted || len(version.Files) == 0 {
				continue
			}
			file := version.Files[0]
			for _, candidate := range version.Files {
				if candidate.Primary {
					file = candidate
				}
			}
			return RemotePlugin{Version: version.VersionNumber, Filename: file.Filename, URL: file.URL, Beta: wanted == "beta",
				Expected: Expected{SHA512: file.Hashes["sha512"], SHA1: file.Hashes["sha1"]}}, nil
		}
	}
	return RemotePlugin{}, fmt.Errorf("geen versie voor Minecraft %s gevonden", minecraft)
}

// githubLatest: het nieuwste .jar-bestand uit de laatste release van een GitHub-project.
func githubLatest(ctx context.Context, repo, prefix string) (RemotePlugin, error) {
	var release struct {
		TagName string `json:"tag_name"`
		Assets  []struct {
			Name   string `json:"name"`
			URL    string `json:"browser_download_url"`
			Digest string `json:"digest"`
		} `json:"assets"`
	}
	if err := getJSON(ctx, githubAPI+"/repos/"+repo+"/releases/latest", &release); err != nil {
		return RemotePlugin{}, err
	}
	for _, asset := range release.Assets {
		if !strings.HasPrefix(asset.Name, prefix) || !strings.HasSuffix(asset.Name, ".jar") {
			continue
		}
		plugin := RemotePlugin{Filename: asset.Name, URL: asset.URL,
			Version: strings.TrimSuffix(strings.TrimPrefix(asset.Name, prefix), ".jar")}
		if algorithm, value, ok := strings.Cut(asset.Digest, ":"); ok && algorithm == "sha256" && sha256Pattern.MatchString(value) {
			plugin.Expected.SHA256 = value
		}
		// Oudere releases zonder digest: de .sha256 die de workflow ernaast zet.
		for _, other := range release.Assets {
			if plugin.Expected.SHA256 == "" && other.Name == asset.Name+".sha256" {
				var sum strings.Builder
				if _, err := download(ctx, other.URL, &sum, 1024, Expected{}); err != nil && sum.Len() == 0 {
					break
				}
				if fields := strings.Fields(sum.String()); len(fields) > 0 && sha256Pattern.MatchString(fields[0]) {
					plugin.Expected.SHA256 = fields[0]
				}
			}
		}
		return plugin, nil
	}
	return RemotePlugin{}, fmt.Errorf("geen %s*.jar in de laatste release (%s)", prefix, release.TagName)
}
