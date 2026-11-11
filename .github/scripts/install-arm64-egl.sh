#!/usr/bin/env bash
set -euo pipefail

if [ "$(uname -m)" != "aarch64" ]; then
  echo "::error::ARM64 EGL bootstrap requires an aarch64 runner."
  exit 1
fi

if ldconfig -p | grep -Fq 'libEGL.so.1'; then
  exit 0
fi

using_fallback=0
use_fallback_mirror() {
  if [ "$using_fallback" -eq 1 ]; then
    return
  fi

  echo "::warning::Switching Ubuntu ports package reads to security.ports.ubuntu.com after primary-mirror failure."
  for source in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [ -f "$source" ] || continue
    sudo sed -i \
      -e 's#http://ports\.ubuntu\.com/ubuntu-ports#https://security.ports.ubuntu.com/ubuntu-ports#g' \
      -e 's#https://ports\.ubuntu\.com/ubuntu-ports#https://security.ports.ubuntu.com/ubuntu-ports#g' \
      "$source"
  done
  using_fallback=1
}

for attempt in 1 2 3; do
  if [ "$using_fallback" -eq 0 ] && ! getent ahosts ports.ubuntu.com >/dev/null 2>&1; then
    use_fallback_mirror
  fi

  resolver_options="attempts:1 timeout:2"
  if sudo env RES_OPTIONS="$resolver_options" apt-get -o Acquire::Retries=0 update -qq && \
     sudo env RES_OPTIONS="$resolver_options" DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=0 install -y --no-install-recommends libegl1; then
    ldconfig -p | grep -F 'libEGL.so.1'
    exit 0
  fi

  use_fallback_mirror
  if [ "$attempt" -lt 3 ]; then
    sleep "$((attempt * 10))"
  fi
done

echo "::error::Unable to install libegl1 after bounded Ubuntu mirror retries."
exit 1
