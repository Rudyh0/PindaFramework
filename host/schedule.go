package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	_ "time/tzdata" // tijdzones, ook als de VPS ze niet heeft
)

// Schedule staat in panel/schedule.json: automatische backups en de dagelijkse herstart.
type Schedule struct {
	Timezone string          `json:"timezone"`
	Backup   BackupSchedule  `json:"backup"`
	Restart  RestartSchedule `json:"restart"`
	// Wat er de laatste keer gebeurde.
	LastBackup  *ScheduleResult `json:"lastBackup,omitempty"`
	LastRestart *ScheduleResult `json:"lastRestart,omitempty"`
}

type BackupSchedule struct {
	Enabled bool `json:"enabled"`
	// "daily": op vaste tijden; "interval": elke Every uur (00:00, 06:00, …).
	Mode      string   `json:"mode"`
	Times     []string `json:"times"`
	Every     int      `json:"every"`
	Days      []int    `json:"days"` // 1 = maandag … 7 = zondag; leeg = elke dag
	Keep      int      `json:"keep"`
	Website   bool     `json:"website"`
	Databases bool     `json:"databases"`
	Exclude   []string `json:"exclude"`
}

type RestartSchedule struct {
	Enabled     bool   `json:"enabled"`
	Time        string `json:"time"`
	Days        []int  `json:"days"`
	Warn        bool   `json:"warn"`
	BackupFirst bool   `json:"backupFirst"`
}

type ScheduleResult struct {
	At      int64  `json:"at"`
	OK      bool   `json:"ok"`
	Message string `json:"message,omitempty"`
}

func defaultSchedule() Schedule {
	return Schedule{
		Timezone: "Europe/Amsterdam",
		Backup: BackupSchedule{Enabled: true, Mode: "daily", Times: []string{"04:00"}, Every: 6, Keep: 7,
			Website: true, Databases: true, Exclude: append([]string(nil), defaultExclude...)},
		Restart: RestartSchedule{Enabled: false, Time: "05:00", Warn: true},
	}
}

type ScheduleStore struct {
	mu       sync.Mutex
	path     string
	schedule Schedule
	// Gaat omhoog bij elke wijziging: de planner rekent dan opnieuw.
	version int
}

func openSchedule(path string) (*ScheduleStore, error) {
	store := &ScheduleStore{path: path, schedule: defaultSchedule()}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &store.schedule); err != nil {
		return nil, fmt.Errorf("%s is geen geldige JSON: %w", path, err)
	}
	return store, nil
}

func (s *ScheduleStore) get() (Schedule, int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.schedule, s.version
}

func (s *ScheduleStore) update(change func(*Schedule)) (Schedule, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.schedule
	change(&next)
	if err := saveJSON(s.path, next); err != nil {
		return s.schedule, err
	}
	s.schedule = next
	s.version++
	return next, nil
}

// setResult bewaart wat er gebeurde, zonder dat de planner opnieuw gaat rekenen.
func (s *ScheduleStore) setResult(backup bool, result ScheduleResult) {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.schedule
	if backup {
		next.LastBackup = &result
	} else {
		next.LastRestart = &result
	}
	if saveJSON(s.path, next) == nil {
		s.schedule = next
	}
}

// ============================================================ controleren

var clockPattern = regexp.MustCompile(`^([01][0-9]|2[0-3]):[0-5][0-9]$`)

func validDays(days []int) ([]int, error) {
	var out []int
	for _, day := range days {
		if day < 1 || day > 7 {
			return nil, errors.New("ongeldige dag")
		}
		if !slices.Contains(out, day) {
			out = append(out, day)
		}
	}
	sort.Ints(out)
	return out, nil
}

// normalize controleert een planning uit de browser.
func (s Schedule) normalize() (Schedule, error) {
	if _, err := time.LoadLocation(s.Timezone); err != nil || s.Timezone == "" || s.Timezone == "Local" {
		return s, fmt.Errorf("onbekende tijdzone %q", s.Timezone)
	}
	b := &s.Backup
	if b.Mode != "daily" && b.Mode != "interval" {
		return s, errors.New("kies dagelijks of elke paar uur")
	}
	if b.Mode == "daily" {
		var times []string
		for _, t := range b.Times {
			if !clockPattern.MatchString(t) {
				return s, fmt.Errorf("ongeldige tijd %q", t)
			}
			if !slices.Contains(times, t) {
				times = append(times, t)
			}
		}
		if len(times) == 0 || len(times) > 6 {
			return s, errors.New("kies 1 tot 6 tijden voor de backups")
		}
		sort.Strings(times)
		b.Times = times
	}
	if !slices.Contains([]int{1, 2, 3, 4, 6, 8, 12}, b.Every) {
		b.Every = 6
	}
	var err error
	if b.Days, err = validDays(b.Days); err != nil {
		return s, err
	}
	if b.Keep < 1 || b.Keep > 100 {
		return s, errors.New("bewaar 1 tot 100 automatische backups")
	}
	var exclude []string
	for _, path := range b.Exclude {
		path = strings.TrimSpace(path)
		if path == "" {
			continue
		}
		clean, err := cleanPath(path)
		if err != nil || clean == "." {
			return s, fmt.Errorf("ongeldig pad om over te slaan: %q", path)
		}
		if !slices.Contains(exclude, clean) {
			exclude = append(exclude, clean)
		}
	}
	if len(exclude) > 50 {
		return s, errors.New("hooguit 50 paden overslaan")
	}
	b.Exclude = exclude
	r := &s.Restart
	if !clockPattern.MatchString(r.Time) {
		return s, fmt.Errorf("ongeldige tijd voor de herstart %q", r.Time)
	}
	if r.Days, err = validDays(r.Days); err != nil {
		return s, err
	}
	return s, nil
}

// ============================================================ wanneer

func clockTimes(times []string) [][2]int {
	var out [][2]int
	for _, t := range times {
		if !clockPattern.MatchString(t) {
			continue
		}
		hour, _ := strconv.Atoi(t[:2])
		minute, _ := strconv.Atoi(t[3:])
		out = append(out, [2]int{hour, minute})
	}
	sort.Slice(out, func(i, j int) bool { return out[i][0]*60+out[i][1] < out[j][0]*60+out[j][1] })
	return out
}

// isoWeekday: 1 = maandag … 7 = zondag.
func isoWeekday(day time.Weekday) int {
	if day == time.Sunday {
		return 7
	}
	return int(day)
}

// nextAt: het eerste moment na after op een van de tijden, op een toegestane dag.
func nextAt(times [][2]int, days []int, after time.Time, location *time.Location) time.Time {
	if len(times) == 0 {
		return time.Time{}
	}
	local := after.In(location)
	for offset := 0; offset <= 8; offset++ {
		day := time.Date(local.Year(), local.Month(), local.Day()+offset, 12, 0, 0, 0, location)
		if len(days) > 0 && !slices.Contains(days, isoWeekday(day.Weekday())) {
			continue
		}
		for _, t := range times {
			candidate := time.Date(day.Year(), day.Month(), day.Day(), t[0], t[1], 0, 0, location)
			if candidate.After(after) {
				return candidate
			}
		}
	}
	return time.Time{}
}

func (s *ScheduleStore) location() *time.Location {
	schedule, _ := s.get()
	return schedule.location()
}

func (s Schedule) location() *time.Location {
	location, err := time.LoadLocation(s.Timezone)
	if err != nil {
		return time.Local
	}
	return location
}

func (s Schedule) nextBackup(after time.Time) time.Time {
	if !s.Backup.Enabled {
		return time.Time{}
	}
	times := s.Backup.Times
	if s.Backup.Mode == "interval" {
		times = nil
		every := s.Backup.Every
		if every < 1 {
			every = 6
		}
		for hour := 0; hour < 24; hour += every {
			times = append(times, fmt.Sprintf("%02d:00", hour))
		}
	}
	return nextAt(clockTimes(times), s.Backup.Days, after, s.location())
}

func (s Schedule) nextRestart(after time.Time) time.Time {
	if !s.Restart.Enabled {
		return time.Time{}
	}
	return nextAt(clockTimes([]string{s.Restart.Time}), s.Restart.Days, after, s.location())
}

// restartWarnings: wanneer spelers een melding krijgen (voor de herstart).
var restartWarnings = []struct {
	before  time.Duration
	message string
}{
	{5 * time.Minute, "De server herstart over 5 minuten."},
	{time.Minute, "De server herstart over 1 minuut."},
	{10 * time.Second, "De server herstart over 10 seconden."},
}

// ============================================================ de planner

// runScheduler draait zolang het paneel draait. Gemiste momenten (het paneel stond uit) worden
// overgeslagen; het volgende moment telt. Na een wijziging van de planning wordt opnieuw gerekend,
// maar nooit van vóór wat al gestart is (anders zou een herstart in de waarschuwingstijd twee
// keer beginnen).
func (a *App) runScheduler() {
	plan := newPlanner()
	for {
		schedule, version := a.schedule.get()
		backup, restart := plan.tick(schedule, version, time.Now())
		if backup {
			go a.scheduledBackup(schedule)
		}
		if !restart.IsZero() {
			go a.scheduledRestart(schedule, restart)
		}
		time.Sleep(15 * time.Second)
	}
}

// planner onthoudt wat de volgende momenten zijn en wat al gestart is.
type planner struct {
	version                                            int
	nextBackup, nextRestart, firedBackup, firedRestart time.Time
	lastTick                                           time.Time
}

func newPlanner() *planner { return &planner{version: -1} }

// tick: moet er nu een backup, en een herstart (op welk moment)? Een herstart begint eerder als
// spelers gewaarschuwd worden.
func (p *planner) tick(schedule Schedule, version int, now time.Time) (bool, time.Time) {
	later := func(x, y time.Time) time.Time {
		if x.After(y) {
			return x
		}
		return y
	}
	if version != p.version {
		// Vanaf de vorige ronde rekenen: wat sindsdien aan de beurt was, gebeurt nu nog.
		since := p.lastTick
		if since.IsZero() {
			since = now
		}
		p.version = version
		p.nextBackup = schedule.nextBackup(later(since, p.firedBackup))
		p.nextRestart = schedule.nextRestart(later(since, p.firedRestart))
	}
	p.lastTick = now
	backup := false
	if !p.nextBackup.IsZero() && !now.Before(p.nextBackup) {
		backup, p.firedBackup = true, p.nextBackup
		p.nextBackup = schedule.nextBackup(later(now, p.firedBackup))
	}
	lead := time.Duration(0)
	if schedule.Restart.Warn {
		lead = restartWarnings[0].before
	}
	var restart time.Time
	if !p.nextRestart.IsZero() && !now.Before(p.nextRestart.Add(-lead)) {
		restart, p.firedRestart = p.nextRestart, p.nextRestart
		p.nextRestart = schedule.nextRestart(p.firedRestart)
	}
	return backup, restart
}

// restartStillPlanned: staat deze herstart nog zo in de planning (niet intussen uitgezet of
// naar een andere tijd verplaatst)?
func (a *App) restartStillPlanned(planned Schedule) bool {
	current, _ := a.schedule.get()
	return current.Restart.Enabled && current.Restart.Time == planned.Restart.Time &&
		slices.Equal(current.Restart.Days, planned.Restart.Days) && current.Timezone == planned.Timezone
}

func (a *App) scheduledBackup(schedule Schedule) {
	var log []string
	say := func(format string, args ...any) { log = append(log, fmt.Sprintf(format, args...)) }
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Hour)
	defer cancel()
	options := BackupOptions{Trigger: "automatisch", By: "planning", Website: schedule.Backup.Website,
		Databases: schedule.Backup.Databases, Exclude: schedule.Backup.Exclude}
	// Loopt er al een backup (bijvoorbeeld die voor een herstart) of iets met de server, dan even
	// wachten (hooguit een uur).
	var backup BackupInfo
	err := errBackupBusy
	for deadline := time.Now().Add(time.Hour); ; time.Sleep(30 * time.Second) {
		if !a.serverBusy() {
			if backup, err = a.createBackup(ctx, options, say); !errors.Is(err, errBackupBusy) {
				break
			}
		}
		if time.Now().After(deadline) {
			a.recordSchedule(true, false, "overgeslagen: er liep al een uur iets anders (een backup, installatie of terugzetten)")
			return
		}
	}
	if err != nil {
		a.recordSchedule(true, false, err.Error())
		return
	}
	a.pruneBackups(schedule.Backup.Keep, say)
	message := fmt.Sprintf("%s (%s)", backup.Name, humanBytes(backup.Size))
	for _, line := range log {
		if strings.HasPrefix(line, "Let op") || strings.HasPrefix(line, "Oude backup") {
			message += "\n" + line
		}
	}
	a.recordSchedule(true, true, message)
}

// scheduledRestart: spelers waarschuwen, eventueel eerst een backup, dan herstarten. Staat de
// server uit, dan gebeurt er niets (iemand heeft hem bewust gestopt).
func (a *App) scheduledRestart(schedule Schedule, at time.Time) {
	if a.process.Status().State != "active" {
		a.recordSchedule(false, false, "overgeslagen: de server stond uit")
		return
	}
	state := a.server.get()
	if schedule.Restart.Warn {
		for _, warning := range restartWarnings {
			wait := time.Until(at.Add(-warning.before))
			if wait < -5*time.Second {
				continue
			}
			time.Sleep(wait)
			if !a.restartStillPlanned(schedule) {
				return // uitgezet of verplaatst; de planner rekent zelf opnieuw
			}
			if a.process.Status().State != "active" {
				a.recordSchedule(false, false, "overgeslagen: de server werd intussen gestopt")
				return
			}
			_, _ = rconCommand(state.RconPort, state.RconPassword, "say "+warning.message, 5*time.Second)
		}
	}
	time.Sleep(time.Until(at))
	if !a.restartStillPlanned(schedule) {
		return
	}
	// Niet tegelijk met installeren of terugzetten (dat stopt en start de server zelf).
	if !a.lockServerJob() {
		a.recordSchedule(false, false, "overgeslagen: er liep iets met de server (installeren of terugzetten)")
		return
	}
	defer a.unlockServerJob()
	if a.process.Status().State != "active" {
		a.recordSchedule(false, false, "overgeslagen: de server werd intussen gestopt")
		return
	}
	message := "herstart"
	if schedule.Restart.BackupFirst {
		_, _ = rconCommand(state.RconPort, state.RconPassword, "say Even een backup, daarna herstart de server.", 5*time.Second)
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Hour)
		backup, err := a.createBackup(ctx, BackupOptions{Trigger: "voor-herstart", By: "planning", Website: schedule.Backup.Website,
			Databases: schedule.Backup.Databases, Exclude: schedule.Backup.Exclude}, func(string, ...any) {})
		cancel()
		if err != nil {
			message = "herstart (de backup ervoor lukte niet: " + err.Error() + ")"
		} else {
			message = "herstart na backup " + backup.Name
			a.pruneBackups(schedule.Backup.Keep, func(string, ...any) {})
		}
	}
	command, err := a.serverCommand()
	if err == nil {
		err = a.process.Restart(command)
	}
	if err != nil {
		a.recordSchedule(false, false, err.Error())
		return
	}
	a.recordSchedule(false, true, message)
}

func (a *App) recordSchedule(backup, ok bool, message string) {
	a.schedule.setResult(backup, ScheduleResult{At: time.Now().UnixMilli(), OK: ok, Message: message})
	action := map[bool]string{true: "geplande backup", false: "geplande herstart"}[backup]
	if !ok {
		action += " niet gelukt"
	}
	a.audit.add("planning", "", action, "", clip(message, 300))
}
