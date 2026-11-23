package main

import "testing"

func TestCountsResultsAndKeepsLatestDuration(t *testing.T) {
	var s scanStats
	if !s.record("recognized", 320) || !s.record("cancelled", 1000) || !s.record("recognized", 525) || !s.record("unavailable", 0) {
		t.Fatal("valid result rejected")
	}
	if s != (scanStats{2, 1, 1, 0}) {
		t.Fatalf("unexpected counters: %+v", s)
	}
}

func TestRejectsUnknownStatusAndInvalidDurationWithoutMutation(t *testing.T) {
	s := scanStats{recognized: 1, lastMillis: 123}
	before := s
	if s.record("not a status", 1) || s.record("recognized", -1) || s.record("recognized", 86400001) || s != before {
		t.Fatalf("invalid input changed stats: %+v", s)
	}
}
