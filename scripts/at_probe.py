#!/usr/bin/env python3
"""P1-S14 raw AT probe for the Huawei K3765 — field evidence only.

Checks SIM/network state, PDU mode, and tries one CMGS prompt dance.
Usage: python3 at_probe.py /dev/ttyUSB0
"""
import sys
import time

import serial

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
PDU = "0021000B910457260745F1400000"  # SILENT_TP0 -> +40756270541


def run(ser, cmd, wait=0.6, prompt_wait=3.0, tail=b""):
    """Send a command, drain responses until stable."""
    out = bytearray()
    ser.reset_input_buffer()
    ser.write(cmd)
    deadline = time.time() + (prompt_wait if tail else wait)
    while time.time() < deadline:
        n = ser.in_waiting
        if n:
            chunk = ser.read(n)
            out.extend(chunk)
            if tail and tail in out:
                break
            deadline = time.time() + (prompt_wait if tail else wait)
        time.sleep(0.05)
    return out


def main():
    ser = serial.Serial(PORT, 115200, timeout=0.2)
    print(f"--- open {PORT} ok")

    print("--- AT:", run(ser, b"AT\r"))
    print("--- AT+CFUN=1:", run(ser, b"AT+CFUN=1\r", prompt_wait=6.0))
    print("--- AT:", run(ser, b"AT\r"))
    print("--- ATI:", run(ser, b"ATI\r"))
    print("--- AT+CPIN?:", run(ser, b"AT+CPIN?\r"))
    print("--- AT+CIMI:", run(ser, b"AT+CIMI\r"))
    print("--- AT+CSQ:", run(ser, b"AT+CSQ\r"))
    print("--- AT+CREG?:", run(ser, b"AT+CREG?\r"))
    print("--- AT+CMGF?:", run(ser, b"AT+CMGF?\r"))
    print("--- AT+CMGF=0:", run(ser, b"AT+CMGF=0\r"))
    print("--- AT+CMGS=14:", run(ser, b"AT+CMGS=14\r", prompt_wait=4.0, tail=b"> "))

    # After the prompt: PDU + Ctrl-Z, watch the verdict.
    ser.reset_input_buffer()
    ser.write(bytes.fromhex(PDU))
    time.sleep(0.05)
    ser.write(b"\x1a")
    verdict = run(ser, b"", prompt_wait=15.0, tail=b"")
    print("--- verdict:", verdict)
    ser.close()


if __name__ == "__main__":
    main()
