# Contributing

## Before opening a change

Read ARCHITECTURE.md, THREADING.md, and TESTING.md. Changes to concurrency, scheduling, mixins, or lifecycle behavior must include a regression test and a description of the ownership invariants they preserve.

## Development checks

Use Java 25 and run:

    ./gradlew --no-daemon clean :common:test :api:test :fabric:test
    ./gradlew --no-daemon build

Do not include build output, local server worlds, credentials, or personal configuration in commits. Do not change release approval evidence to bypass a gate.

## Pull requests

Provide the affected execution paths, exact test commands and results, compatibility implications, and known limitations. Distinguish tests that ran from checks that were only inspected. Do not claim production safety without a dedicated-server soak and independent review.
