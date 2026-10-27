HushSign edge agent
===================

Layout
------

  bin/hs-agent            launcher; also the client for `hs-agent ctl <command>`
  bin/hs-agent.service    systemd unit used by install.sh
  lib/*.jar               the agent and its dependencies
  config/agent.example.yaml, config/policy.example.yaml
  install.sh              installs to /opt/hs-agent and sets up the service account

Install
-------

  tar xzf hs-agent-<version>.tar.gz
  cd hs-agent-<version>
  sudo ./install.sh

Then edit /etc/hs-agent/agent.yaml (gatewayId must be the uuid the fleet registry assigned),
review /etc/hs-agent/policy.yaml, and `systemctl enable --now hs-agent`. The installer does not
start the service: an agent must not begin transmitting under a placeholder identity.

Requirements
------------

  * Java 21 (JAVA_HOME, or java on PATH)
  * a modem on /dev/ttyUSB* and membership of the dialout group
  * reachability to the Kafka brokers in agent.yaml

Pin the modem by its stable path, not by number:

  ls -l /dev/serial/by-id/
    usb-HUAWEI_Technology_HUAWEI_Mobile-if00-port0 -> ../../ttyUSB1

A dongle that resets comes back with a different ttyUSB number, so a pin of
/dev/ttyUSB0 can silently point at the wrong interface (or at nothing) after a
brown-out. This really happens: during the first field test the modem dropped
off the bus mid-transmit on a switched USB port and re-enumerated as
/dev/ttyUSB1.

Files it writes
---------------

  /var/lib/hs-agent/spool          outbound records queued while Kafka is unreachable
  /var/lib/hs-agent/dedupe.ids     claimed emission ids, so a redelivery is not sent twice
  /var/lib/hs-agent/cancellations.ids
  /var/lib/hs-agent/audit/         the hash-chained audit log, audit-0001.jsonl, ...
  /var/lib/hs-agent/admin.token    bearer token for the loopback admin API
  /var/log/hs-agent/agent-clean.log  scrubbed: safe to send to a vendor
  /var/log/hs-agent/agent.log        the operator's copy, with raw values; owner-only

Verifying the device
--------------------

  hs-agent ctl status              gateway, uptime, spool depth, audit verdict
  hs-agent ctl modems              the claimed modem (IMSI masked)
  hs-agent ctl pause|resume        stop and start sending without a restart
  hs-agent ctl kill                refuse everything until the service is restarted
  hs-agent ctl policy reload       re-read policy.yaml

The audit chain can also be verified offline, without the agent running. Note `--dir`, which the
first version of this file omitted:

  java -cp 'lib/*' com.hushsign.agent.audit.AuditCli verify --dir /var/lib/hs-agent/audit
  java -cp 'lib/*' com.hushsign.agent.audit.AuditCli tail   --dir /var/lib/hs-agent/audit --count 20
  java -cp 'lib/*' com.hushsign.agent.audit.AuditCli export --dir /var/lib/hs-agent/audit --out /tmp/audit.txt

The process exits non-zero when the chain does not hold, so it can gate a script.
