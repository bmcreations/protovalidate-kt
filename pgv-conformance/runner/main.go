package main

import (
	"bytes"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"

	"github.com/getcode/proto-validation/pgv-conformance/runner/gen/harness"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/anypb"
)

func main() {
	executor := flag.String("executor", "", "path to executor command (e.g., 'java -jar executor.jar')")
	parallelism := flag.Int("j", 8, "number of parallel workers")
	verbose := flag.Bool("v", false, "verbose output")
	flag.Parse()

	if *executor == "" {
		fmt.Fprintln(os.Stderr, "Usage: pgv-conformance-runner -executor 'java -jar path/to/pgv-conformance.jar'")
		os.Exit(1)
	}

	testCases := TestCases

	var passed, failed, errored int64
	var mu sync.Mutex
	var failures []string

	sem := make(chan struct{}, *parallelism)
	var wg sync.WaitGroup

	for _, tc := range testCases {
		wg.Add(1)
		sem <- struct{}{}
		go func(tc TestCase) {
			defer wg.Done()
			defer func() { <-sem }()

			result, err := runCase(tc, *executor)
			if err != nil {
				atomic.AddInt64(&errored, 1)
				mu.Lock()
				failures = append(failures, fmt.Sprintf("ERROR: %s: %v", tc.Name, err))
				mu.Unlock()
				return
			}

			if result.Error {
				atomic.AddInt64(&errored, 1)
				mu.Lock()
				failures = append(failures, fmt.Sprintf("ERROR: %s: %v", tc.Name, result.Reasons))
				mu.Unlock()
				return
			}

			expectValid := tc.Failures == 0
			if result.Valid == expectValid {
				atomic.AddInt64(&passed, 1)
				if *verbose {
					fmt.Printf("  PASS: %s\n", tc.Name)
				}
			} else {
				atomic.AddInt64(&failed, 1)
				mu.Lock()
				if expectValid {
					failures = append(failures, fmt.Sprintf("FAIL: %s: expected valid, got invalid (%v)", tc.Name, result.Reasons))
				} else {
					failures = append(failures, fmt.Sprintf("FAIL: %s: expected invalid (failures=%d), got valid", tc.Name, tc.Failures))
				}
				mu.Unlock()
			}
		}(tc)
	}

	wg.Wait()

	fmt.Println()
	for _, f := range failures {
		fmt.Println(f)
	}
	fmt.Printf("\nResults: %d passed, %d failed, %d errors (total: %d)\n",
		passed, failed, errored, int64(len(testCases)))

	if failed > 0 || errored > 0 {
		os.Exit(1)
	}
}

func runCase(tc TestCase, executorCmd string) (*harness.TestResult, error) {
	anyMsg, err := anypb.New(tc.Message)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal Any: %w", err)
	}

	testCase := &harness.TestCase{Message: anyMsg}
	input, err := proto.Marshal(testCase)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal TestCase: %w", err)
	}

	args := splitCommand(executorCmd)
	cmd := exec.Command(args[0], args[1:]...)
	cmd.Stdin = bytes.NewReader(input)

	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("executor failed: %w, stderr: %s", err, stderr.String())
	}

	result := &harness.TestResult{}
	if err := proto.Unmarshal(stdout.Bytes(), result); err != nil {
		return nil, fmt.Errorf("failed to unmarshal TestResult: %w, stdout: %q", err, stdout.Bytes())
	}

	return result, nil
}

func splitCommand(cmd string) []string {
	var parts []string
	current := ""
	for _, c := range cmd {
		if c == ' ' {
			if current != "" {
				parts = append(parts, current)
				current = ""
			}
		} else {
			current += string(c)
		}
	}
	if current != "" {
		parts = append(parts, current)
	}
	return parts
}
