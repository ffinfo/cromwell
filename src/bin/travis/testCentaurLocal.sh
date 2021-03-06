#!/usr/bin/env bash

printTravisHeartbeat() {
    # Sleep one minute between printouts, but don't zombie for more than two hours
    for ((i=0; i < 120; i++)); do
        sleep 60
        printf "…"
    done &
    TRAVIS_HEARTBEAT_PID=$!
}

killTravisHeartbeat() {
    if [ -n "${TRAVIS_HEARTBEAT_PID+set}" ]; then
        kill ${TRAVIS_HEARTBEAT_PID} || true
    fi
}

exitScript() {
    echo "CROMWELL LOG"
    cat logs/cromwell.log
    echo "CENTAUR LOG"
    cat logs/centaur.log
    killTravisHeartbeat
}

trap exitScript EXIT
printTravisHeartbeat

set -x
set -e
git clone https://github.com/broadinstitute/centaur.git
cd centaur
./test_cromwell.sh -b${TRAVIS_BRANCH} -p5
