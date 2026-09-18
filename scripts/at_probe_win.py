#!/usr/bin/env python3
"""P1-S14 Windows AT probe: scans all COM ports, identifies the Huawei stick,
and reports SIM/network state. Run after the vendor serial driver is bound.

Usage: python at_probe_win.py [--all]
"""
import sys
import time

import serial
from serial.tools import list_ports


def run(ser, cmd, wait=0.8):
    out = bytearray()
    ser.reset_input_buffer()
    ser.write(cmd)
    deadline = time.time() + wait
    while time.time() < deadline:
        n = ser.in_waiting
        if n:
            chunk = ser.read(n)
            out.extend(chunk)
            deadline = time.time() + wait
        time.sleep(0.05)
    return out


def probe(port_name, show_all):
    try:
        ser = serial.Serial(port_name, 115200, timeout=0.2)
    except Exception as e:
        if show_all:
            print(f"{port_name}: cannot open: {e}")
        return
    ati = run(ser, b"ATI\r")
    if b"OK" not in ati:
        if show_all:
            print(f"{port_name}: no AT answer: {ati!r}")
        ser.close()
        return
    print(f"=== {port_name}")
    print("ATI:", ati)
    print("CPIN?:", run(ser, b"AT+CPIN?\r"))
    print("^SYSINFO:", run(ser, b"AT^SYSINFO\r"))
    print("CSQ:", run(ser, b"AT+CSQ\r"))
    print("CREG?:", run(ser, b"AT+CREG?\r"))
    print("CMGF=0:", run(ser, b"AT+CMGF=0\r"))
    ser.close()


def main():
    show_all = "--all" in sys.argv
    ports = [p for p in list_ports.comports()
             if not (p.hwid or "").upper().startswith("BTHENUM")]
    print("COM ports:", [(p.device, p.description) for p in ports])
    for p in ports:
        if not show_all:
            hwid = (p.hwid or "") + (p.description or "")
            if "12D1" not in hwid.upper() and "HUAWEI" not in hwid.upper():
                continue
        probe(p.device, show_all)


if __name__ == "__main__":
    main()
