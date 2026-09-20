#!/usr/bin/env python3
"""P1-S14: replicate PduSpy's exact pre-send recipe (CMEE=1, CNMI=?, CPMS with
the 'AR' Archive storage) and then send one PDU. Usage: python pdu_recipe.py <port>
"""
import sys
import time

import serial

DA = "0B910457260745F1"  # -> +40756270541
PDU = "00" + "0100" + DA + "0000" + "12C8FA1C3D4D9FDD2068AC358DD140F4F29C0E"


def cmd(ser, at, wait=0.8):
    ser.write(at)
    time.sleep(wait)
    out = ser.read(ser.in_waiting)
    print(at.decode().strip(), "->", out)
    return out


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "COM7"
    ser = serial.Serial(port, 115200, timeout=0.2)

    cmd(ser, b"AT+CMEE=1\r")
    cmd(ser, b"AT+CMGF=0\r")
    cmd(ser, b"AT+CNMI=?\r")
    cmd(ser, b"AT+CPMS=\"AR\",\"MT\",\"SM\"\r")

    pdu = bytes.fromhex(PDU)
    ser.write(b"AT+CMGS=%d\r" % len(pdu))
    time.sleep(1.2)
    print("CMGS ->", ser.read(ser.in_waiting))
    ser.write(pdu)
    time.sleep(0.2)
    ser.write(b"\x1a")
    time.sleep(8)
    print("verdict ->", ser.read(ser.in_waiting))
    ser.close()


if __name__ == "__main__":
    main()
