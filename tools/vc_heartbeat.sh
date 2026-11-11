#!/bin/sh
# Pleos Virtual Console: keep a "browser session" alive so vehicle controls are accepted.
while true; do
  curl -s -m 3 -X POST "http://127.0.0.1:8088/browser/session?client=lanealert-demo" >/dev/null 2>&1
  sleep 1
done
