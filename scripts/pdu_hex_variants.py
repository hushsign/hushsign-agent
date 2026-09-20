#!/usr/bin/env python3
"""P1-S14: fork-style ASCII-HEX PDU submission variants (old system sent the
PDU as hex TEXT — Huawei PCUI quirk). Usage: python pdu_hex_variants.py <port>
"""
import sys
import time

import serial

TPDU = "01000B910457260745F1000012C8FA1C3D4D9FDD2068AC358DD140F4F29C0E"
FULL = "00" + TPDU
SMSC_EXPLICIT = "079140749464000F"


def attempt(ser, label, hex_text, size, crlf=False):
    ser.write(b"AT+CMGF=0\r")
    time.sleep(0.7)
    ser.read(ser.in_waiting)
    ser.write(b"AT+CMGS=%d%s" % (size, b"\r\n" if crlf else b"\r"))
    time.sleep(1.5)
    ser.read(ser.in_waiting)
    ser.write(hex_text.encode())
    time.sleep(0.1)
    ser.write(b"\x1a")
    time.sleep(8)
    print(label, "->", ser.read(ser.in_waiting))


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "COM6"
    ser = serial.Serial(port, 115200, timeout=0.2)
    ser.write(b"AT\r")
    time.sleep(0.5)
    ser.read(ser.in_waiting)

    attempt(ser, "1 full hex text, CMGS=octets", FULL, len(FULL) // 2)
    attempt(ser, "2 tpdu hex text, CMGS=31", TPDU, len(TPDU) // 2)
    attempt(ser, "3 explicit-smc hex text, CMGS=39", SMSC_EXPLICIT + TPDU, (len(SMSC_EXPLICIT) + len(TPDU)) // 2)
    attempt(ser, "4 full hex text, CMGS=64 (hex char count)", FULL, len(FULL))
    attempt(ser, "5 full hex text, CRLF line ending", FULL, len(FULL) // 2, crlf=True)
    ser.close()


if __name__ == "__main__":
    main()
