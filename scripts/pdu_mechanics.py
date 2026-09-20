#!/usr/bin/env python3
"""P1-S14: A/B the two remaining send mechanics against the K3765:
ASCII-hex PDU vs binary PDU, and zero-delay Ctrl-Z vs paused.
Usage: python pdu_mechanics.py <port>
"""
import sys
import time

import serial

PDU_HEX = "0001000B910457260745F1000012C8FA1C3D4D9FDD2068AC358DD140F4F29C0E"


def attempt(ser, label, body, pause_before_ctrlz):
    ser.write(b"AT+CMGF=0\r")
    time.sleep(0.7)
    ser.read(ser.in_waiting)
    ser.write(b"AT+CMGS=%d\r" % (len(PDU_HEX) // 2))
    time.sleep(1.2)
    ser.read(ser.in_waiting)
    ser.write(body)
    if pause_before_ctrlz:
        time.sleep(0.2)
    ser.write(b"\x1a")
    time.sleep(8)
    print(label, "->", ser.read(ser.in_waiting))


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "COM7"
    ser = serial.Serial(port, 115200, timeout=0.2)
    ser.write(b"AT\r")
    time.sleep(0.5)
    ser.read(ser.in_waiting)

    attempt(ser, "1 binary, no pause", bytes.fromhex(PDU_HEX), False)
    attempt(ser, "2 ascii-hex text, no pause", PDU_HEX.encode(), False)
    attempt(ser, "3 binary, 200ms pause", bytes.fromhex(PDU_HEX), True)
    attempt(ser, "4 ascii-hex text, 200ms pause", PDU_HEX.encode(), True)
    ser.close()


if __name__ == "__main__":
    main()
