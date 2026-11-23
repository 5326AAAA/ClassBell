package main

type scanStats struct {
	recognized  int
	cancelled   int
	unavailable int
	lastMillis  int
}

func (s *scanStats) record(status string, elapsed int) bool {
	if elapsed < 0 || elapsed > 24*60*60*1000 {
		return false
	}
	switch status {
	case "recognized":
		s.recognized++
	case "cancelled":
		s.cancelled++
	case "unavailable":
		s.unavailable++
	default:
		return false
	}
	s.lastMillis = elapsed
	return true
}
