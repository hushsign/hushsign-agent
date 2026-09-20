#!/usr/bin/env python3
"""P1-S14: exhaust the K3765 PDU-mode quirks that could cause CMS 304."""
import sys
import time

import serial

DA = "0B910457260745F1"  # -> +40756270541
TPDU = ("0100" + DA + "0000"  # FO/MR, DA, PID/DCS
        + "12C8FA1C3D4D9FDD2068AC358DD140F4F29C0E")  # UDL + 7-bit "HushSign P1-S14 test"
SMSC_EXPLICIT = "079140749464000F"  # +40744946000, Orange RO


def attempt(ser, label, pdu_hex):
    pdu = bytes.fromhex(pdu_hex)
    ser.write(b"AT+CMGS=%d\r" % len(pdu))
    time.sleep(1.2)
    ser.read(ser.in_waiting)
    ser.write(pdu)
    time.sleep(0.15)
    ser.write(b"\x1a")
    time.sleep(7)
    verdict = ser.read(ser.in_waiting)
    print(f"{label}: {verdict}")
    return verdict


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "COM7"
    ser = serial.Serial(port, 115200, timeout=0.2)

    ser.write(b"AT\r")
    time.sleep(0.5)
    ser.read(ser.in_waiting)

    print("CSMS=1:", end=" ")
    ser.write(b"AT+CSMS=1\r")
    time.sleep(0.6)
    print(ser.read(ser.in_waiting))

    print("CMGF=0:", end=" ")
    ser.write(b"AT+CMGF=0\r")
    time.sleep(0.6)
    print(ser.read(ser.in_waiting))

    attempt(ser, "A full PDU, SMSC=00", "00" + TPDU)
    attempt(ser, "B explicit SMSC", SMSC_EXPLICIT + TPDU)
    attempt(ser, "C MR=01", "00" + "0111" + DA + "0000" + "12C8FA1C3D4D9FDD2068AC358DD140F4F29C0E")

    print("ATE0:", end=" ")
    ser.write(b"ATE0\r")
    time.sleep(0.6)
    print(ser.read(ser.in_waiting))
    attempt(ser, "D echo off + full PDU", "00" + TPDU)

    print("E text-mode sanity:", end=" ")
    ser.write(b"AT+CMGF=1\r")
    time.sleep(0.6)
    ser.read(ser.in_waiting)
    ser.write(b'AT+CMGS="40756270541"\r')
    time.sleep(1.2)
    ser.read(ser.in_waiting)
    ser.write(b"HushSign P1-S14 sanity")
    time.sleep(0.15)
    ser.write(b"\x1a")
    time.sleep(7)
    print(ser.read(ser.in_waiting))
    ser.close()


if __name__ == "__main__":
    main()
