#!/usr/bin/env python3
"""P1-S14: send SILENT_TP0 with the K3765-proven recipe — TPDU as ASCII hex
text, CMGS length excluding the SMSC octet. Usage: python send_silent_tpdu.py <port>
"""
import sys
import time

import serial

FULL_PDU = "0021000B910457260745F1400000"  # SILENT_TP0 -> +40756270541
TPDU = FULL_PDU[2:]  # drop the SMSC octet


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "COM6"
    ser = serial.Serial(port, 115200, timeout=0.2)

    ser.write(b"AT\r")
    time.sleep(0.5)
    print("AT:", ser.read(ser.in_waiting))

    ser.write(b"AT+CMGF=0\r")
    time.sleep(0.7)
    print("CMGF=0:", ser.read(ser.in_waiting))

    ser.write(b"AT+CPIN?\r")
    time.sleep(0.7)
    print("CPIN?:", ser.read(ser.in_waiting))

    print(f"SILENT_TP0 TPDU ({len(TPDU) // 2} octets): {TPDU}")
    ser.write(b"AT+CMGS=%d\r" % (len(TPDU) // 2))
    time.sleep(1.5)
    print("prompt:", ser.read(ser.in_waiting))
    ser.write(TPDU.encode())
    time.sleep(0.1)
    ser.write(b"\x1a")
    time.sleep(10)
    print("verdict:", ser.read(ser.in_waiting))
    ser.close()


if __name__ == "__main__":
    main()
