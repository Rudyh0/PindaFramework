package main

import (
	"fmt"
	"log"
	"strings"
	"sync"
	"time"
)

// Job is werk dat even duurt (een database inladen, ...). De browser vraagt de voortgang op.
type Job struct {
	ID      string   `json:"id"`
	Title   string   `json:"title"`
	User    string   `json:"user"`
	Status  string   `json:"status"` // running, done, failed
	Lines   []string `json:"lines"`
	Error   string   `json:"error,omitempty"`
	Started int64    `json:"started"`
	Ended   int64    `json:"ended,omitempty"`
}

type Jobs struct {
	mu   sync.Mutex
	jobs map[string]*Job
	// In volgorde van starten, om oude taken op te ruimen.
	order []string
}

const maxJobLines = 2000

func newJobs() *Jobs {
	return &Jobs{jobs: map[string]*Job{}}
}

// start draait het werk op de achtergrond. In het werk kun je met say() regels toevoegen.
func (j *Jobs) start(title, user string, work func(say func(format string, args ...any)) error) *Job {
	job := &Job{ID: randomToken()[:16], Title: title, User: user, Status: "running", Started: time.Now().UnixMilli()}
	j.mu.Lock()
	j.jobs[job.ID] = job
	j.order = append(j.order, job.ID)
	// Alleen de laatste 50 bewaren.
	for len(j.order) > 50 {
		delete(j.jobs, j.order[0])
		j.order = j.order[1:]
	}
	j.mu.Unlock()

	say := func(format string, args ...any) {
		line := fmt.Sprintf(format, args...)
		j.mu.Lock()
		defer j.mu.Unlock()
		for _, part := range strings.Split(strings.TrimRight(line, "\n"), "\n") {
			job.Lines = append(job.Lines, part)
		}
		if len(job.Lines) > maxJobLines {
			job.Lines = append([]string{"…"}, job.Lines[len(job.Lines)-maxJobLines:]...)
		}
	}
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("taak %q liep vast: %v", title, r)
				j.finish(job, fmt.Errorf("onverwachte fout: %v", r))
			}
		}()
		j.finish(job, work(say))
	}()
	return j.get(job.ID)
}

func (j *Jobs) finish(job *Job, err error) {
	j.mu.Lock()
	defer j.mu.Unlock()
	job.Ended = time.Now().UnixMilli()
	if err != nil {
		job.Status = "failed"
		job.Error = err.Error()
		return
	}
	job.Status = "done"
}

// get geeft een kopie (de regels erbij), zodat de JSON nooit half bijgewerkt is.
func (j *Jobs) get(id string) *Job {
	j.mu.Lock()
	defer j.mu.Unlock()
	job, ok := j.jobs[id]
	if !ok {
		return nil
	}
	result := *job
	result.Lines = append([]string(nil), job.Lines...)
	return &result
}
