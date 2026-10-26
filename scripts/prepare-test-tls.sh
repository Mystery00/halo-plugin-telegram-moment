#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
tls="$project_root/workplace/store-test/tls"
mkdir -p "$tls"
if [[ -e "$tls/server.key" || -e "$tls/truststore.p12" ]]; then
  echo 'Test TLS files already exist; reuse the dedicated fixture or choose a fresh workspace.' >&2
  exit 1
fi
# 自签证书仅用于测试容器，不安装到主机或真实 Halo 的信任库。
openssl req -x509 -newkey rsa:2048 -nodes -days 7 -keyout "$tls/server.key" -out "$tls/server.crt" \
  -subj '/CN=telegram-moment-mock' -addext 'subjectAltName=DNS:telegram-moment-mock,DNS:localhost,IP:127.0.0.1' 2>/dev/null
docker run --rm -v "$tls:/tls" gradle:8.14-jdk21 keytool -importcert -noprompt -alias fixture \
  -file /tls/server.crt -keystore /tls/truststore.p12 -storetype PKCS12 -storepass changeit
echo 'Isolated test TLS fixture created.'
