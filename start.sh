#!/bin/bash
# Starts the NexFlow Spring Boot JAR from a .env file in the same directory.
# Usage:  chmod +x start.sh && ./start.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
JAR_FILE="$SCRIPT_DIR/app.jar"
LOG_FILE="$SCRIPT_DIR/nexflow.log"
PID_FILE="$SCRIPT_DIR/nexflow.pid"

# ── Stop existing instance if running ────────────────────────────────────────
if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE")
  if kill -0 "$OLD_PID" 2>/dev/null; then
    echo "Stopping existing process (PID $OLD_PID)…"
    kill "$OLD_PID"
    sleep 2
  fi
  rm -f "$PID_FILE"
fi

# ── Load .env ─────────────────────────────────────────────────────────────────
if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: .env not found at $ENV_FILE"
  echo "Run: cp .env.example .env  then fill in the values."
  exit 1
fi

set +H   # disable bash history expansion (handles passwords with '!' correctly)
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
set -H

# RABBITMQ_PORT must not be in the environment — Spring Boot's relaxed binding
# maps it to rabbitmq.port and overrides the STOMP relay port (61613) with 5672.
# RABBITMQ_AMQP_PORT is the correct variable for the AMQP port.
unset RABBITMQ_PORT

# ── Check JAR exists ─────────────────────────────────────────────────────────
if [ ! -f "$JAR_FILE" ]; then
  echo "ERROR: app.jar not found at $JAR_FILE"
  echo "Build locally with:  ./mvnw clean package -DskipTests"
  echo "Then upload:         scp target/nexflow-backend-*.jar user@host:~/nexflow/app.jar"
  exit 1
fi

# ── Launch ────────────────────────────────────────────────────────────────────
echo "Starting NexFlow backend… (logs → $LOG_FILE)"
nohup java \
  -Xms512m -Xmx1280m \
  -jar "$JAR_FILE" \
  --spring.profiles.active=prod \
  --management.health.mail.enabled=false \
  > "$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
echo "Started. PID=$(cat $PID_FILE)"
echo "Tail logs:  tail -f $LOG_FILE"
echo "Stop:       kill \$(cat $PID_FILE)"
