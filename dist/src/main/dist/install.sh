#!/usr/bin/env bash
# Installs the HushSign agent on a Debian/Ubuntu gateway (P7-S7).
#
# It installs and explains; it does not start anything. An agent that begins sending the moment
# it is unpacked would be sending under whatever identity the example file happens to contain,
# so the last thing this script does is print what still has to be edited.
set -euo pipefail

PREFIX="${PREFIX:-/opt/hs-agent}"
CONFIG_DIR="${CONFIG_DIR:-/etc/hs-agent}"
STATE_DIR="${STATE_DIR:-/var/lib/hs-agent}"
LOG_DIR="${LOG_DIR:-/var/log/hs-agent}"
SERVICE_USER="${SERVICE_USER:-hushsign}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

say() { printf '%s\n' "$*"; }
die() { printf 'install: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo ./install.sh)"
command -v java >/dev/null 2>&1 || die "no java on PATH; install a Java 21 runtime first"
[ -d "$HERE/lib" ] || die "lib/ is missing next to this script; unpack the whole archive"

say "installing the agent to $PREFIX"
install -d -m 0755 "$PREFIX/bin" "$PREFIX/lib"
install -m 0755 "$HERE/bin/hs-agent" "$PREFIX/bin/hs-agent"
install -m 0644 "$HERE"/lib/*.jar "$PREFIX/lib/"
install -m 0644 "$HERE/README.txt" "$PREFIX/README.txt"

say "creating the service account $SERVICE_USER"
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --no-create-home --home-dir "$STATE_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
if getent group dialout >/dev/null 2>&1; then
  usermod -a -G dialout "$SERVICE_USER"
else
  say "warning: no dialout group; the agent may not be able to open the modem"
fi

say "creating state and log directories"
install -d -m 0700 -o "$SERVICE_USER" -g "$SERVICE_USER" "$STATE_DIR"
install -d -m 0700 -o "$SERVICE_USER" -g "$SERVICE_USER" "$LOG_DIR"
install -d -m 0750 -o root -g "$SERVICE_USER" "$CONFIG_DIR"

# Never overwrite a configuration: the second install of a gateway must not reset its identity
# or its policy.
for example in agent.example.yaml policy.example.yaml; do
  target="$CONFIG_DIR/${example%.example.yaml}.yaml"
  if [ -e "$target" ]; then
    say "keeping existing $target"
  else
    install -m 0640 -o root -g "$SERVICE_USER" "$HERE/config/$example" "$target"
    say "wrote $target from $example"
  fi
done

say "installing the systemd unit"
install -m 0644 "$HERE/bin/hs-agent.service" /etc/systemd/system/hs-agent.service
systemctl daemon-reload

cat <<NEXT

Installed. Nothing has been started yet, deliberately.

  1. Put the gateway uuid the fleet registry assigned into $CONFIG_DIR/agent.yaml
     (gatewayId). A name that is not a uuid is refused at startup.
  2. Review $CONFIG_DIR/policy.yaml - techniques, operators, caps, quiet hours.
  3. Check the modem is visible:  ls -l /dev/ttyUSB* /dev/serial/by-id/
     Pin the by-id path in agent.yaml (modems.ports): a dongle that resets comes back with a
     different ttyUSB number, and a pinned /dev/ttyUSB0 then points at nothing.
  4. Huawei K3765-family modems need modems.pduSubmission: TPDU_HEX. The default FULL_HEX is
     rejected with "+CMS ERROR: 304", which reaches the platform as
     "modem ... rejected the PDU" - the first real send on the test VM failed exactly this way.
  5. Start it:                    systemctl enable --now hs-agent
  6. Watch it:                    journalctl -u hs-agent -f
  7. Talk to it:                  $PREFIX/bin/hs-agent ctl status

The admin token is generated on first start at $STATE_DIR/admin.token (0600).
NEXT
