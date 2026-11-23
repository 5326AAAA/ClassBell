package main

import "syscall/js"

func main() {
	var stats scanStats
	record := js.FuncOf(func(_ js.Value, args []js.Value) any {
		if len(args) != 2 || args[0].Type() != js.TypeString || args[1].Type() != js.TypeNumber {
			return nil
		}
		elapsed := args[1].Float()
		if !(elapsed >= 0 && elapsed <= 24*60*60*1000) || !stats.record(args[0].String(), int(elapsed)) {
			return nil
		}
		return map[string]any{
			"recognized":  stats.recognized,
			"cancelled":   stats.cancelled,
			"unavailable": stats.unavailable,
			"lastMillis":  stats.lastMillis,
		}
	})
	js.Global().Set("classbellRecordScan", record)
	select {}
}
